package com.powerjob.archive.core.domain;

import java.time.OffsetDateTime;

public record DeliveryBatchItem(
        long batchItemId, long batchDeliveryId, long dataId, String deliveryBatch,
        String originalFileName, String newFileName, int sourceSeq, String status,
        long deliveryEntityId, String entityCode, String workerGroup, Long deliveryId,
        OffsetDateTime createdAt, OffsetDateTime startedAt, OffsetDateTime completedAt) {
}
