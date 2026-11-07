package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;

/** 节点只返回后继、通用 locator 和数据补丁，不直接更新任务或提交聚合状态。 */
public record NodeResult(String nextNode, JsonNode inputLocator, JsonNode outputLocator,
                         SourceDataPatch sourcePatch, ResultKind kind, Duration nextPoll) {
    public enum ResultKind { SUCCEEDED, WAITING }

    public static NodeResult success(String nextNode, JsonNode input, JsonNode output) {
        return new NodeResult(nextNode, input, output, null, ResultKind.SUCCEEDED, null);
    }

    public static NodeResult success(String nextNode, JsonNode input, JsonNode output, SourceDataPatch patch) {
        return new NodeResult(nextNode, input, output, patch, ResultKind.SUCCEEDED, null);
    }

    public static NodeResult waiting(String node, JsonNode input, JsonNode output, Duration poll) {
        return new NodeResult(node, input, output, null, ResultKind.WAITING, poll);
    }
}
