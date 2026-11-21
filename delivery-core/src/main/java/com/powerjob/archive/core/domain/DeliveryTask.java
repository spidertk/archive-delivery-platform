package com.powerjob.archive.core.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record DeliveryTask(
        long taskId, long deliveryId, long deliveryEntityId, String entityCode, String workerGroup,
        LocalDate deliveryDate, OffsetDateTime cutoffAt, String newFileName,
        long sizeBytes, long tokenCount, JsonNode artifacts,
        String currentNodeId, String executionStatus, String leaseOwner,
        OffsetDateTime leaseUntil, long fence, int retryCount, OffsetDateTime nextRetryAt,
        String lastErrorCode, OffsetDateTime completedAt) {

    public String originalFileName() {
        if (artifacts != null && artifacts.isArray() && !artifacts.isEmpty()) {
            String name = artifacts.required(0).path("sourceArtifact").path("originalFileName").asText(null);
            if (name != null && !name.isBlank()) return name;
        }
        return newFileName;
    }
}
