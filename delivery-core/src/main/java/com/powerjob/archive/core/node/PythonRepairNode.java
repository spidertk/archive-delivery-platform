package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.config.ArchiveProperties;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeHandler;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.pipeline.PipelineNodeConfig;
import com.powerjob.archive.core.pipeline.SourceDataPatch;
import com.powerjob.archive.core.repository.DeliveryRepository;
import com.powerjob.archive.core.service.BatchPlanningService;
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
    private final BatchPlanningService planning;
    private final PipelineNodeConfig configs;
    private final ObjectMapper mapper;

    public PythonRepairNode(ArchiveProperties properties, RequestIdAllocationService numbers, DeliveryRepository deliveries,
                            BatchPlanningService planning, PipelineNodeConfig configs, ObjectMapper mapper) {
        this.properties = properties;
        this.numbers = numbers;
        this.deliveries = deliveries;
        this.planning = planning;
        this.configs = configs;
        this.mapper = mapper;
    }

    @Override public String type() { return "python.repair"; }

    @Override public NodeResult execute(NodeContext context) throws Exception {
        JsonNode mergedOutput = NodeSupport.previousOutput(context, "package.merge");
        Path source = Path.of(mergedOutput.required("workspacePath").asText());
        int count = mergedOutput.required("requestCount").asInt();
        String namespace = deliveries.find(context.task().deliveryId()).orElseThrow().deliveryBatch();
        String requestKey = context.task().deliveryId() + ":" + context.task().newFileName();
        RequestIdAllocationService.NumberRange range = numbers.reserve(context.source().dataId(), Math.multiplyExact(count, 2), namespace, requestKey);
        Path target = Path.of(properties.getWorkRoot(), "delivery-" + context.task().deliveryId(),
                "task-" + context.task().taskId(), "repair");
        Path manifestPath = target.resolve("repair-result.json");
        long maxPackageBytes = planning.limits(context.task().deliveryId()).maxPackageBytes();
        Object configuredGzipLevel = context.node().config().getOrDefault("gzipCompressLevel", 9);
        if (!(configuredGzipLevel instanceof Number number) || number.intValue() < 1 || number.intValue() > 9) {
            throw new IllegalArgumentException("python.repair gzipCompressLevel must be an integer from 1 to 9");
        }
        int gzipLevel = number.intValue();
        List<String> command = new ArrayList<>(List.of(properties.getPythonCommand(),
                NodeSupport.script(configs, context.node()), "--source", source.toString(), "--target", target.toString(),
                "--delivery-date", context.task().deliveryDate().toString(),
                "--entity-code", context.entity().entityCode(),
                "--start-number", Long.toString(range.start()), "--max-bytes", Long.toString(maxPackageBytes),
                "--output-name", context.task().newFileName(), "--original-name", context.task().originalFileName(),
                "--merge-manifest", mergedOutput.required("manifestPath").asText(),
                "--manifest", manifestPath.toString(), "--gzip-level", Integer.toString(gzipLevel)));
        log.info("Repair started deliveryId={} taskId={} processId={} sourceCount={} requestCount={} entityCode={} outputName={} maxPackageBytes={} gzipLevel={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), context.sources().size(), count,
                context.entity().entityCode(), context.task().newFileName(), maxPackageBytes, gzipLevel);
        JsonNode manifest;
        try {
            manifest = mapper.readTree(ProcessSupport.runJson(command, null, Duration.ofMinutes(10), "REPAIR_RESULT "));
        } catch (IllegalStateException error) {
            if (error.getMessage() != null && error.getMessage().contains("Repaired artifact exceeds maxPackageBytes")) {
                throw new com.powerjob.archive.core.service.PackageOverflowException(error.getMessage(), error);
            }
            throw error;
        }
        if (manifest.required("requestCount").asInt() != count || manifest.required("artifacts").size() != 1) {
            throw new IllegalStateException("Repair request count or artifact count changed");
        }
        JsonNode artifact = manifest.required("artifacts").required(0);
        SourceDataPatch patch = new SourceDataPatch(namespace, null,
                artifact.required("sizeBytes").asLong(), artifact.required("token").asLong());
        ObjectNode input = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("scriptPath", configs.script(context.node()))
                .put("workspacePath", source.toAbsolutePath().toString()).put("sourceCount", context.sources().size())
                .put("requestCount", count).put("entityCode", context.entity().entityCode())
                .put("vendorCode", context.entity().vendorCode());
        ObjectNode output = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("entityCode", context.entity().entityCode())
                .put("localPath", manifest.required("artifactPath").asText()).put("sizeBytes", artifact.required("sizeBytes").asLong())
                .put("sha256", artifact.required("sha256").asText()).put("requestCount", count)
                .put("sourceCount", context.sources().size());
        output.set("timings", manifest.required("timings"));
        output.put("verifiedAt", Instant.now().toString());
        log.info("Repair succeeded deliveryId={} taskId={} processId={} entityCode={} sourceCount={} requestCount={} sizeBytes={} sha256={} artifact={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), context.entity().entityCode(), context.sources().size(), count,
                artifact.required("sizeBytes").asLong(), artifact.required("sha256").asText(), manifest.required("artifactPath").asText());
        return NodeResult.success(context.pipeline().successor(context.node().id()), input, output, patch);
    }
}
