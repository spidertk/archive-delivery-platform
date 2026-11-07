package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.config.ArchiveProperties;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeHandler;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.pipeline.SourceDataPatch;
import com.powerjob.archive.core.service.RequestIdAllocationService;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class PythonRepairNode implements NodeHandler {
    private final ArchiveProperties properties;
    private final RequestIdAllocationService numbers;
    private final ObjectMapper mapper;

    public PythonRepairNode(ArchiveProperties properties, RequestIdAllocationService numbers, ObjectMapper mapper) {
        this.properties = properties;
        this.numbers = numbers;
        this.mapper = mapper;
    }

    @Override public String type() { return "python.repair"; }

    /** Java 提供交付日期、业务账户和幂等号段；Python 只返回数据面产物与七字段 artifacts。 */
    @Override public NodeResult execute(NodeContext context) throws Exception {
        Path source = Path.of(NodeSupport.previousOutput(context, "oss.download").required("localPath").asText());
        JsonNode inspection = mapper.readTree(ProcessSupport.run(List.of(properties.getPythonCommand(), properties.getPythonScript(),
                "--source", source.toString(), "--inspect"), null, Duration.ofMinutes(2)));
        int count = inspection.required("requestCount").asInt();
        String namespace = "customer-request:" + context.task().deliveryDate().toString().replace("-", "");
        RequestIdAllocationService.NumberRange range = numbers.reserve(context.source().dataId(), count, namespace);
        Path target = Path.of(properties.getWorkRoot(), "delivery-" + context.task().deliveryId(),
                "data-" + context.source().dataId(), "repair");
        Path manifestPath = target.resolve("repair-result.json");
        List<String> command = new ArrayList<>(List.of(properties.getPythonCommand(), properties.getPythonScript(),
                "--source", source.toString(), "--target", target.toString(),
                "--delivery-date", context.task().deliveryDate().toString(),
                "--delivery-account", context.entity().accountName(),
                "--start-number", Long.toString(range.start()), "--manifest", manifestPath.toString()));
        JsonNode manifest = mapper.readTree(ProcessSupport.run(command, null, Duration.ofMinutes(10)));
        if (manifest.required("requestCount").asInt() != count || manifest.required("artifacts").size() != 1) {
            throw new IllegalStateException("Repair request count or artifact count changed");
        }
        JsonNode artifact = manifest.required("artifacts").get(0);
        if (artifact.required("artifactSeq").asInt() != context.task().artifactSeq()) throw new IllegalStateException("artifactSeq changed");
        JsonNode firstMapping = manifest.required("mappings").required(0);
        ObjectNode outputLocator = mapper.createObjectNode().put("schemaVersion", 1)
                .put("memberPath", firstMapping.required("newRequestId").asText() + "/")
                .put("manifestPath", firstMapping.required("newRequestId").asText() + "/metadata.json");
        SourceDataPatch patch = new SourceDataPatch(firstMapping.path("originalRequestId").asText(null),
                firstMapping.required("newRequestId").asText(), namespace, outputLocator, "QUALIFIED",
                String.valueOf(context.node().config().getOrDefault("ruleSet", "fix-archive-legacy-v1")),
                artifact.required("sizeBytes").asLong(), artifact.required("token").asLong(), artifact.required("sha256").asText(),
                manifest.required("artifacts"));
        ObjectNode input = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("localPath", source.toAbsolutePath().toString()).put("sha256", context.source().sha256());
        ObjectNode output = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("localPath", manifest.required("artifactPath").asText()).put("sizeBytes", artifact.required("sizeBytes").asLong())
                .put("sha256", artifact.required("sha256").asText()).put("verifiedAt", Instant.now().toString());
        return NodeResult.success(context.pipeline().successor(context.node().id()), input, output, patch);
    }
}
