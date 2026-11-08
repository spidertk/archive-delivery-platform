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
    private static final Set<String> ARTIFACT_FIELDS = Set.of("artifactSeq", "originalFileName", "newFileName", "sizeBytes", "sha256", "token", "file_mapping");
    private final FileTransferService files;
    private final ObjectMapper mapper;

    public ManifestQualifyNode(FileTransferService files, ObjectMapper mapper) {
        this.files = files;
        this.mapper = mapper;
    }

    @Override public String type() { return "manifest.qualify"; }

    /** qualify 核对原始 source_data.artifact、任务产物对、metadata 全集和最终文件的真实 size/SHA。 */
    @Override public NodeResult execute(NodeContext context) throws Exception {
        JsonNode artifact = context.task().deliveryArtifact();
        if (artifact == null || context.task().artifacts().size() != context.sources().size()) {
            throw new IllegalStateException("Every mapped source must have one repaired delivery artifact pair");
        }
        validateArtifactContract(artifact);
        if (artifact.required("sizeBytes").asLong() != context.task().sizeBytes()
                || artifact.required("token").asLong() != context.task().tokenCount()
                || !artifact.required("newFileName").asText().equals(context.task().newFileName())) {
            throw new IllegalStateException("Task size/token/fileName differ from the repaired delivery artifact");
        }
        Set<String> metadataIds = new HashSet<>();
        context.task().metadataIds().forEach(id -> {
            if (!metadataIds.add(id.asText())) throw new IllegalStateException("metadataIds contain duplicates");
        });
        Set<String> mappedIds = new HashSet<>();
        List<SourceData> sources = context.sources();
        for (int index = 0; index < sources.size(); index++) {
            SourceData source = sources.get(index);
            JsonNode pair = context.task().artifacts().required(index);
            if (pair.required("dataId").asLong() != source.dataId()
                    || !pair.required("sourceArtifact").equals(source.artifact())
                    || pair.required("sourceArtifact").required("sizeBytes").asLong() != source.sizeBytes()
                    || !pair.required("sourceArtifact").required("sha256").asText().equals(source.sha256())
                    || !pair.required("sourceArtifact").required("originalFileName").asText().equals(source.originalFileName())) {
                throw new IllegalStateException("sourceArtifact differs from source_data.artifact for dataId=" + source.dataId());
            }
            JsonNode delivery = pair.required("deliveryArtifact");
            validateArtifactContract(delivery);
            ObjectNode deliveryPackage = delivery.deepCopy();
            deliveryPackage.remove("file_mapping");
            ObjectNode expectedPackage = artifact.deepCopy();
            expectedPackage.remove("file_mapping");
            if (!deliveryPackage.equals(expectedPackage) || delivery.required("file_mapping").isEmpty()) {
                throw new IllegalStateException("Source artifact mappings must share the verified package and be non-empty");
            }
            delivery.required("file_mapping").forEach(value -> {
                if (!mappedIds.add(value.asText())) throw new IllegalStateException("requestId is duplicated across source mappings");
            });
        }
        if (!metadataIds.equals(mappedIds) || context.task().metadataIds().size() != mappedIds.size()) {
            throw new IllegalStateException("metadataIds and file_mapping requestIds differ");
        }
        Path path = Path.of(NodeSupport.previousOutput(context, "python.repair").required("localPath").asText());
        if (!Files.isRegularFile(path) || Files.size(path) != artifact.required("sizeBytes").asLong()
                || !files.sha256(path).equals(artifact.required("sha256").asText())) {
            throw new IllegalStateException("Repair artifact size/SHA verification failed");
        }
        ObjectNode locator = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("localPath", path.toAbsolutePath().toString()).put("sizeBytes", Files.size(path))
                .put("sourceCount", sources.size()).put("metadataCount", metadataIds.size())
                .put("sha256", artifact.required("sha256").asText()).put("verifiedAt", Instant.now().toString());
        log.info("Manifest qualification succeeded deliveryId={} taskId={} processId={} entityCode={} sourceCount={} requestMappings={} sizeBytes={} sha256={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), context.task().entityCode(), sources.size(),
                metadataIds.size(), Files.size(path), artifact.required("sha256").asText());
        return NodeResult.success(context.pipeline().successor(context.node().id()), locator, locator);
    }

    private static void validateArtifactContract(JsonNode artifact) {
        artifact.fieldNames().forEachRemaining(name -> {
            if (!ARTIFACT_FIELDS.contains(name)) throw new IllegalArgumentException("Unknown artifact field " + name);
        });
        if (artifact.size() != 7 || artifact.required("artifactSeq").asInt() != 1
                || !artifact.required("sha256").asText().matches("[0-9a-f]{64}")
                || !artifact.required("file_mapping").isObject()) {
            throw new IllegalStateException("Artifact contract or request mapping is invalid");
        }
    }
}
