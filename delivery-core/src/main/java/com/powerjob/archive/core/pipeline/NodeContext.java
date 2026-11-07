package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.powerjob.archive.core.domain.DeliveryEntity;
import com.powerjob.archive.core.domain.DeliveryTask;
import com.powerjob.archive.core.domain.SourceData;
import java.util.Map;

/** 节点上下文只暴露当前任务、交付实体、当前节点凭据和此前成功 locator，避免读取整份 Secret 集合。 */
public record NodeContext(
        DeliveryTask task, SourceData source, DeliveryEntity entity,
        NodeDefinition node, PipelineDefinition pipeline, long processId, long fence,
        String workerId, Map<String, JsonNode> previousOutputs) {
    public JsonNode outputOf(String nodeId) {
        JsonNode output = previousOutputs.get(nodeId);
        if (output == null) throw new IllegalStateException("Missing succeeded output for node " + nodeId);
        return output;
    }
}
