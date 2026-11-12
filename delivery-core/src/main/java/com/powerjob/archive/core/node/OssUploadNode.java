package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.JsonNode;
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
        // repair locator 提供最终包 SHA，任务 artifacts 不再包含 deliveryArtifact。
        JsonNode repairOutput = NodeSupport.previousOutput(context, "python.repair");
        String expectedSha = repairOutput.required("sha256").asText();
        // 上传源只能是当前任务成功 repair 的本地最终包。
        Path artifact = NodeSupport.repairedPath(context);
        // 文件缺失、大小漂移或 repair/task 汇总不一致时禁止外部写入。
        if (!Files.isRegularFile(artifact) || Files.size(artifact) != context.task().sizeBytes()
                || repairOutput.required("sizeBytes").asLong() != context.task().sizeBytes()) {
            throw new IllegalStateException("Repaired artifact is missing, not qualified or size changed");
        }
        // Secret 只从当前不可变 OSS 节点配置读取。
        OssNodeConfig config = configs.oss(context.node(), false);
        // 提交批次同时用于路径隔离和幂等定位。
        String deliveryBatch = deliveries.find(context.task().deliveryId()).orElseThrow().deliveryBatch();
        // 对象键由厂商、实体和任务级唯一包名模板生成。
        String key = paths.objectKey(config.prefix(), context.task(), context.entity().vendorCode(),
                context.entity().accountName(), deliveryBatch);
        // GET 回验文件写入任务自己的受控目录，不覆盖 repair 包。
        Path verified = NodeSupport.ossVerifiedPath(context, properties.getWorkRoot());
        // 开始日志记录业务血缘和外部位置，不记录 AK/SK。
        log.info("OSS upload started deliveryId={} taskId={} processId={} entityCode={} bucket={} key={} sizeBytes={} sha256={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), context.task().entityCode(),
                config.bucket(), key, context.task().sizeBytes(), expectedSha);
        // SDK 上传后必须实际 GET 并核对 size/SHA 才返回成功。
        OssTransferService.UploadResult uploaded = transfers.upload(config, artifact, key, expectedSha,
                context.task().deliveryId(), context.task().taskId(), verified);
        // 输入 locator 只保存本地路径和摘要等脱敏事实。
        ObjectNode input = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("localPath", artifact.toAbsolutePath().toString()).put("sha256", expectedSha);
        // 输出 locator 保存可复核的 OSS URI、回验文件和文件事实。
        ObjectNode output = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("uri", uploaded.uri()).put("localPath", verified.toAbsolutePath().toString())
                .put("sizeBytes", uploaded.sizeBytes()).put("sha256", uploaded.sha256()).put("verifiedAt", Instant.now().toString());
        // 成功日志明确包含真实 GET 后的 size/SHA。
        log.info("OSS upload and GET verification succeeded deliveryId={} taskId={} processId={} uri={} sizeBytes={} sha256={} verifiedPath={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), uploaded.uri(),
                uploaded.sizeBytes(), uploaded.sha256(), verified);
        // 后继节点仍由 Pipeline DAG 决定。
        return NodeResult.success(context.pipeline().successor(context.node().id()), input, output);
    }
}
