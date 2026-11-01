package com.powerjob.archive.core.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GetObjectRequest;
import com.powerjob.archive.core.config.MvpProperties;
import com.powerjob.archive.core.domain.DiscoveredObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "OSS_SOURCE_ACCESS_KEY_ID", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OSS_SOURCE_ACCESS_KEY_SECRET", matches = ".+")
class OssDiscoveryIntegrationTest {
    @Test
    void listsTenRealObjectsAndReadsOneByteFromTheFirst() throws Exception {
        MvpProperties properties = new MvpProperties();
        properties.setOssSourceEndpoint(envOrDefault("OSS_SOURCE_ENDPOINT", "https://oss-cn-hangzhou.aliyuncs.com"));
        properties.setOssSourceAccessKeyId(System.getenv("OSS_SOURCE_ACCESS_KEY_ID"));
        properties.setOssSourceAccessKeySecret(System.getenv("OSS_SOURCE_ACCESS_KEY_SECRET"));
        properties.setOssSourceBucket("junflow-ysai-source-hz-20260906");
        properties.setOssSourcePrefix("_derived/ysai-sftp-20261027/delivery_plan_0005/");
        OssClientFactory clients = new OssClientFactory(properties);
        List<DiscoveredObject> objects = new OssDiscoveryService(clients, properties).discover();
        assertThat(objects).hasSize(10);
        assertThat(objects.stream().filter(object -> object.productionLineId() == 1L)).hasSize(5);
        assertThat(objects.stream().filter(object -> object.productionLineId() == 2L)).hasSize(5);
        assertThat(objects).allMatch(object -> object.objectKey().endsWith(".tar.gz"));

        OSS client = clients.create();
        try {
            DiscoveredObject first = objects.get(0);
            assertThat(client.getObjectMetadata(first.bucketName(), first.objectKey()).getContentLength())
                    .isEqualTo(first.size());
            GetObjectRequest range = new GetObjectRequest(first.bucketName(), first.objectKey());
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
