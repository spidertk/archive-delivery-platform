package com.powerjob.archive.core.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.model.GetObjectRequest;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.ListObjectsV2Result;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ARCHIVE_PREFLIGHT", matches = "true")
class ProductionPreflightTest {
    @Test
    void checksOssReadWriteAndReplicationAccessOnce() throws Exception {
        OSS source = client("OSS_SOURCE");
        try {
            String bucket = required("OSS_SOURCE_BUCKET");
            String prefix = required("OSS_SOURCE_PREFIX");
            ListObjectsV2Result listed = source.listObjectsV2(new ListObjectsV2Request(bucket)
                    .withPrefix(prefix).withMaxKeys(1));
            assertThat(listed.getObjectSummaries()).isNotEmpty();
            OSSObjectSummary first = listed.getObjectSummaries().get(0);
            source.getObjectMetadata(bucket, first.getKey());
            GetObjectRequest range = new GetObjectRequest(bucket, first.getKey());
            range.setRange(0, 0);
            try (InputStream input = source.getObject(range).getObjectContent()) {
                assertThat(input.read()).isNotEqualTo(-1);
            }
            System.out.println("OK source OSS list/head/range: " + bucket);
        } finally {
            source.shutdown();
        }

        OSS hz = client("OSS_HZ");
        String hzBucket = required("OSS_HZ_BUCKET");
        String hzKey = required("HZ_OSS_TEST_PREFIX") + UUID.randomUUID() + ".txt";
        byte[] body = "archive-mvp-preflight".getBytes(StandardCharsets.UTF_8);
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(body.length);
            hz.putObject(hzBucket, hzKey, new ByteArrayInputStream(body), metadata);
            assertThat(hz.getObjectMetadata(hzBucket, hzKey).getContentLength()).isEqualTo(body.length);
            System.out.println("OK HZ OSS put/head: " + hzBucket + "/.archive-mvp-preflight");
        } finally {
            try {
                hz.deleteObject(hzBucket, hzKey);
                System.out.println("OK HZ OSS delete probe");
            } finally {
                hz.shutdown();
            }
        }

        OSS sg = client("OSS_SG");
        try {
            String sgBucket = required("OSS_SG_BUCKET");
            sg.getObjectMetadata(sgBucket, required("OSS_SG_HEAD_KEY"));
            System.out.println("OK SG OSS head: " + sgBucket);
        } finally {
            sg.shutdown();
        }
    }

    private static OSS client(String prefix) {
        ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
        configuration.setConnectionTimeout(10_000);
        configuration.setSocketTimeout(10_000);
        configuration.setMaxErrorRetry(0);
        return new OSSClientBuilder().build(required(prefix + "_ENDPOINT"),
                required(prefix + "_ACCESS_KEY_ID"), required(prefix + "_ACCESS_KEY_SECRET"), configuration);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank() || value.startsWith("replace-")) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
