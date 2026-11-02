package com.powerjob.archive.core.pipeline;

import java.util.List;
import java.util.Map;

public record PipelineDefinition(String pipelineCode, int version, Map<String, Object> config,
                                 List<NodeDefinition> nodes) {
    public int intConfig(String key) {
        Object value = config.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Pipeline config " + key + " must be a number");
        }
        return number.intValue();
    }

    public boolean booleanConfig(String key) {
        Object value = config.get(key);
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException("Pipeline config " + key + " must be a boolean");
        }
        return bool;
    }

    public void validateProduction() {
        int crrWaitSeconds = intConfig("crrWaitSeconds");
        if (crrWaitSeconds < 0 || crrWaitSeconds > 7200) {
            throw new IllegalArgumentException("crrWaitSeconds must be between 0 and 7200");
        }
        booleanConfig("autoRollover");
        if (booleanConfig("ftpCleanBeforeDownload")) {
            throw new IllegalArgumentException("FTP_CLEAN_FORBIDDEN");
        }
        PipelineNodeConfig configs = new PipelineNodeConfig(new com.fasterxml.jackson.databind.ObjectMapper());
        configs.oss(node("download"), true);
        configs.oss(node("hzUpload"), false);
        configs.oss(node("sgVerify"), false);
        configs.ftp(node("sgDownload"));
        configs.ftp(node("publish"));
    }

    public NodeDefinition node(String id) {
        return nodes.stream().filter(node -> node.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Pipeline node: " + id));
    }
}
