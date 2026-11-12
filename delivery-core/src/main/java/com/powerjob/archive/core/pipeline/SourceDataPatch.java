package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.databind.JsonNode;

/** 节点返回原始来源对、data_id 列表及合并包汇总值，由 TaskExecutionService 在 fence 校验后统一提交。 */
public record SourceDataPatch(String requestNamespace, JsonNode artifacts, JsonNode metadataIds,
                              Long sizeBytes, Long tokenCount) {
}
