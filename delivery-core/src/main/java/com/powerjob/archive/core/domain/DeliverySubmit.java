package com.powerjob.archive.core.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 一次交付的稳定身份、累计目标和追加请求历史；deliveryBatch 是全局幂等键和请求编号命名空间。 */
public record DeliverySubmit(
        long deliveryId, String deliveryBatch, long sourceId, long pipelineVersionId,
        LocalDate deliveryDate, OffsetDateTime cutoffAt, OffsetDateTime submittedAt,
        JsonNode targetStrategy, String status, OffsetDateTime discoveryCompletedAt,
        OffsetDateTime completedAt) {
}
