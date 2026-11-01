package com.powerjob.archive.core.pipeline;

import java.util.List;

public record PipelineDefinition(String pipelineCode, int version, List<NodeDefinition> nodes) {
    public NodeDefinition node(String id) {
        return nodes.stream().filter(node -> node.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Pipeline node: " + id));
    }
}
