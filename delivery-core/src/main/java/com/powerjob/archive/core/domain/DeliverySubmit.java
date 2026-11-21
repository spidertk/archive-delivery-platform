package com.powerjob.archive.core.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record DeliverySubmit(
        long deliveryId, String deliveryBatch, long sourceId, long pipelineVersionId,
        LocalDate deliveryDate, OffsetDateTime cutoffAt, OffsetDateTime submittedAt,
        JsonNode targetStrategy, String status, OffsetDateTime discoveryCompletedAt,
        OffsetDateTime completedAt, String submissionType, Long batchDeliveryId, JsonNode batchExtension) {
    public boolean batchCreation() {
        return "BATCH_CREATE".equals(submissionType);
    }
}
