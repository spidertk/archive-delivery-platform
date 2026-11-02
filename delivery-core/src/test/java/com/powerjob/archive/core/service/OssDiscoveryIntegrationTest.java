package com.powerjob.archive.core.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GetObjectRequest;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.powerjob.archive.core.pipeline.OssNodeConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "OSS_SOURCE_ACCESS_KEY_ID", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OSS_SOURCE_ACCESS_KEY_SECRET", matches = ".+")
class OssDiscoveryIntegrationTest {
    @Test
    void listsTenRealObjectsAndReadsOneByteFromTheFirst() throws Exception {
        OssNodeConfig config = new OssNodeConfig(envOrDefault("OSS_SOURCE_ENDPOINT", "https://oss-cn-hangzhou.aliyuncs.com"),
                "junflow-ysai-source-hz-20260906", "_derived/ysai-sftp-20261027/delivery_plan_0005/",
                System.getenv("OSS_SOURCE_ACCESS_KEY_ID"), System.getenv("OSS_SOURCE_ACCESS_KEY_SECRET"));
        OSS client = new OssClientFactory().create(config);
        try {
            var result = client.listObjectsV2(new ListObjectsV2Request(config.bucket()).withPrefix(config.prefix()));
            var objects = result.getObjectSummaries().stream().filter(object -> object.getKey().endsWith(".tar.gz")).toList();
            assertThat(objects).hasSizeGreaterThanOrEqualTo(10);
            var first = objects.get(0);
            assertThat(client.getObjectMetadata(first.getBucketName(), first.getKey()).getContentLength())
                    .isEqualTo(first.getSize());
            GetObjectRequest range = new GetObjectRequest(first.getBucketName(), first.getKey());
            range.setRange(0, 0);
            try (InputStream input = client.getObject(range).getObjectContent()) {
                assertThat(input.read()).isNotEqualTo(-1);
            }
        } finally {
            client.shutdown();
        }
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
