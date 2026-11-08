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
    private final ObjectMapper mapper;

    public PythonRepairNode(ArchiveProperties properties, RequestIdAllocationService numbers,
                            DeliveryRepository deliveries, ObjectMapper mapper) {
        this.properties = properties;
        this.numbers = numbers;
        this.deliveries = deliveries;
        this.mapper = mapper;
    }

    @Override public String type() { return "python.repair"; }

    /** repair 接收 entityCode；逐来源保留原始 artifact 和合并产物的独立 file_mapping，metadataIds 与映射全集一致。 */
    @Override public NodeResult execute(NodeContext context) throws Exception {
        JsonNode mergedOutput = NodeSupport.previousOutput(context, "package.merge");
        Path source = Path.of(mergedOutput.required("workspacePath").asText());
        int count = mergedOutput.required("requestCount").asInt();
        String namespace = deliveries.find(context.task().deliveryId()).orElseThrow().deliveryBatch();
        RequestIdAllocationService.NumberRange range = numbers.reserve(context.source().dataId(), count, namespace);
        Path target = Path.of(properties.getWorkRoot(), "delivery-" + context.task().deliveryId(),
                "task-" + context.task().taskId(), "repair");
        Path manifestPath = target.resolve("repair-result.json");
        long maxPackageBytes = context.pipeline().maxPackageBytes();
        List<String> command = new ArrayList<>(List.of(properties.getPythonCommand(), properties.getPythonScript(),
                "--source", source.toString(), "--target", target.toString(),
                "--delivery-date", context.task().deliveryDate().toString(),
                "--entity-code", context.entity().entityCode(),
                "--start-number", Long.toString(range.start()), "--max-bytes", Long.toString(maxPackageBytes),
                "--output-name", context.task().newFileName(), "--original-name", context.task().originalFileName(),
                "--merge-manifest", mergedOutput.required("manifestPath").asText(),
                "--manifest", manifestPath.toString()));
        log.info("Repair started deliveryId={} taskId={} processId={} sourceCount={} requestCount={} entityCode={} vendorCode={} outputName={} maxPackageBytes={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), context.sources().size(), count,
                context.entity().entityCode(), context.entity().vendorCode(), context.task().newFileName(), maxPackageBytes);
        JsonNode manifest = mapper.readTree(ProcessSupport.runJson(command, null, Duration.ofMinutes(10), "REPAIR_RESULT "));
        if (manifest.required("requestCount").asInt() != count || manifest.required("artifacts").size() != 1) {
            throw new IllegalStateException("Repair request count or artifact count changed");
        }
        JsonNode artifact = manifest.required("artifacts").required(0);
        JsonNode mergeManifest = mapper.readTree(java.nio.file.Files.readString(
                Path.of(mergedOutput.required("manifestPath").asText())));
        java.util.Map<String, Integer> memberSources = new java.util.HashMap<>();
        mergeManifest.required("members").forEach(member ->
                memberSources.put(member.required("memberKey").asText(), member.required("sourceSeq").asInt()));
        ArrayNode artifactPairs = mapper.createArrayNode();
        int sourceSeq = 0;
        for (SourceData taskSource : context.sources()) {
            sourceSeq++;
            ObjectNode scopedArtifact = artifact.deepCopy();
            ObjectNode fileMapping = scopedArtifact.putObject("file_mapping");
            for (JsonNode mapping : manifest.required("mappings")) {
                if (memberSources.getOrDefault(mapping.required("memberKey").asText(), -1) == sourceSeq) {
                    fileMapping.put(mapping.required("originalPath").asText(), mapping.required("newRequestId").asText());
                }
            }
            if (fileMapping.isEmpty()) throw new IllegalStateException("Source artifact mapping is missing for dataId=" + taskSource.dataId());
            ObjectNode pair = artifactPairs.addObject();
            pair.put("dataId", taskSource.dataId());
            pair.set("sourceArtifact", taskSource.artifact().deepCopy());
            pair.set("deliveryArtifact", scopedArtifact);
        }
        ArrayNode metadataIds = mapper.createArrayNode();
        manifest.required("mappings").forEach(mapping -> metadataIds.add(mapping.required("newRequestId").asText()));
        String ruleVersion = String.valueOf(context.node().config().getOrDefault("ruleSet", "production-archive-v2"));
        SourceDataPatch patch = new SourceDataPatch(ruleVersion, namespace, artifactPairs, metadataIds);
        ObjectNode input = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("workspacePath", source.toAbsolutePath().toString()).put("sourceCount", context.sources().size())
                .put("requestCount", count).put("entityCode", context.entity().entityCode())
                .put("vendorCode", context.entity().vendorCode());
        ObjectNode output = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("entityCode", context.entity().entityCode())
                .put("localPath", manifest.required("artifactPath").asText()).put("sizeBytes", artifact.required("sizeBytes").asLong())
                .put("sha256", artifact.required("sha256").asText()).put("requestCount", count)
                .put("sourceCount", context.sources().size()).set("metadataIds", metadataIds.deepCopy());
        output.put("verifiedAt", Instant.now().toString());
        log.info("Repair succeeded deliveryId={} taskId={} processId={} entityCode={} requestCount={} metadataCount={} sizeBytes={} sha256={} artifact={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), context.entity().entityCode(), count, metadataIds.size(),
                artifact.required("sizeBytes").asLong(), artifact.required("sha256").asText(), manifest.required("artifactPath").asText());
        return NodeResult.success(context.pipeline().successor(context.node().id()), input, output, patch);
    }
}
