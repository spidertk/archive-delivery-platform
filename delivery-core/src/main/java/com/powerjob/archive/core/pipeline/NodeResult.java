package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;

public record NodeResult(String nextNode, String businessStatus, JsonNode manifest,
                         ResultKind kind, Duration nextPoll) {
    public enum ResultKind { SUCCEEDED, WAITING }

    public static NodeResult of(String nextNode, String businessStatus, JsonNode manifest) {
        return new NodeResult(nextNode, businessStatus, manifest, ResultKind.SUCCEEDED, null);
    }

    public static NodeResult waiting(String node, JsonNode manifest, Duration poll) {
        return new NodeResult(node, null, manifest, ResultKind.WAITING, poll);
    }
}
