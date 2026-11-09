package com.powerjob.archive.core.pipeline;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pipeline 快照统一承载节点定义、规则和 DAG；执行内核不读取独立节点或规则配置表。 */
public record PipelineDefinition(String pipelineCode, int version, Map<String, Object> config,
                                 Map<String, Object> nodeDefinitions, Map<String, Object> ruleSets,
                                 List<NodeDefinition> nodes) {
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

    /** 发布时同时校验 DAG、集中节点元数据、规则引用与外部连接安全边界。 */
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
            if (node.type().equals("python.repair")) {
                Object ruleSet = node.config().get("ruleSet");
                if (ruleSet == null || !ruleSets.containsKey(ruleSet.toString())) {
                    throw new IllegalArgumentException("Repair ruleSet must be defined in this PipelineVersion");
                }
            }
            if (node.type().equals("package.merge")) maxPackageBytes();
        }
        Set<String> visited = new HashSet<>();
        String current = firstNode();
        while (current != null) {
            if (!visited.add(current)) throw new IllegalArgumentException("Pipeline DAG contains a cycle");
            current = node(current).next();
        }
        if (visited.size() != nodes.size()) throw new IllegalArgumentException("Pipeline DAG contains unreachable nodes");
        Object assignment = config.get("assignment");
        if (!(assignment instanceof Map<?, ?> map) || !"SUBMIT_TARGETS".equals(map.get("strategySource"))) {
            throw new IllegalArgumentException("Pipeline config.assignment.strategySource must be SUBMIT_TARGETS");
        }
        minPackageBytes();
        PipelineNodeConfig configs = new PipelineNodeConfig(new com.fasterxml.jackson.databind.ObjectMapper());
        for (NodeDefinition node : nodes) {
            if (node.type().equals("oss.download")) configs.oss(node, true);
            if (node.type().equals("oss.upload")) configs.oss(node, false);
            if (node.type().equals("ftp.download-verify")) configs.ftp(node);
        }
    }

    public int selectionLimit() {
        Object selection = config.get("selection");
        if (!(selection instanceof Map<?, ?> map) || !(map.get("limit") instanceof Number limit) || limit.intValue() <= 0) {
            throw new IllegalArgumentException("Pipeline config.selection.limit must be positive");
        }
        return limit.intValue();
    }

    public long maxPackageBytes() {
        Object assignment = config.get("assignment");
        Object configured = assignment instanceof Map<?, ?> map ? map.get("maxPackageBytes") : null;
        long value = configured instanceof Number number ? number.longValue() : -1;
        if (value <= 0) throw new IllegalArgumentException("config.assignment.maxPackageBytes must be positive");
        return value;
    }

    public long minPackageBytes() {
        Object assignment = config.get("assignment");
        Object configured = assignment instanceof Map<?, ?> map ? map.get("minPackageBytes") : null;
        long value = configured instanceof Number number ? number.longValue() : maxPackageBytes() * 4 / 5;
        if (value <= 0 || value > maxPackageBytes()) {
            throw new IllegalArgumentException("config.assignment.minPackageBytes must be positive and no greater than maxPackageBytes");
        }
        return value;
    }
}
