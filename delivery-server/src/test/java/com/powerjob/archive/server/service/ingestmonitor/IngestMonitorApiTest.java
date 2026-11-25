package com.powerjob.archive.server.service.ingestmonitor;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "archive.ingest-monitor.enabled=true", "archive.ingest-monitor.dashboard-url=http://127.0.0.1:8090/monitor", "archive.ingest-monitor.initial-delay-ms=3600000",
        "archive.ingest-monitor.webhook-url=https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=test-private-value"})
@Testcontainers(disabledWithoutDocker = true)
class IngestMonitorApiTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("archive_delivery").withUsername("archive").withPassword("archive_local");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired TestRestTemplate http;
    @Autowired IngestMonitorService service;
    @MockBean OssSourceScanner scanner;
    @MockBean WeComNotifier notifier;

    @Test void scheduledServiceStartsAndPublicApiContainsOnlyObservationFacts() throws Exception {
        // 完整 Spring 上下文验证调度装配；实际外部边界替换，公开响应不得回传受控机器人 key。
        var headers=new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var created=http.postForEntity("/api/v1/ingest-sources",new org.springframework.http.HttpEntity<>("{\"ossUri\":\"oss://api-bucket/auto/deep/\",\"ossType\":\"ALIBABA_CLOUD\"}",headers),String.class);
        assertEquals(201,created.getStatusCodeValue());
        assertEquals("auto",new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.getBody()).path("channelCode").asText());
        service.poll();
        var response = http.getForEntity("/api/v1/ingest-sources/monitor", String.class);
        assertEquals(200, response.getStatusCodeValue());
        assertTrue(response.getBody().contains("Jayden-incoming-2.0"));
        assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getBody()).path("channels").get(0).path("observedAt").isTextual());
        assertFalse(response.getBody().contains("test-private-value"));
        assertFalse(response.getBody().contains("webhook"));
        assertEquals(200, http.getForEntity("/api/v1/ingest-sources/1/trend?hours=24", String.class).getStatusCodeValue());
        assertEquals(200,http.getForEntity("/api/v1/ingest-sources/1/changes?hours=24",String.class).getStatusCodeValue());
        assertEquals(400, http.getForEntity("/api/v1/ingest-sources/1/trend?hours=721", String.class).getStatusCodeValue());
    }
}
