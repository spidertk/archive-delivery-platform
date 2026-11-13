package com.powerjob.archive.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeHandler;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.pipeline.PipelineDefinition;
import com.powerjob.archive.core.pipeline.PipelineEngine;
import com.powerjob.archive.core.pipeline.PipelineRepository;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 用真实处理器注册表和本地替身验证每个 Markdown Pipeline，不执行外部 OSS/FTP IO。 */
@SpringBootTest(properties = "powerjob.worker.enabled=false")
@Testcontainers(disabledWithoutDocker = true)
class GoldenPipelineDefinitionTest {
    private static final Pattern BLOCK = Pattern.compile("```json\\s+(pipeline-definition|golden-case)\\s*\\n(.*?)```", Pattern.DOTALL);
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("archive_delivery").withUsername("archive").withPassword("archive_local")
            .withEnv("PGOPTIONS", "-c archive_delivery.upgrade_authorized=2026-11-07");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=archive_delivery");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET archive_delivery.upgrade_authorized='2026-11-07'");
    }

    @Autowired List<NodeHandler> productionHandlers;
    private final ObjectMapper mapper = new ObjectMapper();
    private final PipelineRepository pipelines = new PipelineRepository(mapper);

    @TestFactory List<DynamicTest> everyMarkdownPipelineHasLocalAndProductionContracts() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        try (var paths = Files.list(registryRoot())) {
            for (Path path : paths.filter(path -> path.getFileName().toString().endsWith(".md")).sorted().toList()) {
                tests.add(DynamicTest.dynamicTest(path.getFileName().toString(), () -> verify(path)));
            }
        }
        assertFalse(tests.isEmpty(), "At least one Markdown pipeline is required");
        return tests;
    }

    private Path registryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("deploy").resolve("pipelines"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate deploy/pipelines below the project root");
        return current.resolve("deploy").resolve("pipelines");
    }

    private void verify(Path path) throws Exception {
        String text = Files.readString(path);
        JsonNode definitionJson = block(text, "pipeline-definition");
        JsonNode golden = block(text, "golden-case");
        String localSnapshot = definitionJson.toString().replaceAll("replace-[a-z0-9-]+", "local-test");
        PipelineDefinition definition = pipelines.read(mapper.readTree(localSnapshot));
        definition.validateProduction();
        assertEquals(definition.pipelineCode(), golden.required("pipelineCode").asText());
        assertEquals(golden.required("targets").size() > 0, true);
        int taskCount = 0;
        for (JsonNode target : golden.required("targets")) taskCount += target.required("targetCount").asInt();
        assertEquals(taskCount, golden.required("expected").required("taskCount").asInt());
        assertEquals("DELIVERED", golden.required("expected").required("deliveryStatus").asText());
        assertTrue(golden.required("expected").required("externalChecks").size() >= 5);

        var registeredTypes = productionHandlers.stream().map(NodeHandler::type).collect(Collectors.toSet());
        List<String> nodeTypes = definition.nodes().stream().map(node -> node.type()).toList();
        for (String type : nodeTypes) {
            assertTrue(registeredTypes.contains(type), "No production NodeHandler registered for " + type);
        }
        assertEquals(nodeTypes, mapper.convertValue(golden.required("expected").required("nodeTypes"),
                mapper.getTypeFactory().constructCollectionType(List.class, String.class)));

        // 本地只替换外部 IO；PipelineEngine、节点类型分派和 DAG 后继判断与生产完全一致。
        List<NodeHandler> localHandlers = nodeTypes.stream().distinct().map(type -> new NodeHandler() {
            @Override public String type() { return type; }
            @Override public NodeResult execute(NodeContext context) {
                return NodeResult.success(context.pipeline().successor(context.node().id()), null, null);
            }
        }).collect(Collectors.toList());
        PipelineEngine engine = new PipelineEngine(localHandlers);
        List<String> executed = new ArrayList<>();
        String current = definition.firstNode();
        long processId = 1;
        while (current != null) {
            var node = definition.node(current);
            executed.add(current);
            NodeResult result = engine.execute(new NodeContext(null, null, null, null, node, definition,
                    processId++, 1, "local-golden", Map.of()));
            definition.requireSuccessor(current, result.nextNode());
            current = result.nextNode();
        }
        assertEquals(definition.nodes().stream().map(node -> node.id()).toList(), executed);
    }

    private JsonNode block(String text, String kind) throws Exception {
        var matcher = BLOCK.matcher(text);
        while (matcher.find()) {
            if (matcher.group(1).equals(kind)) return mapper.readTree(matcher.group(2));
        }
        throw new IllegalArgumentException("Missing " + kind + " JSON block");
    }
}
