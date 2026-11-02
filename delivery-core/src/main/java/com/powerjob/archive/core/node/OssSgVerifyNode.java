package com.powerjob.archive.core.node;

import com.aliyun.oss.model.ObjectMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeHandler;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.repository.OutputPackageRepository;
import com.powerjob.archive.core.service.CrrWaitTimeoutException;
import com.powerjob.archive.core.service.OssTransferService;
import com.powerjob.archive.core.service.RemoteConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
public class OssSgVerifyNode implements NodeHandler {
    private final OssTransferService transfers;
    private final OutputPackageRepository outputs;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final com.powerjob.archive.core.pipeline.PipelineNodeConfig configs;

    public OssSgVerifyNode(OssTransferService transfers, OutputPackageRepository outputs,
                           JdbcTemplate jdbc, Clock clock,
                           com.powerjob.archive.core.pipeline.PipelineNodeConfig configs) {
        this.transfers = transfers;
        this.outputs = outputs;
        this.jdbc = jdbc;
        this.clock = clock;
        this.configs = configs;
    }

    @Override
    public String type() { return "oss.sg-verify"; }

    @Override
    public NodeResult execute(NodeContext context) {
        String hzPath = context.output().manifest().required("hzPath").asText();
        String key = URI.create(hzPath).getPath().replaceFirst("^/", "");
        Timestamp uploaded = jdbc.queryForObject("SELECT hz_uploaded_at FROM output_package WHERE id=?",
                Timestamp.class, context.output().id());
        if (uploaded == null) throw new IllegalStateException("hz_uploaded_at is required before SG verification");
        Instant uploadedAt = uploaded.toInstant();
        Instant now = clock.instant();
        int waitSeconds = context.pipeline().intConfig("crrWaitSeconds");
        Instant firstCheck = uploadedAt.plusSeconds(waitSeconds);
        if (now.isBefore(firstCheck)) {
            return NodeResult.waiting("sgVerify", context.output().manifest(), Duration.between(now, firstCheck));
        }
        int pollSeconds = ((Number) context.node().config().getOrDefault("pollSeconds", 30)).intValue();
        int maxWaitSeconds = ((Number) context.node().config().getOrDefault("maxWaitSeconds", 7200)).intValue();
        var config = configs.oss(context.node(), false);
        var remote = transfers.headSg(config, key);
        if (remote.isEmpty()) {
            if (!now.isBefore(uploadedAt.plusSeconds(maxWaitSeconds))) throw new CrrWaitTimeoutException();
            return NodeResult.waiting("sgVerify", context.output().manifest(), Duration.ofSeconds(pollSeconds));
        }
        JsonNode artifact = context.output().manifest().required("artifacts").required(0);
        ObjectMetadata metadata = remote.get();
        String remoteSha = metadata.getUserMetadata() == null ? null : metadata.getUserMetadata().get("sha256");
        if (metadata.getContentLength() != artifact.required("bytes").asLong()
                || !artifact.required("sha256").asText().equals(remoteSha)) {
            throw new RemoteConflictException("SG OSS size or SHA differs from HZ artifact: " + key);
        }
        String uri = transfers.sgUri(config, key);
        outputs.updatePath(context.output().id(), "sg_source_path", uri);
        outputs.updateArtifactPath(context.output().id(), "sg_path", uri);
        JsonNode manifest = context.output().manifest().deepCopy();
        ((ObjectNode) manifest).put("sgPath", uri);
        return NodeResult.of("sgDownload", "SG_VERIFIED", manifest);
    }
}
