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
        return previous == null ? Path.of(workRoot, "delivery-" + context.task().deliveryId(), "data-" + context.source().dataId(),
                "repair", context.task().newFileName()) : Path.of(previous.required("localPath").asText());
    }

    static Path ossVerifiedPath(NodeContext context, String workRoot) {
        JsonNode previous = context.previousOutputs().entrySet().stream()
                .filter(entry -> context.pipeline().nodes().stream().anyMatch(node -> node.id().equals(entry.getKey()) && node.type().equals("oss.upload")))
                .map(java.util.Map.Entry::getValue).filter(output -> output.has("localPath")).findFirst().orElse(null);
        return previous == null ? Path.of(workRoot, "delivery-" + context.task().deliveryId(), "data-" + context.source().dataId(),
                "oss-verified", context.task().deliveryDate().toString(), "entity-" + context.task().deliveryEntityId(), context.task().newFileName())
                : Path.of(previous.required("localPath").asText());
    }
}
