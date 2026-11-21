package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.databind.JsonNode;

public record SourceDataPatch(String requestNamespace, JsonNode artifacts, Long sizeBytes, Long tokenCount) {
}
