package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PipelineRepositoryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PipelineRepository repository = new PipelineRepository(mapper);

    @Test void explicitNextDefinesTheOnlyExecutionOrder() throws Exception {
        var definition = repository.read(mapper.readTree("{\"pipelineCode\":\"archive-delivery\",\"version\":1," +
                "\"nodeDefinitions\":{},\"config\":{},\"nodes\":[" +
                "{\"id\":\"b\",\"type\":\"manifest.qualify\",\"next\":null,\"config\":{}}," +
                "{\"id\":\"a\",\"type\":\"python.repair\",\"next\":\"b\",\"config\":{}}]}"));
        assertEquals("a", definition.firstNode());
        assertEquals("b", definition.successor("a"));
        assertNull(definition.successor("b"));
    }

    @Test void jumpHostNodesAreRejectedAtPublicationBoundary() throws Exception {
        var definition = repository.read(mapper.readTree("{\"pipelineCode\":\"archive-delivery\",\"version\":1," +
                "\"nodeDefinitions\":{\"ssh.publish\":{\"enabled\":true}}," +
                "\"config\":{\"assignment\":{\"strategySource\":\"SUBMIT_TARGETS\"}}," +
                "\"nodes\":[{\"id\":\"bad\",\"type\":\"ssh.publish\",\"next\":null,\"config\":{}}]}"));
        assertThrows(IllegalArgumentException.class, definition::validateProduction);
    }

    @Test void sharedFtpNameRequiresOssUploadDuringRollover() throws Exception {
        // 跨日会重新领号，重新执行 OSS 上传才能让新 FTP 名称仍对应同名对象。
        String json = """
                {"pipelineCode":"archive-delivery","version":1,
                 "nodeDefinitions":{"oss.upload":{"enabled":true},"manifest.qualify":{"enabled":true}},
                 "config":{"assignment":{"strategySource":"SUBMIT_TARGETS"},"rolloverStartNode":"%s"},
                 "nodes":[{"id":"last","type":"manifest.qualify","next":null,"config":{}},
                          {"id":"upload","type":"oss.upload","next":"last","config":{
                            "endpoint":"https://oss.example","bucket":"bucket","accessKeyId":"access",
                            "accessKeySecret":"secret","useFtpFileName":true,
                            "prefix":"{deliveryDate}/{providerCode}/{entityCode}"}}]}
                """;
        var valid = repository.read(mapper.readTree(json.formatted("upload")));
        valid.validateProduction();
        var invalid = repository.read(mapper.readTree(json.formatted("last")));
        assertThrows(IllegalArgumentException.class, invalid::validateProduction);
    }

    @Test void redundantRuleAndSelectionConfigurationIsRejected() throws Exception {
        String prefix = "{\"pipelineCode\":\"archive-delivery\",\"version\":1,\"nodeDefinitions\":{},";
        String suffix = "\"nodes\":[]}";
        assertThrows(IllegalArgumentException.class, () -> repository.read(mapper.readTree(prefix +
                "\"ruleSets\":{},\"config\":{}," + suffix)));
        assertThrows(IllegalArgumentException.class, () -> repository.read(mapper.readTree(prefix +
                "\"config\":{\"selection\":{\"limit\":3}}," + suffix)));
        assertThrows(IllegalArgumentException.class, () -> repository.read(mapper.readTree(prefix +
                "\"config\":{},\"nodes\":[{\"id\":\"repair\",\"type\":\"python.repair\"," +
                "\"config\":{\"ruleSet\":\"production-archive-v2\"}}]}")));
    }
}
