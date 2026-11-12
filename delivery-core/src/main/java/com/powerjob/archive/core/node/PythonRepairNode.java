package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.config.ArchiveProperties;
import com.powerjob.archive.core.domain.SourceData;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeHandler;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.pipeline.PipelineNodeConfig;
import com.powerjob.archive.core.pipeline.SourceDataPatch;
import com.powerjob.archive.core.repository.DeliveryRepository;
import com.powerjob.archive.core.service.RequestIdAllocationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class PythonRepairNode implements NodeHandler {
    private static final Logger log = LoggerFactory.getLogger(PythonRepairNode.class);
    private final ArchiveProperties properties;
    private final RequestIdAllocationService numbers;
    private final DeliveryRepository deliveries;
    private final PipelineNodeConfig configs;
    private final ObjectMapper mapper;

    public PythonRepairNode(ArchiveProperties properties, RequestIdAllocationService numbers,
                            DeliveryRepository deliveries, PipelineNodeConfig configs, ObjectMapper mapper) {
        this.properties = properties;
        this.numbers = numbers;
        this.deliveries = deliveries;
        this.configs = configs;
        this.mapper = mapper;
    }

    @Override public String type() { return "python.repair"; }

    /** repair 只落原始 sourceArtifact 和有序 data_id，requestId 与最终 SHA 分别留在 manifest 和节点 locator。 */
    @Override public NodeResult execute(NodeContext context) throws Exception {
        // merge 输出提供安全解包目录、请求总数和成员血缘清单。
        JsonNode mergedOutput = NodeSupport.previousOutput(context, "package.merge");
        // repair 只消费 merge 保留的请求目录，不重新解包源文件。
        Path source = Path.of(mergedOutput.required("workspacePath").asText());
        // 请求总数用于核对修复前后没有目录丢失或新增。
        int count = mergedOutput.required("requestCount").asInt();
        // deliveryBatch 是请求编号分配的唯一命名空间。
        String namespace = deliveries.find(context.task().deliveryId()).orElseThrow().deliveryBatch();
        // 重试复用同一来源和批次的编号区间，避免请求编号漂移。
        RequestIdAllocationService.NumberRange range = numbers.reserve(context.source().dataId(), count, namespace);
        // 每个任务的 repair 文件限制在自己的受控工作目录。
        Path target = Path.of(properties.getWorkRoot(), "delivery-" + context.task().deliveryId(),
                "task-" + context.task().taskId(), "repair");
        // 结构化 manifest 保留完整 originalPath/newRequestId 供独立验收。
        Path manifestPath = target.resolve("repair-result.json");
        // 不可变 Pipeline 决定最终包上限和 gzip 级别，级别只接受 1–9，默认保持原行为。
        long maxPackageBytes = context.pipeline().maxPackageBytes();
        Object configuredGzipLevel = context.node().config().getOrDefault("gzipCompressLevel", 9);
        if (!(configuredGzipLevel instanceof Number number) || number.intValue() < 1 || number.intValue() > 9) {
            throw new IllegalArgumentException("python.repair gzipCompressLevel must be an integer from 1 to 9");
        }
        int gzipLevel = number.intValue();
        // 当前 PipelineVersion 决定本地或生产脚本，固定 argv 不接收 Secret 或任意 Shell。
        List<String> command = new ArrayList<>(List.of(properties.getPythonCommand(),
                NodeSupport.script(configs, context.node()), "--source", source.toString(), "--target", target.toString(),
                "--delivery-date", context.task().deliveryDate().toString(),
                "--entity-code", context.entity().entityCode(),
                "--start-number", Long.toString(range.start()), "--max-bytes", Long.toString(maxPackageBytes),
                "--output-name", context.task().newFileName(), "--original-name", context.task().originalFileName(),
                "--merge-manifest", mergedOutput.required("manifestPath").asText(),
                "--manifest", manifestPath.toString(), "--gzip-level", Integer.toString(gzipLevel)));
        // 开始日志包含执行血缘、实体、请求数、包边界和压缩级别，不记录凭据。
        log.info("Repair started deliveryId={} taskId={} processId={} sourceCount={} requestCount={} entityCode={} vendorCode={} outputName={} maxPackageBytes={} gzipLevel={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), context.sources().size(), count,
                context.entity().entityCode(), context.entity().vendorCode(), context.task().newFileName(), maxPackageBytes, gzipLevel);
        // 本地进程非零退出或非法 JSON 会直接阻断当前节点。
        JsonNode manifest = mapper.readTree(ProcessSupport.runJson(command, null, Duration.ofMinutes(10), "REPAIR_RESULT "));
        // 修复必须保留全部请求且只生成一个最终交付包。
        if (manifest.required("requestCount").asInt() != count || manifest.required("artifacts").size() != 1) {
            throw new IllegalStateException("Repair request count or artifact count changed");
        }
        // Python 结果提供最终文件大小、token 和 SHA，但不写入任务 artifacts。
        JsonNode artifact = manifest.required("artifacts").required(0);
        // artifacts 与 metadataIds 按 mapping 顺序共同描述原始来源。
        ArrayNode artifactPairs = mapper.createArrayNode();
        ArrayNode metadataIds = mapper.createArrayNode();
        for (SourceData taskSource : context.sources()) {
            // 每个来源只保存 dataId 和未经修改的原始 artifact。
            ObjectNode pair = artifactPairs.addObject();
            pair.put("dataId", taskSource.dataId());
            pair.set("sourceArtifact", taskSource.artifact().deepCopy());
            // metadata_ids 是同下标的 source_data.data_id，不是 requestId。
            metadataIds.add(taskSource.dataId());
            // 来源级日志用于定位具体 dataId 的处理顺序。
            log.debug("Repair source lineage deliveryId={} taskId={} processId={} dataId={} metadataIndex={}",
                    context.task().deliveryId(), context.task().taskId(), context.processId(), taskSource.dataId(),
                    metadataIds.size() - 1);
        }
        // 清洗版本由主单绑定的 PipelineVersion 统一追溯，不在来源上重复维护规则版本。
        // 最终 size/token 通过补丁独立提交，SHA 只保存在 repair 节点 locator。
        SourceDataPatch patch = new SourceDataPatch(namespace, artifactPairs, metadataIds,
                artifact.required("sizeBytes").asLong(), artifact.required("token").asLong());
        // 输入 locator 只记录脱敏执行事实和实体业务编码。
        ObjectNode input = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("scriptPath", configs.script(context.node()))
                .put("workspacePath", source.toAbsolutePath().toString()).put("sourceCount", context.sources().size())
                .put("requestCount", count).put("entityCode", context.entity().entityCode())
                .put("vendorCode", context.entity().vendorCode());
        // 输出 locator 明确区分来源数、请求总数和最终文件 SHA。
        ObjectNode output = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("entityCode", context.entity().entityCode())
                .put("localPath", manifest.required("artifactPath").asText()).put("sizeBytes", artifact.required("sizeBytes").asLong())
                .put("sha256", artifact.required("sha256").asText()).put("requestCount", count)
                .put("sourceCount", context.sources().size()).put("metadataCount", metadataIds.size())
                .set("metadataIds", metadataIds.deepCopy());
        output.put("verifiedAt", Instant.now().toString());
        // 成功日志记录最终文件事实，不包含 Secret 或请求映射。
        log.info("Repair succeeded deliveryId={} taskId={} processId={} entityCode={} sourceCount={} requestCount={} sizeBytes={} sha256={} artifact={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), context.entity().entityCode(), metadataIds.size(), count,
                artifact.required("sizeBytes").asLong(), artifact.required("sha256").asText(), manifest.required("artifactPath").asText());
        // 由 Pipeline 决定下一节点，处理器不直接更新任务状态。
        return NodeResult.success(context.pipeline().successor(context.node().id()), input, output, patch);
    }
}
