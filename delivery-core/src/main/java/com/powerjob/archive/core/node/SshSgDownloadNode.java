package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.config.FtpDeliveryProperties;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeHandler;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.repository.OutputPackageRepository;
import com.powerjob.archive.core.service.RemotePathService;
import com.powerjob.archive.core.service.SshCommandRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class SshSgDownloadNode implements NodeHandler {
    private final SshCommandRunner ssh;
    private final FtpDeliveryProperties ftp;
    private final RemotePathService paths;
    private final OutputPackageRepository outputs;
    private final JdbcTemplate jdbc;

    public SshSgDownloadNode(SshCommandRunner ssh, FtpDeliveryProperties ftp, RemotePathService paths,
                             OutputPackageRepository outputs, JdbcTemplate jdbc) {
        this.ssh = ssh;
        this.ftp = ftp;
        this.paths = paths;
        this.outputs = outputs;
        this.jdbc = jdbc;
    }

    @Override
    public String type() { return "ssh.sg-download"; }

    @Override
    public NodeResult execute(NodeContext context) throws Exception {
        long outputId = context.output().id();
        long runId = jdbc.queryForObject("SELECT run_id FROM source_package WHERE id=?", Long.class,
                context.output().sourcePackageId());
        FtpDeliveryProperties.Line line = ftp.line(context.output().productionLineId());
        String stagingDir = line.getRemoteRoot() + "/.archive-mvp-staging/run-" + runId + "/package-" + outputId;
        String finalName = context.output().newPackageName();
        RemotePathService.validatePackageName(finalName);
        boolean clean = context.output().pipelineSnapshot().required("config").required("ftpCleanBeforeDownload").asBoolean();
        String sgUri = context.output().manifest().required("sgPath").asText();
        String sha = context.output().manifest().required("artifacts").required(0).required("sha256").asText();
        int timeout = ((Number) context.node().config().getOrDefault("commandTimeoutSeconds", 900)).intValue();
        ssh.run(context.output().productionLineId(), line.getDownloadScript(),
                List.of(sgUri, sha, stagingDir, finalName, Boolean.toString(clean)), Duration.ofSeconds(timeout));
        String stagingPath = stagingDir + "/" + finalName;
        outputs.updatePath(outputId, "sg_download_path", stagingPath);
        JsonNode manifest = context.output().manifest().deepCopy();
        ((ObjectNode) manifest).put("sgDownloadPath", stagingPath);
        return NodeResult.of("publish", "SG_DOWNLOADED", manifest);
    }
}
