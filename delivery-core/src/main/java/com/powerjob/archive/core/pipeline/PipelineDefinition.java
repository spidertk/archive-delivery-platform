package com.powerjob.archive.core.pipeline;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record PipelineDefinition(String pipelineCode, int version, Map<String, Object> config,
                                 Map<String, Object> nodeDefinitions, List<NodeDefinition> nodes) {
    public NodeDefinition node(String id) {
        return nodes.stream().filter(node -> node.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Pipeline node: " + id));
    }

    public String firstNode() {
        Set<String> successors = new HashSet<>();
        nodes.stream().map(NodeDefinition::next).filter(java.util.Objects::nonNull).forEach(successors::add);
        List<String> starts = nodes.stream().map(NodeDefinition::id).filter(id -> !successors.contains(id)).toList();
        if (starts.size() != 1) throw new IllegalArgumentException("Pipeline must have exactly one start node");
        return starts.get(0);
    }

    public String successor(String id) { return node(id).next(); }

    public void requireSuccessor(String currentNodeId, String nextNodeId) {
        if (!java.util.Objects.equals(successor(currentNodeId), nextNodeId)) {
            throw new IllegalArgumentException("Illegal Pipeline successor after " + currentNodeId);
        }
    }

    public String rolloverStartNode() {
        Object value = config.get("rolloverStartNode");
        return value == null ? firstNode() : node(value.toString()).id();
    }

    public void validateProduction() {
        if (nodes.isEmpty() || nodes.stream().map(NodeDefinition::id).distinct().count() != nodes.size()) {
            throw new IllegalArgumentException("Pipeline nodes must be non-empty and have unique ids");
        }
        for (NodeDefinition node : nodes) {
            if (!(nodeDefinitions.get(node.type()) instanceof Map<?, ?> definition)
                    || !Boolean.TRUE.equals(definition.get("enabled"))) {
                throw new IllegalArgumentException("Pipeline node type is not enabled in nodeDefinitions: " + node.type());
            }
            if (node.type().startsWith("ssh.") || node.type().contains("crr") || node.type().equals("delivery.group")) {
                throw new IllegalArgumentException("Unsupported legacy Pipeline node: " + node.type());
            }
        }
        Set<String> visited = new HashSet<>();
        String current = firstNode();
        while (current != null) {
            if (!visited.add(current)) throw new IllegalArgumentException("Pipeline DAG contains a cycle");
            current = node(current).next();
        }
        if (visited.size() != nodes.size()) throw new IllegalArgumentException("Pipeline DAG contains unreachable nodes");
        Object assignment = config.get("assignment");
        if (!(assignment instanceof Map<?, ?> map)) throw new IllegalArgumentException("Pipeline config.assignment is required");
        String expectedStrategy = "archive-batch".equals(pipelineCode) ? "BATCH_PLANNING" : "SUBMIT_TARGETS";
        if (!expectedStrategy.equals(map.get("strategySource"))) {
            throw new IllegalArgumentException("Pipeline config.assignment.strategySource must be " + expectedStrategy);
        }
        if ("archive-batch".equals(pipelineCode)) {
            com.powerjob.archive.core.service.BatchPlanningService.limits(this);
            if (!(map.get("entityWorkers") instanceof List<?> workers) || workers.isEmpty()) {
                throw new IllegalArgumentException("archive-batch entityWorkers must be non-empty");
            }
        }
        PipelineNodeConfig configs = new PipelineNodeConfig(new com.fasterxml.jackson.databind.ObjectMapper());
        for (NodeDefinition node : nodes) {
            if (node.type().equals("oss.download")) configs.oss(node, true);
            if (node.type().equals("oss.upload")) configs.oss(node, false);
            if (node.type().equals("ftp.download-verify")) configs.ftp(node);
            if (node.type().equals("package.merge") || node.type().equals("python.repair")
                    || node.type().equals("ftp.download-verify")) configs.script(node);
        }
    }
}
