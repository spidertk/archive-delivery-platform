package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.domain.SourceData;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeHandler;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.service.FileTransferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class ManifestQualifyNode implements NodeHandler {
    private static final Logger log = LoggerFactory.getLogger(ManifestQualifyNode.class);
    private final FileTransferService files;
    private final ObjectMapper mapper;

    public ManifestQualifyNode(FileTransferService files, ObjectMapper mapper) {
        this.files = files;
        this.mapper = mapper;
    }

    @Override public String type() { return "manifest.qualify"; }

    /** qualify 核对原始 artifact、同下标 data_id 以及 repair locator 中的最终 size/SHA。 */
    @Override public NodeResult execute(NodeContext context) throws Exception {
        // repair 成功 locator 是最终包大小、SHA、路径和请求总数的唯一执行事实。
        JsonNode repairOutput = NodeSupport.previousOutput(context, "python.repair");
        // mapping 顺序是 artifacts 与 metadata_ids 的共同下标依据。
        List<SourceData> sources = context.sources();
        // 最终包事实不再从 delivery_task.artifacts 读取。
        long packageSize = repairOutput.required("sizeBytes").asLong();
        String packageSha = repairOutput.required("sha256").asText();
        // 开始日志分别暴露来源数和请求目录数。
        log.info("Manifest qualification started deliveryId={} taskId={} processId={} entityCode={} sourceCount={} requestCount={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), context.task().entityCode(),
                sources.size(), repairOutput.required("requestCount").asInt());
        // 每个来源必须恰好有一个原始 artifact 和一个 data_id。
        if (context.task().artifacts() == null || context.task().artifacts().size() != sources.size()
                || context.task().metadataIds() == null || !context.task().metadataIds().isArray()
                || context.task().metadataIds().size() != sources.size()) {
            throw new IllegalStateException("Every mapped source must have one sourceArtifact and one metadata dataId");
        }
        // repair locator 与任务落库的来源 data_id 列表必须完全一致。
        if (!context.task().metadataIds().equals(repairOutput.required("metadataIds"))) {
            throw new IllegalStateException("Task metadataIds differ from the repair dataId output");
        }
        // 最终包不能低于当前不可变 Pipeline 的下限。
        if (packageSize < context.pipeline().minPackageBytes()) {
            throw new IllegalStateException("Repaired artifact is below minPackageBytes: " + packageSize
                    + "<" + context.pipeline().minPackageBytes());
        }
        // 数据库任务汇总值必须与 repair 最终包一致。
        if (packageSize != context.task().sizeBytes() || repairOutput.required("requestCount").asInt() <= 0
                || !packageSha.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Task size or repair request/SHA facts are invalid");
        }
        // data_id 集合用于拒绝重复或错序来源。
        Set<Long> sourceDataIds = new HashSet<>();
        // 逐下标校验可避免来源、原始产物和 data_id 发生错位。
        for (int index = 0; index < sources.size(); index++) {
            // 当前 mapping 来源提供原始 artifact 的权威值。
            SourceData source = sources.get(index);
            // 同下标产物项只允许 dataId/sourceArtifact 两字段。
            JsonNode pair = context.task().artifacts().required(index);
            if (pair.size() != 2 || !pair.has("dataId") || !pair.has("sourceArtifact") || pair.has("deliveryArtifact")) {
                throw new IllegalStateException("Task artifacts must contain only dataId and sourceArtifact");
            }
            // 原始对象及其大小、摘要、文件名均不得被合并产物覆盖。
            if (pair.required("dataId").asLong() != source.dataId()
                    || !pair.required("sourceArtifact").equals(source.artifact())
                    || pair.required("sourceArtifact").required("sizeBytes").asLong() != source.sizeBytes()
                    || !pair.required("sourceArtifact").required("sha256").asText().equals(source.sha256())
                    || !pair.required("sourceArtifact").required("originalFileName").asText().equals(source.originalFileName())) {
                throw new IllegalStateException("sourceArtifact differs from source_data.artifact for dataId=" + source.dataId());
            }
            // metadata_ids 必须是与当前来源相同且全任务唯一的正整数 data_id。
            JsonNode metadataId = context.task().metadataIds().required(index);
            if (!metadataId.isIntegralNumber() || metadataId.asLong() <= 0 || metadataId.asLong() != source.dataId()
                    || !sourceDataIds.add(metadataId.asLong())) {
                throw new IllegalStateException("metadataIds must contain unique source_data dataId values in mapping order");
            }
        }
        // 最终文件路径只接受成功 repair 节点输出。
        Path path = Path.of(repairOutput.required("localPath").asText());
        // 独立读取文件大小和 SHA，防止仅数据库字段自洽。
        if (!Files.isRegularFile(path) || Files.size(path) != packageSize || !files.sha256(path).equals(packageSha)) {
            throw new IllegalStateException("Repair artifact size/SHA verification failed");
        }
        // locator 同时记录来源数与请求总数，metadataIds 只含 data_id。
        ObjectNode locator = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("localPath", path.toAbsolutePath().toString()).put("sizeBytes", Files.size(path))
                .put("sourceCount", sources.size()).put("metadataCount", context.task().metadataIds().size())
                .put("requestCount", repairOutput.required("requestCount").asInt())
                .put("sha256", packageSha).put("verifiedAt", Instant.now().toString());
        // 成功日志明确区分 metadataCount 和 requestCount。
        log.info("Manifest qualification succeeded deliveryId={} taskId={} processId={} entityCode={} sourceCount={} metadataCount={} requestCount={} sizeBytes={} sha256={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), context.task().entityCode(), sources.size(),
                context.task().metadataIds().size(), repairOutput.required("requestCount").asInt(), Files.size(path), packageSha);
        // 输入输出复用同一脱敏 locator，供上传节点继续执行。
        return NodeResult.success(context.pipeline().successor(context.node().id()), locator, locator);
    }
}
