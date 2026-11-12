package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
public class PipelineRepository {
    private final ObjectMapper objectMapper;

    public PipelineRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 节点实现元数据与 DAG 同版快照，拒绝已删除的独立规则和来源条数配置。 */
    public PipelineDefinition read(JsonNode snapshot) {
        if (snapshot.has("ruleSets") || snapshot.path("config").has("selection")) {
            throw new IllegalArgumentException("Pipeline ruleSets and config.selection are no longer supported");
        }
        List<NodeDefinition> nodes = new ArrayList<>();
        snapshot.required("nodes").forEach(node -> {
            if (node.path("type").asText().equals("python.repair") && node.path("config").has("ruleSet")) {
                throw new IllegalArgumentException("python.repair config.ruleSet is no longer supported");
            }
            nodes.add(new NodeDefinition(
                    node.required("id").asText(), node.required("type").asText(),
                    node.path("stageCode").asText(node.required("id").asText()),
                    node.path("displayName").asText(node.required("id").asText()),
                    node.hasNonNull("next") ? node.get("next").asText() : null,
                    objectMapper.convertValue(node.path("config"), new TypeReference<Map<String, Object>>() {})));
        });
        Map<String, Object> config = objectMapper.convertValue(snapshot.required("config"), new TypeReference<Map<String, Object>>() {});
        Map<String, Object> nodeDefinitions = objectMapper.convertValue(snapshot.required("nodeDefinitions"), new TypeReference<Map<String, Object>>() {});
        return new PipelineDefinition(snapshot.required("pipelineCode").asText(), snapshot.required("version").asInt(),
                Map.copyOf(config), Map.copyOf(nodeDefinitions), List.copyOf(nodes));
    }
}
