package com.powerjob.archive.core.service;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.powerjob.archive.core.pipeline.OssNodeConfig;
import org.springframework.stereotype.Component;

@Component
public class OssClientFactory {
    /** 客户端只接收当前节点显式凭据，禁止从全局环境变量隐式回退。 */
    public OSS create(OssNodeConfig config) {
        ClientBuilderConfiguration settings = new ClientBuilderConfiguration();
        settings.setConnectionTimeout(10_000);
        settings.setSocketTimeout(60_000);
        settings.setMaxErrorRetry(2);
        settings.setConnectionRequestTimeout(10_000);
        return new OSSClientBuilder().build(config.endpoint(), config.accessKeyId(), config.accessKeySecret(), settings);
    }
}
