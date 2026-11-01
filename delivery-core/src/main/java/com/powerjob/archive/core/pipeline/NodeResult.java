package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.databind.JsonNode;

public record NodeResult(String nextNode, String businessStatus, JsonNode manifest) {
    public static NodeResult of(String nextNode, String businessStatus, JsonNode manifest) {
        return new NodeResult(nextNode, businessStatus, manifest);
    }
}
