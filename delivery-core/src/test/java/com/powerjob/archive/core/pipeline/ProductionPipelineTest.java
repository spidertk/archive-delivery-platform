package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionPipelineTest {
    private final PipelineRepository repository = new PipelineRepository(new ObjectMapper());

    @Test
    void definesOnlyRealExternalNodesAndSafeRootConfiguration() throws Exception {
        PipelineDefinition definition = readV2();
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
        PipelineDefinition definition = readV2();
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

    private PipelineDefinition readV2() throws Exception {
        String json;
        try (var input = getClass().getResourceAsStream("/pipelines/archive-delivery-v2.json")) {
            json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        return repository.read(new ObjectMapper().readTree(json));
    }

    private static PipelineDefinition withConfig(PipelineDefinition source, java.util.Map<String, Object> config) {
        return new PipelineDefinition(source.pipelineCode(), source.version(), config, source.nodes());
    }
}
