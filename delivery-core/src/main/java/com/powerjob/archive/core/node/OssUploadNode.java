package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.config.ArchiveProperties;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeHandler;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.pipeline.OssNodeConfig;
import com.powerjob.archive.core.pipeline.PipelineNodeConfig;
import com.powerjob.archive.core.service.OssTransferService;
import com.powerjob.archive.core.service.RemotePathService;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

@Component
public class OssUploadNode implements NodeHandler {
    private final OssTransferService transfers;
    private final PipelineNodeConfig configs;
    private final RemotePathService paths;
    private final ArchiveProperties properties;
    private final ObjectMapper mapper;

    public OssUploadNode(OssTransferService transfers, PipelineNodeConfig configs, RemotePathService paths,
                         ArchiveProperties properties, ObjectMapper mapper) {
        this.transfers = transfers;
        this.configs = configs;
        this.paths = paths;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override public String type() { return "oss.upload"; }

    /** 交付 OSS 同 URI 同 SHA 幂等，不同内容阻断；成功后实际 GET 回本地供最终 FTP 节点使用。 */
    @Override public NodeResult execute(NodeContext context) throws Exception {
        Path artifact = NodeSupport.repairedPath(context, properties.getWorkRoot());
        if (!Files.isRegularFile(artifact) || Files.size(artifact) != context.task().sizeBytes()) {
            throw new IllegalStateException("Repaired artifact is missing or size changed");
        }
        OssNodeConfig config = configs.oss(context.node(), false);
        String key = paths.objectKey(config.prefix(), context.task());
        Path verified = NodeSupport.ossVerifiedPath(context, properties.getWorkRoot());
        OssTransferService.UploadResult uploaded = transfers.upload(config, artifact, key, context.task().sha256(),
                context.task().deliveryId(), context.task().taskId(), verified);
        ObjectNode input = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("localPath", artifact.toAbsolutePath().toString()).put("sha256", context.task().sha256());
        ObjectNode output = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("uri", uploaded.uri()).put("localPath", verified.toAbsolutePath().toString())
                .put("sizeBytes", uploaded.sizeBytes()).put("sha256", uploaded.sha256()).put("verifiedAt", Instant.now().toString());
        return NodeResult.success(context.pipeline().successor(context.node().id()), input, output);
    }
}
