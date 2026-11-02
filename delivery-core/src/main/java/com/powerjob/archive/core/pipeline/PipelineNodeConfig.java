package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class PipelineNodeConfig {
    private final ObjectMapper mapper;

    public PipelineNodeConfig(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public OssNodeConfig oss(NodeDefinition node, boolean prefixRequired) {
        OssNodeConfig config = mapper.convertValue(node.config(), OssNodeConfig.class);
        config.validate(prefixRequired);
        return config;
    }

    public FtpNodeConfig ftp(NodeDefinition node) {
        FtpNodeConfig config = mapper.convertValue(node.config(), FtpNodeConfig.class);
        config.validate();
        return config;
    }
}
