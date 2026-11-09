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

    static Path repairedPath(NodeContext context) {
        // 最终包路径只取当前任务成功 repair locator，任务 artifacts 不再提供交付产物。
        return Path.of(previousOutput(context, "python.repair").required("localPath").asText());
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
