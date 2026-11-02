package com.powerjob.archive.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.server.ArchiveServerApplication;
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

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ArchiveServerApplication.class)
@AutoConfigureMockMvc
@Testcontainers
class PipelineControllerTest {
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

    @Test
    void draftsMasksPublishesAndFreezesVersion() throws Exception {
        String draft = mvc.perform(post("/api/v1/pipelines/archive-delivery/versions")
                        .contentType("application/json").content("{\"baseVersionId\":2}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        long draftId = ((Number) com.jayway.jsonpath.JsonPath.read(draft, "$.id")).longValue();
        mvc.perform(put("/api/v1/pipeline-versions/" + draftId + "/definition")
                        .contentType("application/json").content(validDefinition()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.nodes[?(@.id=='hzUpload')].config.accessKeySecret")
                        .value(contains("********")));
        String masked = mvc.perform(get("/api/v1/pipeline-versions/" + draftId))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("test-secret"))))
                .andReturn().getResponse().getContentAsString();
        JsonNode maskedDefinition = new ObjectMapper().readTree(masked).required("definition");
        mvc.perform(put("/api/v1/pipeline-versions/" + draftId + "/definition")
                        .contentType("application/json").content(maskedDefinition.toString()))
                .andExpect(status().isOk());
        JsonNode stored = new ObjectMapper().readTree(jdbc.queryForObject(
                "SELECT definition::text FROM pipeline_version WHERE id=?", String.class, draftId));
        assertThat(secret(stored, "hzUpload")).isEqualTo("test-secret");

        mvc.perform(post("/api/v1/pipeline-versions/" + draftId + "/publish"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
        mvc.perform(put("/api/v1/pipeline-versions/" + draftId + "/definition")
                        .contentType("application/json").content(validDefinition()))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject(
                "SELECT pipeline_version_id FROM source_pipeline_binding WHERE effective_to IS NULL", Long.class))
                .isEqualTo(draftId);
    }

    private static String secret(JsonNode definition, String nodeId) {
        for (JsonNode node : definition.required("nodes")) {
            if (node.required("id").asText().equals(nodeId)) {
                return node.required("config").required("accessKeySecret").asText();
            }
        }
        throw new AssertionError("Missing node " + nodeId);
    }

    private static String validDefinition() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(
                Path.of("../delivery-core/src/main/resources/pipelines/archive-delivery-v3.json").toFile());
        for (JsonNode node : root.required("nodes")) {
            String id = node.required("id").asText();
            if (Set.of("download", "hzUpload", "sgVerify").contains(id)) {
                ObjectNode config = ((ObjectNode) node).putObject("config");
                config.put("endpoint", "https://oss-cn-hangzhou.aliyuncs.com").put("bucket", id + "-bucket")
                        .put("accessKeyId", "test-id").put("accessKeySecret", "test-secret");
                if (id.equals("download")) config.put("prefix", "source/");
            }
            if (Set.of("sgDownload", "publish").contains(id)) {
                ObjectNode lines = ((ObjectNode) node).putObject("config").putObject("lines");
                for (long line = 1; line <= 3; line++) {
                    lines.putObject(Long.toString(line)).put("host", "sg-" + line).put("user", "root")
                            .put("key", "/run/secrets/line-" + line + "-id_ed25519")
                            .put("remoteRoot", "/srv/maas-sftp/maasData/archive")
                            .put("downloadScript", "/opt/archive-delivery/bin/sg_download.sh")
                            .put("publishScript", "/opt/archive-delivery/bin/publish_package.sh");
                }
            }
        }
        return mapper.writeValueAsString(root);
    }
}
