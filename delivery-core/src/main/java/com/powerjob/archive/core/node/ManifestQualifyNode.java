package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeHandler;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.service.FileTransferService;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

@Component
public class ManifestQualifyNode implements NodeHandler {
    private static final Set<String> ARTIFACT_FIELDS = Set.of("artifactSeq", "originalFileName", "newFileName", "sizeBytes", "sha256", "token", "file_mapping");
    private final FileTransferService files;
    private final ObjectMapper mapper;

    public ManifestQualifyNode(FileTransferService files, ObjectMapper mapper) {
        this.files = files;
        this.mapper = mapper;
    }

    @Override public String type() { return "manifest.qualify"; }

    /** qualify 重新读取本地 repair 产物并核对七字段契约、任务快照和真实 size/SHA。 */
    @Override public NodeResult execute(NodeContext context) throws Exception {
        if (!context.source().artifacts().isArray() || context.source().artifacts().size() != 1) {
            throw new IllegalStateException("Exactly one repaired artifact is required for this task");
        }
        JsonNode artifact = context.source().artifacts().get(0);
        if (!artifact.fieldNames().hasNext()) throw new IllegalStateException("Empty artifact");
        artifact.fieldNames().forEachRemaining(name -> {
            if (!ARTIFACT_FIELDS.contains(name)) throw new IllegalArgumentException("Unknown artifact field " + name);
        });
        if (artifact.size() != 7 || artifact.required("artifactSeq").asInt() != context.task().artifactSeq()
                || !artifact.required("sha256").asText().matches("[0-9a-f]{64}")
                || !artifact.required("file_mapping").isObject() || artifact.required("file_mapping").isEmpty()) {
            throw new IllegalStateException("Artifact contract or request mapping is invalid");
        }
        Path path = Path.of(NodeSupport.previousOutput(context, "python.repair").required("localPath").asText());
        if (!Files.isRegularFile(path) || Files.size(path) != artifact.required("sizeBytes").asLong()
                || !files.sha256(path).equals(artifact.required("sha256").asText())) {
            throw new IllegalStateException("Repair artifact size/SHA verification failed");
        }
        ObjectNode locator = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("localPath", path.toAbsolutePath().toString()).put("sizeBytes", Files.size(path))
                .put("sha256", artifact.required("sha256").asText()).put("verifiedAt", Instant.now().toString());
        return NodeResult.success(context.pipeline().successor(context.node().id()), locator, locator);
    }
}
