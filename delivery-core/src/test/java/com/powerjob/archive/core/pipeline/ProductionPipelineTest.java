package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionPipelineTest {
    private final PipelineRepository repository = new PipelineRepository(new ObjectMapper());

    @Test
    void definesOnlyRealExternalNodesAndSafeRootConfiguration() throws Exception {
        PipelineDefinition definition = readV3();
        assertThat(definition.nodes()).extracting(NodeDefinition::id)
                .containsExactly("download", "repair", "qualify", "group", "hzUpload",
                        "sgVerify", "sgDownload", "publish", "receipt");
        assertThat(definition.nodes()).filteredOn(node -> node.scope().equals("OUTPUT"))
                .extracting(NodeDefinition::type)
                .containsExactly("oss.hz-upload", "oss.sg-verify", "ssh.sg-download", "ssh.publish", "delivery.receipt");
        assertThat(definition.nodes()).allMatch(node -> !node.type().startsWith("local."));
        assertThat(definition.intConfig("crrWaitSeconds")).isEqualTo(180);
        assertThat(definition.booleanConfig("autoRollover")).isFalse();
        assertThat(definition.booleanConfig("ftpCleanBeforeDownload")).isFalse();
        definition.validateProduction();
    }

    @Test
    void rejectsUnsafeOrInvalidStrategyAtPublishTime() throws Exception {
        PipelineDefinition definition = readV3();
        var unsafe = new LinkedHashMap<>(definition.config());
        unsafe.put("ftpCleanBeforeDownload", true);
        assertThatThrownBy(() -> withConfig(definition, unsafe).validateProduction())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FTP_CLEAN_FORBIDDEN");
        unsafe.put("ftpCleanBeforeDownload", false);
        unsafe.put("crrWaitSeconds", 7201);
        assertThatThrownBy(() -> withConfig(definition, unsafe).validateProduction())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("crrWaitSeconds");
        unsafe.put("crrWaitSeconds", 0);
        withConfig(definition, unsafe).validateProduction();
        unsafe.put("crrWaitSeconds", 600);
        withConfig(definition, unsafe).validateProduction();
    }

    @Test
    void productionProfileDoesNotEnableLocalAdaptersOrDebugLoop() throws Exception {
        String profile = Files.readString(Path.of("../delivery-worker/src/main/resources/application-production.yml"));
        assertThat(profile).doesNotContain("local-source-dir", "hz-root", "sg-root", "ftp-root");
        assertThat(profile).contains("debug-loop: ${ARCHIVE_DEBUG_LOOP:false}");
    }

    private PipelineDefinition readV3() throws Exception {
        ObjectNode root;
        try (var input = getClass().getResourceAsStream("/pipelines/archive-delivery-v3.json")) {
            root = (ObjectNode) new ObjectMapper().readTree(input.readAllBytes());
        }
        for (JsonNode node : root.required("nodes")) {
            String id = node.required("id").asText();
            if (java.util.Set.of("download", "hzUpload", "sgVerify").contains(id)) {
                ObjectNode config = ((ObjectNode) node).putObject("config");
                config.put("endpoint", "https://oss-cn-hangzhou.aliyuncs.com").put("bucket", id + "-bucket")
                        .put("accessKeyId", "test-id").put("accessKeySecret", "test-secret");
                if (id.equals("download")) config.put("prefix", "source/");
            }
            if (java.util.Set.of("sgDownload", "publish").contains(id)) {
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
        return repository.read(root);
    }

    private static PipelineDefinition withConfig(PipelineDefinition source, java.util.Map<String, Object> config) {
        return new PipelineDefinition(source.pipelineCode(), source.version(), config, source.nodes());
    }
}
