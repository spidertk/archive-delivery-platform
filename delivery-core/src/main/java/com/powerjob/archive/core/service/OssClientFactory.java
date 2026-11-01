package com.powerjob.archive.core.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.powerjob.archive.core.config.MvpProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "archive.discovery-mode", havingValue = "oss")
public class OssClientFactory {
    private final MvpProperties properties;

    public OssClientFactory(MvpProperties properties) {
        this.properties = properties;
    }

    public OSS create() {
        if (properties.getOssSourceAccessKeyId() == null || properties.getOssSourceAccessKeyId().isBlank()
                || properties.getOssSourceAccessKeySecret() == null || properties.getOssSourceAccessKeySecret().isBlank()) {
            throw new IllegalStateException("OSS_SOURCE_ACCESS_KEY_ID and OSS_SOURCE_ACCESS_KEY_SECRET are required");
        }
        return new OSSClientBuilder().build(properties.getOssSourceEndpoint(),
                properties.getOssSourceAccessKeyId(), properties.getOssSourceAccessKeySecret());
    }
}
