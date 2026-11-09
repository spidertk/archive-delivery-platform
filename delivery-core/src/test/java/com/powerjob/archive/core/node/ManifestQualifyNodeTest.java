package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.domain.DeliveryEntity;
import com.powerjob.archive.core.domain.DeliveryTask;
import com.powerjob.archive.core.domain.SourceData;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeDefinition;
import com.powerjob.archive.core.pipeline.PipelineDefinition;
import com.powerjob.archive.core.service.FileTransferService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManifestQualifyNodeTest {
    @TempDir Path temp;

    @Test void metadataContainsOrderedDataIdsAndArtifactsContainOnlySourceFacts() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        FileTransferService files = new FileTransferService();
        Path packagePath = temp.resolve("batch_00001.tar.gz");
        Files.writeString(packagePath, "merged-package");
        long size = Files.size(packagePath);
        String sha = files.sha256(packagePath);
        SourceData first = source(11, "first.tar.gz", mapper);
        SourceData second = source(12, "second.tar.gz", mapper);
        ArrayNode artifacts = mapper.createArrayNode();
        addPair(artifacts.addObject(), first);
        addPair(artifacts.addObject(), second);
        ArrayNode metadata = mapper.createArrayNode().add(11).add(12);
        DeliveryTask task = task(artifacts, metadata, size);
        PipelineDefinition pipeline = new PipelineDefinition("archive-delivery", 1,
                Map.of("assignment", Map.of("minPackageBytes", 1, "maxPackageBytes", 100)), Map.of(), Map.of(),
                List.of(new NodeDefinition("repair", "python.repair", "REPAIR", "Repair", "qualify", Map.of()),
                        new NodeDefinition("qualify", "manifest.qualify", "VERIFY", "Qualify", null, Map.of())));
        ObjectNode repairOutput = mapper.createObjectNode().put("localPath", packagePath.toString())
                .put("requestCount", 3).put("sizeBytes", size).put("sha256", sha);
        repairOutput.set("metadataIds", metadata.deepCopy());
        NodeContext context = new NodeContext(task, first, List.of(first, second),
                new DeliveryEntity(10, "alibaba", "jun", "Local", "FTP", "entity1", "group1", null, true),
                pipeline.node("qualify"), pipeline, 100, 1, "worker", Map.of("repair", repairOutput));

        var result = new ManifestQualifyNode(files, mapper).execute(context);
        assertEquals(2, result.outputLocator().required("metadataCount").asInt());
        assertEquals(3, result.outputLocator().required("requestCount").asInt());
        assertEquals(2, artifacts.required(0).size());
        assertFalse(artifacts.required(0).has("deliveryArtifact"));
        assertEquals(11, metadata.required(0).asLong());
        assertEquals(12, metadata.required(1).asLong());

        ArrayNode reversed = mapper.createArrayNode().add(12).add(11);
        NodeContext invalid = new NodeContext(task(artifacts, reversed, size), context.source(), context.sources(),
                context.entity(), context.node(), context.pipeline(), context.processId(), context.fence(),
                context.workerId(), context.previousOutputs());
        assertThrows(IllegalStateException.class, () -> new ManifestQualifyNode(files, mapper).execute(invalid));
    }

    private static SourceData source(long dataId, String name, ObjectMapper mapper) {
        ObjectNode artifact = mapper.createObjectNode().put("artifactSeq", 1).put("originalFileName", name)
                .put("newFileName", name).put("sizeBytes", 1).put("sha256", "0".repeat(64)).put("token", 1);
        artifact.putObject("file_mapping");
        return new SourceData(dataId, 8, name, "", mapper.createObjectNode(), null,
                "PENDING", null, null, 0, null, 1, 1, "0".repeat(64), artifact);
    }

    private static void addPair(ObjectNode pair, SourceData source) {
        pair.put("dataId", source.dataId());
        pair.set("sourceArtifact", source.artifact());
    }

    private static DeliveryTask task(ArrayNode artifacts, ArrayNode metadata, long size) {
        return new DeliveryTask(7, 8, 10, "jun", "group1", LocalDate.parse("2026-11-09"),
                OffsetDateTime.parse("2026-11-09T18:00:00+08:00"), "batch_00001.tar.gz", size, size,
                artifacts, metadata, "qualify", "RUNNING", "worker", null, 1, 0, null, null, null);
    }
}
