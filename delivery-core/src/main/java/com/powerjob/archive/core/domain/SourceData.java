package com.powerjob.archive.core.domain;

import com.fasterxml.jackson.databind.JsonNode;

/** 源数据只描述原始定位、质量和单个原始产物，不携带 Worker 租约或交付协议密钥。 */
public record SourceData(
        long dataId, long deliveryId, String originalFileName,
        String requestNamespace, JsonNode originalLocator, JsonNode outputLocator,
        String qualification, String filterRuleCode, String filterReason, long affectedCount,
        String ruleVersion, long sizeBytes, long tokenCount, String sha256, JsonNode artifact) {
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
