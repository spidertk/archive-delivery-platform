package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.databind.JsonNode;

/** 节点返回的数据事实补丁由 TaskExecutionService 在 fence 校验后统一提交。 */
public record SourceDataPatch(
        String originalRequestId, String newRequestId, String requestNamespace,
        JsonNode outputLocator, String qualification, String ruleVersion,
        Long sizeBytes, Long tokenCount, String sha256, JsonNode artifacts) {
}
