package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PipelineRepositoryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PipelineRepository repository = new PipelineRepository(mapper);

    @Test void explicitNextDefinesTheOnlyExecutionOrder() throws Exception {
        var definition = repository.read(mapper.readTree("{\"pipelineCode\":\"archive-delivery\",\"version\":1," +
                "\"nodeDefinitions\":{},\"ruleSets\":{},\"config\":{\"selection\":{\"limit\":3}},\"nodes\":[" +
                "{\"id\":\"b\",\"type\":\"manifest.qualify\",\"next\":null,\"config\":{}}," +
                "{\"id\":\"a\",\"type\":\"python.repair\",\"next\":\"b\",\"config\":{}}]}"));
        assertEquals("a", definition.firstNode());
        assertEquals("b", definition.successor("a"));
        assertNull(definition.successor("b"));
        assertEquals(3, definition.selectionLimit());
    }

    @Test void jumpHostNodesAreRejectedAtPublicationBoundary() throws Exception {
        var definition = repository.read(mapper.readTree("{\"pipelineCode\":\"archive-delivery\",\"version\":1," +
                "\"nodeDefinitions\":{\"ssh.publish\":{\"enabled\":true}},\"ruleSets\":{}," +
                "\"config\":{\"selection\":{\"limit\":1},\"assignment\":{\"strategySource\":\"SUBMIT_TARGETS\"}}," +
                "\"nodes\":[{\"id\":\"bad\",\"type\":\"ssh.publish\",\"next\":null,\"config\":{}}]}"));
        assertThrows(IllegalArgumentException.class, definition::validateProduction);
    }
}
