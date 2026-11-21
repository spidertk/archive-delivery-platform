package com.powerjob.archive.core.domain;

import com.fasterxml.jackson.databind.JsonNode;

public record SourceData(
        long dataId, long deliveryId, String originalFileName,
        String requestNamespace, JsonNode originalLocator, JsonNode outputLocator,
        String qualification, String filterRuleCode, String filterReason, long affectedCount,
        long sizeBytes, long tokenCount, String sha256, String uniqueKey, JsonNode artifact) {
    public String sourceUri() {
        return originalLocator.required("sourceUri").asText();
    }

    public String sourceVersion() {
        return originalLocator.required("sourceVersion").asText();
    }

    public String cachePath() {
        return originalLocator.path("cachePath").asText(null);
    }
}
