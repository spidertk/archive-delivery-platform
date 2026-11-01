package com.powerjob.archive.core.service;

import com.aliyun.oss.OSSClientBuilder;
import com.powerjob.archive.core.config.OssDeliveryProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ARCHIVE_TEST_REAL_OSS", matches = "true")
class OssTransferIntegrationTest {
    @Test
    void realHzObjectIsPutOnlyOnceAndDeletedAfterProbe() throws Exception {
        OssDeliveryProperties properties = new OssDeliveryProperties();
        properties.getHz().setEndpoint(required("OSS_HZ_ENDPOINT"));
        properties.getHz().setBucket(required("OSS_HZ_BUCKET"));
        properties.getHz().setAccessKeyId(required("OSS_HZ_ACCESS_KEY_ID"));
        properties.getHz().setAccessKeySecret(required("OSS_HZ_ACCESS_KEY_SECRET"));
        Path artifact = Files.createTempFile("archive-hz-it", ".txt");
        Files.writeString(artifact, "archive-mvp-integration");
        String sha = new FileTransferService().sha256(artifact);
        String prefix = System.getenv().getOrDefault("HZ_OSS_TEST_PREFIX", ".archive-mvp-it/");
        String key = prefix + UUID.randomUUID() + "/batch_00001.tar.gz";
        OssTransferService service = new OssTransferService(properties);
        try {
            assertThat(service.uploadHz(artifact, key, sha, 1, 1).uploaded()).isTrue();
            assertThat(service.uploadHz(artifact, key, sha, 1, 1).uploaded()).isFalse();
        } finally {
            var client = new OSSClientBuilder().build(properties.getHz().getEndpoint(),
                    properties.getHz().getAccessKeyId(), properties.getHz().getAccessKeySecret());
            try {
                client.deleteObject(properties.getHz().getBucket(), key);
            } finally {
                client.shutdown();
                Files.deleteIfExists(artifact);
            }
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
