package com.powerjob.archive.core.domain;

import com.fasterxml.jackson.databind.JsonNode;

/** 源数据只描述定位、质量和产物事实，不携带 Worker 租约或交付协议密钥。 */
public record SourceData(
        long dataId, long deliveryId, String originalRequestId, String newRequestId,
        String requestNamespace, JsonNode originalLocator, JsonNode outputLocator,
        String qualification, String filterRuleCode, String filterReason, long affectedCount,
        String ruleVersion, long sizeBytes, long tokenCount, String sha256, JsonNode artifacts) {
    public String sourceUri() {
        return originalLocator.required("sourceUri").asText();
    }

    public String sourceVersion() {
        return originalLocator.required("sourceVersion").asText();
    }
}
