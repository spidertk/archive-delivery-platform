package com.powerjob.archive.core.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/** 节点尝试保留完整 delivery/task 血缘与通用 locator；processId 不与 Pipeline 字符串 nodeId 混用。 */
public record DeliveryNode(
        long processId, long deliveryId, long taskId, String originalFileName,
        String newFileName, String nodeId, String nodeType, String stageCode,
        int attempt, long fence, String status, String workerInstanceId,
        JsonNode inputLocator, JsonNode outputLocator, String errorCode,
        String errorDetail, OffsetDateTime startedAt, OffsetDateTime finishedAt) {
}
