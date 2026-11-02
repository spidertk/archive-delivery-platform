package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineRepositoryTest {
    @Test
    void loadsVersionedNodeSequence() throws Exception {
        String json;
        try (var input = getClass().getResourceAsStream("/pipelines/archive-delivery-v3.json")) {
            json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        PipelineDefinition definition = new PipelineRepository(new ObjectMapper()).read(new ObjectMapper().readTree(json));
        assertThat(definition.nodes()).extracting(NodeDefinition::id)
                .containsExactly("download", "repair", "qualify", "group", "hzUpload",
                        "sgVerify", "sgDownload", "publish", "receipt");
        assertThat(definition.nodes()).extracting(NodeDefinition::scope)
                .containsExactly("SOURCE", "SOURCE", "SOURCE", "SOURCE", "OUTPUT",
                        "OUTPUT", "OUTPUT", "OUTPUT", "OUTPUT");
    }
}
