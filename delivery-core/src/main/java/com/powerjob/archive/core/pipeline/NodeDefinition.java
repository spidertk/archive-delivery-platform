package com.powerjob.archive.core.pipeline;

import java.util.Map;

/** DAG 节点只声明通用类型、阶段、显示名、显式后继和当前节点配置。 */
public record NodeDefinition(
        String id, String type, String stageCode, String displayName,
        String next, Map<String, Object> config) {
}
