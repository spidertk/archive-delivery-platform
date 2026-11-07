package com.powerjob.archive.core.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 一次交付的稳定身份与不可变目标快照；聚合状态只由任务事实投影更新。 */
public record DeliverySubmit(
        long deliveryId, String deliveryBatch, long sourceId, long pipelineVersionId,
        String idempotencyKey, LocalDate deliveryDate, OffsetDateTime cutoffAt,
        OffsetDateTime submittedAt, JsonNode targetStrategy, String status,
        OffsetDateTime discoveryCompletedAt, OffsetDateTime completedAt) {
}
