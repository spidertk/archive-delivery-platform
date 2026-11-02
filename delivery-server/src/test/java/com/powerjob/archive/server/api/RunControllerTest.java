package com.powerjob.archive.server.api;

import com.powerjob.archive.server.ArchiveServerApplication;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ArchiveServerApplication.class)
@AutoConfigureMockMvc
@Testcontainers
class RunControllerTest {
    private static final String OSS_URI = "oss://junflow-ysai-source-hz-20260906/_derived/ysai-sftp-20261027/delivery_plan_0005/";
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeAll
    static void publishPipeline(@Autowired MockMvc mvc) throws Exception {
        var definition = (com.fasterxml.jackson.databind.node.ObjectNode)
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(PipelineControllerTest.validDefinition());
        for (var node : definition.required("nodes")) {
            if (node.required("id").asText().equals("download")) {
                var config = (com.fasterxml.jackson.databind.node.ObjectNode) node.required("config");
                config.put("bucket", "junflow-ysai-source-hz-20260906");
                config.put("prefix", "_derived/ysai-sftp-20261027/delivery_plan_0005/");
            }
        }
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/pipeline-versions/3/definition")
                        .contentType("application/json").content(definition.toString()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/pipeline-versions/3/publish")).andExpect(status().isOk());
    }

    @Test
    void submitsIdempotentlyWithoutListingOss() throws Exception {
        String body = "{\"ossUri\":\"" + OSS_URI + "\",\"idempotencyKey\":\"delivery-plan-0005-local-v1\",\"jobDate\":\"20261030\"}";
        String first = mvc.perform(post("/api/v1/runs").contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("DISCOVERING"))
                .andExpect(jsonPath("$.jobDate").value("2026-10-30"))
                .andExpect(jsonPath("$.pipelineVersion").value(3))
                .andReturn().getResponse().getContentAsString();
        Number runId = com.jayway.jsonpath.JsonPath.read(first, "$.runId");
        mvc.perform(post("/api/v1/runs").contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.runId").value(runId.longValue()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_run", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_package", Integer.class)).isZero();
    }

    @Test
    void rejectsConflictsInvalidUrisDatesAndStrategyOverrides() throws Exception {
        mvc.perform(post("/api/v1/runs").contentType("application/json").content(validBody("conflict-key", OSS_URI)))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/runs").contentType("application/json")
                        .content(validBody("conflict-key", OSS_URI + "other/")))
                .andExpect(status().isConflict());
        for (String uri : new String[]{"https://bucket/prefix/", "oss:///prefix/", "oss://bucket/a/../b/"}) {
            mvc.perform(post("/api/v1/runs").contentType("application/json")
                            .content(validBody("bad-" + uri.hashCode(), uri)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/runs").contentType("application/json")
                        .content("{\"ossUri\":\"" + OSS_URI + "\",\"idempotencyKey\":\"bad-date\",\"jobDate\":\"20260230\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/runs").contentType("application/json")
                        .content("{\"ossUri\":\"" + OSS_URI + "\",\"idempotencyKey\":\"policy\",\"jobDate\":\"20261030\",\"crrWaitSeconds\":0}"))
                .andExpect(status().isBadRequest());
    }

    private static String validBody(String key, String uri) {
        return "{\"ossUri\":\"" + uri + "\",\"idempotencyKey\":\"" + key + "\",\"jobDate\":\"20261030\"}";
    }
}
