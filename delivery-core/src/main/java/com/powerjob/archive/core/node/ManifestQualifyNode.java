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
import java.util.List;

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

    @Override public NodeResult execute(NodeContext context) throws Exception {
        JsonNode repairOutput = NodeSupport.previousOutput(context, "python.repair");
        List<SourceData> sources = context.sources();
        long packageSize = repairOutput.required("sizeBytes").asLong();
        String packageSha = repairOutput.required("sha256").asText();
        if (context.task().artifacts() == null || context.task().artifacts().size() != sources.size()) {
            throw new IllegalStateException("Every delivery_batch source must have one sourceArtifact");
        }
        if (packageSize <= 0) {
            throw new IllegalStateException("Repaired artifact must not be empty");
        }
        if (packageSize != context.task().sizeBytes() || repairOutput.required("requestCount").asInt() <= 0
                || !packageSha.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Task size or repair request/SHA facts are invalid");
        }
        for (int index = 0; index < sources.size(); index++) {
            SourceData source = sources.get(index);
            JsonNode pair = context.task().artifacts().required(index);
            if (pair.size() != 2 || pair.required("dataId").asLong() != source.dataId()
                    || !pair.required("sourceArtifact").equals(source.artifact())) {
                throw new IllegalStateException("sourceArtifact differs from delivery_batch source dataId=" + source.dataId());
            }
        }
        Path path = Path.of(repairOutput.required("localPath").asText());
        if (!Files.isRegularFile(path) || Files.size(path) != packageSize || !files.sha256(path).equals(packageSha)) {
            throw new IllegalStateException("Repair artifact size/SHA verification failed");
        }
        ObjectNode locator = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("localPath", path.toAbsolutePath().toString()).put("sizeBytes", Files.size(path))
                .put("sourceCount", sources.size()).put("requestCount", repairOutput.required("requestCount").asInt())
                .put("sha256", packageSha).put("verifiedAt", Instant.now().toString());
        log.info("Manifest qualification succeeded deliveryId={} taskId={} processId={} entityCode={} sourceCount={} requestCount={} sizeBytes={} sha256={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), context.task().entityCode(), sources.size(),
                repairOutput.required("requestCount").asInt(), Files.size(path), packageSha);
        return NodeResult.success(context.pipeline().successor(context.node().id()), locator, locator);
    }
}
