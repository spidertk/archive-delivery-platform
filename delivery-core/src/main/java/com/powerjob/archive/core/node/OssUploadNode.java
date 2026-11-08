package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.config.ArchiveProperties;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeHandler;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.pipeline.OssNodeConfig;
import com.powerjob.archive.core.pipeline.PipelineNodeConfig;
import com.powerjob.archive.core.repository.DeliveryRepository;
import com.powerjob.archive.core.service.OssTransferService;
import com.powerjob.archive.core.service.RemotePathService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

@Component
public class OssUploadNode implements NodeHandler {
    private static final Logger log = LoggerFactory.getLogger(OssUploadNode.class);
    private final OssTransferService transfers;
    private final PipelineNodeConfig configs;
    private final RemotePathService paths;
    private final ArchiveProperties properties;
    private final DeliveryRepository deliveries;
    private final ObjectMapper mapper;

    public OssUploadNode(OssTransferService transfers, PipelineNodeConfig configs, RemotePathService paths,
                         ArchiveProperties properties, DeliveryRepository deliveries, ObjectMapper mapper) {
        this.transfers = transfers;
        this.configs = configs;
        this.paths = paths;
        this.properties = properties;
        this.deliveries = deliveries;
        this.mapper = mapper;
    }

    @Override public String type() { return "oss.upload"; }

    /** 项目 OSS SDK 直传并 GET 回验，不调用 ossutil；生产路径由提交批次和实体 vendorCode 模板生成。 */
    @Override public NodeResult execute(NodeContext context) throws Exception {
        Path artifact = NodeSupport.repairedPath(context, properties.getWorkRoot());
        if (!Files.isRegularFile(artifact) || Files.size(artifact) != context.task().sizeBytes()
                || context.task().deliveryArtifact() == null) {
            throw new IllegalStateException("Repaired artifact is missing, not qualified or size changed");
        }
        OssNodeConfig config = configs.oss(context.node(), false);
        String deliveryBatch = deliveries.find(context.task().deliveryId()).orElseThrow().deliveryBatch();
        String key = paths.objectKey(config.prefix(), context.task(), context.entity().vendorCode(), deliveryBatch);
        Path verified = NodeSupport.ossVerifiedPath(context, properties.getWorkRoot());
        log.info("OSS upload started deliveryId={} taskId={} processId={} bucket={} key={} sizeBytes={} sha256={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), config.bucket(), key,
                context.task().sizeBytes(), context.task().sha256());
        OssTransferService.UploadResult uploaded = transfers.upload(config, artifact, key, context.task().sha256(),
                context.task().deliveryId(), context.task().taskId(), verified);
        ObjectNode input = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("localPath", artifact.toAbsolutePath().toString()).put("sha256", context.task().sha256());
        ObjectNode output = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("uri", uploaded.uri()).put("localPath", verified.toAbsolutePath().toString())
                .put("sizeBytes", uploaded.sizeBytes()).put("sha256", uploaded.sha256()).put("verifiedAt", Instant.now().toString());
        log.info("OSS upload and GET verification succeeded deliveryId={} taskId={} processId={} uri={} sizeBytes={} sha256={} verifiedPath={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), uploaded.uri(),
                uploaded.sizeBytes(), uploaded.sha256(), verified);
        return NodeResult.success(context.pipeline().successor(context.node().id()), input, output);
    }
}
