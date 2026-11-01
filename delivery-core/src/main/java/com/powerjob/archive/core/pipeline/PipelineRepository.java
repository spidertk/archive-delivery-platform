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

    public PipelineDefinition read(JsonNode snapshot) {
        List<NodeDefinition> nodes = new ArrayList<>();
        snapshot.required("nodes").forEach(node -> nodes.add(new NodeDefinition(
                node.required("id").asText(),
                node.required("scope").asText(),
                node.required("type").asText(),
                objectMapper.convertValue(node.path("config"), new TypeReference<Map<String, Object>>() {}))));
        Map<String, Object> config = snapshot.has("config")
                ? objectMapper.convertValue(snapshot.required("config"), new TypeReference<Map<String, Object>>() {})
                : Map.of();
        return new PipelineDefinition(snapshot.required("pipelineCode").asText(),
                snapshot.required("version").asInt(), Map.copyOf(config), List.copyOf(nodes));
    }
}
