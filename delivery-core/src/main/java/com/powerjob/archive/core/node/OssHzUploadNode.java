package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeHandler;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.repository.OutputPackageRepository;
import com.powerjob.archive.core.service.OssTransferService;
import com.powerjob.archive.core.service.RemotePathService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class OssHzUploadNode implements NodeHandler {
    private final OssTransferService transfers;
    private final RemotePathService paths;
    private final OutputPackageRepository outputs;
    private final JdbcTemplate jdbc;
    private final com.powerjob.archive.core.pipeline.PipelineNodeConfig configs;

    public OssHzUploadNode(OssTransferService transfers, RemotePathService paths,
                           OutputPackageRepository outputs, JdbcTemplate jdbc,
                           com.powerjob.archive.core.pipeline.PipelineNodeConfig configs) {
        this.transfers = transfers;
        this.paths = paths;
        this.outputs = outputs;
        this.jdbc = jdbc;
        this.configs = configs;
    }

    @Override
    public String type() { return "oss.hz-upload"; }

    @Override
    public NodeResult execute(NodeContext context) throws Exception {
        JsonNode artifact = context.output().manifest().required("artifacts").required(0);
        String key = paths.objectKey(context.output().id());
        long runId = jdbc.queryForObject("SELECT s.run_id FROM source_package s WHERE s.id=?",
                Long.class, context.output().sourcePackageId());
        var config = configs.oss(context.node(), false);
        OssTransferService.UploadResult result = transfers.uploadHz(config, Path.of(artifact.required("path").asText()),
                key, artifact.required("sha256").asText(), runId, context.output().id());
        String uri = transfers.hzUri(config, key);
        outputs.updatePath(context.output().id(), "hz_path", uri);
        outputs.updateArtifactPath(context.output().id(), "hz_path", uri);
        jdbc.update("UPDATE output_package SET hz_uploaded_at=COALESCE(hz_uploaded_at,now()),updated_at=now() WHERE id=?",
                context.output().id());
        JsonNode manifest = context.output().manifest().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest).put("hzPath", uri);
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest).put("hzUploaded", result.uploaded());
        return NodeResult.of("sgVerify", "HZ_UPLOADED", manifest);
    }
}
