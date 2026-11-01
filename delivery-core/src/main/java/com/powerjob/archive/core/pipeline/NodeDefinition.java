package com.powerjob.archive.core.pipeline;

import java.util.Map;

public record NodeDefinition(String id, String scope, String type, Map<String, Object> config) {
}
