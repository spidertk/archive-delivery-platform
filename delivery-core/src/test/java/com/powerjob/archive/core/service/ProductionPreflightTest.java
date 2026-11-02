package com.powerjob.archive.core.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GetObjectRequest;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.ListObjectsV2Result;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.pipeline.OssNodeConfig;
import com.powerjob.archive.core.pipeline.PipelineDefinition;
import com.powerjob.archive.core.pipeline.PipelineNodeConfig;
import com.powerjob.archive.core.pipeline.PipelineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ARCHIVE_PREFLIGHT", matches = "true")
class ProductionPreflightTest {
    @Test
    void checksBoundPipelineOssAccessOnce() throws Exception {
        PipelineDefinition definition = boundDefinition();
        PipelineNodeConfig configs = new PipelineNodeConfig(new ObjectMapper());
        OssNodeConfig sourceConfig = configs.oss(definition.node("download"), true);
        OSS source = new OssClientFactory().create(sourceConfig);
        try {
            ListObjectsV2Result listed = source.listObjectsV2(new ListObjectsV2Request(sourceConfig.bucket())
                    .withPrefix(sourceConfig.prefix()).withMaxKeys(1));
            assertThat(listed.getObjectSummaries()).isNotEmpty();
            OSSObjectSummary first = listed.getObjectSummaries().get(0);
            source.getObjectMetadata(sourceConfig.bucket(), first.getKey());
            GetObjectRequest range = new GetObjectRequest(sourceConfig.bucket(), first.getKey());
            range.setRange(0, 0);
            try (InputStream input = source.getObject(range).getObjectContent()) {
                assertThat(input.read()).isNotEqualTo(-1);
            }
            System.out.println("OK source OSS list/head/range: " + sourceConfig.bucket());
        } finally {
            source.shutdown();
        }

        OssNodeConfig hzConfig = configs.oss(definition.node("hzUpload"), false);
        OSS hz = new OssClientFactory().create(hzConfig);
        String hzKey = ".archive-mvp-preflight/" + UUID.randomUUID() + ".txt";
        byte[] body = "archive-mvp-preflight".getBytes(StandardCharsets.UTF_8);
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(body.length);
            hz.putObject(hzConfig.bucket(), hzKey, new ByteArrayInputStream(body), metadata);
            assertThat(hz.getObjectMetadata(hzConfig.bucket(), hzKey).getContentLength()).isEqualTo(body.length);
            System.out.println("OK HZ OSS put/head: " + hzConfig.bucket());
        } finally {
            try {
                hz.deleteObject(hzConfig.bucket(), hzKey);
                System.out.println("OK HZ OSS delete probe");
            } finally {
                hz.shutdown();
            }
        }

        OssNodeConfig sgConfig = configs.oss(definition.node("sgVerify"), false);
        OSS sg = new OssClientFactory().create(sgConfig);
        try {
            ListObjectsV2Result listed = sg.listObjectsV2(new ListObjectsV2Request(sgConfig.bucket()).withMaxKeys(1));
            assertThat(listed.getObjectSummaries()).isNotEmpty();
            sg.getObjectMetadata(sgConfig.bucket(), listed.getObjectSummaries().get(0).getKey());
            System.out.println("OK SG OSS list/head: " + sgConfig.bucket());
        } finally {
            sg.shutdown();
        }
    }

    private static PipelineDefinition boundDefinition() throws Exception {
        String url = required("ARCHIVE_DB_URL");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url,
                required("ARCHIVE_DB_USER"), required("ARCHIVE_DB_PASSWORD"));
        String json = new JdbcTemplate(dataSource).queryForObject("SELECT pv.definition::text FROM source_pipeline_binding b " +
                "JOIN pipeline_version pv ON pv.id=b.pipeline_version_id " +
                "WHERE b.effective_to IS NULL AND pv.status='PUBLISHED' ORDER BY b.effective_from DESC LIMIT 1", String.class);
        return new PipelineRepository(new ObjectMapper()).read(new ObjectMapper().readTree(json));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
