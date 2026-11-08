package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.databind.JsonNode;

/** 节点返回的任务产物事实由 TaskExecutionService 在 fence 校验后统一提交。 */
public record SourceDataPatch(String ruleVersion, String requestNamespace, JsonNode artifacts, JsonNode metadataIds) {
}
