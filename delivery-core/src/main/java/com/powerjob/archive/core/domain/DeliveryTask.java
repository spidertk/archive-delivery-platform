package com.powerjob.archive.core.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 一个 artifact 面向一个交付实体的执行原子；执行组快照约束本地文件域，跨日新 taskId 仍保持同组。 */
public record DeliveryTask(
        long taskId, long deliveryId, long dataId, long deliveryEntityId, String workerGroup, int targetSeq, int artifactSeq,
        LocalDate deliveryDate, OffsetDateTime cutoffAt, String originalFileName,
        String newFileName, long sizeBytes, long tokenCount, String sha256,
        String currentNodeId, String executionStatus, String leaseOwner,
        OffsetDateTime leaseUntil, long fence, int retryCount, OffsetDateTime nextRetryAt,
        String lastErrorCode, Long supersededByTaskId, OffsetDateTime invalidatedAt,
        String invalidationReason, OffsetDateTime completedAt) {
}
