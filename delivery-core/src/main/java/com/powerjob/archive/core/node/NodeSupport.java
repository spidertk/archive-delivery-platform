package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.powerjob.archive.core.pipeline.NodeContext;
import java.nio.file.Path;

final class NodeSupport {
    private NodeSupport() {}

    static JsonNode previousOutput(NodeContext context, String nodeType) {
        return context.pipeline().nodes().stream().filter(node -> node.type().equals(nodeType))
                .map(node -> context.previousOutputs().get(node.id())).filter(java.util.Objects::nonNull).findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing succeeded " + nodeType + " output"));
    }

    static Path repairedPath(NodeContext context, String workRoot) {
        JsonNode previous = context.previousOutputs().entrySet().stream()
                .filter(entry -> entry.getValue().has("localPath") && entry.getValue().has("sha256"))
                .filter(entry -> context.pipeline().nodes().stream().anyMatch(node -> node.id().equals(entry.getKey()) && node.type().equals("python.repair")))
                .map(java.util.Map.Entry::getValue).findFirst().orElse(null);
        if (previous != null) return Path.of(previous.required("localPath").asText());
        Path deliveryRoot = Path.of(workRoot, "delivery-" + context.task().deliveryId());
        if (java.nio.file.Files.isDirectory(deliveryRoot)) {
            try (var candidates = java.nio.file.Files.find(deliveryRoot, 4, (path, attributes) ->
                    attributes.isRegularFile() && path.getFileName().toString().equals(context.task().newFileName())
                            && path.getParent().getFileName().toString().equals("repair")
                            && matchesTaskArtifact(context, path, attributes.size()))) {
                var repaired = candidates.findFirst();
                if (repaired.isPresent()) return repaired.get();
            } catch (java.io.IOException error) {
                throw new IllegalStateException("Cannot locate repaired artifact within the delivery work root", error);
            }
        }
        return deliveryRoot.resolve("task-" + context.task().taskId()).resolve("repair").resolve(context.task().newFileName());
    }

    private static boolean matchesTaskArtifact(NodeContext context, Path candidate, long size) {
        if (size != context.task().sizeBytes() || context.task().sha256() == null) return false;
        try {
            return context.task().sha256().equals(new com.powerjob.archive.core.service.FileTransferService().sha256(candidate));
        } catch (java.io.IOException error) {
            return false;
        }
    }

    static Path ossVerifiedPath(NodeContext context, String workRoot) {
        JsonNode previous = context.previousOutputs().entrySet().stream()
                .filter(entry -> context.pipeline().nodes().stream().anyMatch(node -> node.id().equals(entry.getKey()) && node.type().equals("oss.upload")))
                .map(java.util.Map.Entry::getValue).filter(output -> output.has("localPath")).findFirst().orElse(null);
        return previous == null ? Path.of(workRoot, "delivery-" + context.task().deliveryId(), "task-" + context.task().taskId(),
                "oss-verified", context.task().deliveryDate().toString(), "entity-" + context.task().deliveryEntityId(), context.task().newFileName())
                : Path.of(previous.required("localPath").asText());
    }
}
