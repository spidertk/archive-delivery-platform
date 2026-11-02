package com.powerjob.archive.core.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.powerjob.archive.core.pipeline.OssNodeConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "archive.discovery-mode", havingValue = "oss")
public class OssClientFactory {
    public OSS create(OssNodeConfig config) {
        ClientBuilderConfiguration settings = new ClientBuilderConfiguration();
        settings.setConnectionTimeout(10_000);
        settings.setSocketTimeout(60_000);
        settings.setMaxErrorRetry(2);
        return new OSSClientBuilder().build(config.endpoint(), config.accessKeyId(),
                config.accessKeySecret(), settings);
    }
}
