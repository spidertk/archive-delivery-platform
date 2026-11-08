package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.config.ArchiveProperties;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeHandler;
import com.powerjob.archive.core.pipeline.NodeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class PackageMergeNode implements NodeHandler {
    private static final Logger log = LoggerFactory.getLogger(PackageMergeNode.class);
    private final ArchiveProperties properties;
    private final ObjectMapper mapper;

    public PackageMergeNode(ArchiveProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override public String type() { return "package.merge"; }

    /** 每个源包只解包一次，输出请求目录供 repair 直接消费，不生成中间压缩包。 */
    @Override public NodeResult execute(NodeContext context) throws Exception {
        JsonNode download = NodeSupport.previousOutput(context, "oss.download");
        JsonNode sources = download.required("sources");
        if (sources.size() != context.sources().size() || context.pipeline().maxPackageBytes() <= 0) {
            throw new IllegalStateException("Downloaded source count or maxPackageBytes is invalid");
        }
        Path target = Path.of(properties.getWorkRoot(), "delivery-" + context.task().deliveryId(),
                "task-" + context.task().taskId(), "merge", "requests");
        Path manifest = target.resolveSibling("merge-result.json");
        List<String> command = new ArrayList<>(List.of(properties.getPythonCommand(), properties.getPythonMergeScript(),
                "--target", target.toString(), "--manifest", manifest.toString()));
        sources.forEach(source -> {
            command.add("--source");
            command.add(source.required("localPath").asText());
        });
        log.info("Package merge started deliveryId={} taskId={} processId={} sourceCount={} workspace={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), sources.size(), target);
        JsonNode merged = mapper.readTree(ProcessSupport.runJson(command, null, Duration.ofMinutes(10), "MERGE_RESULT "));
        ObjectNode input = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("sourceCount", sources.size()).put("maxPackageBytes", context.pipeline().maxPackageBytes())
                .set("sources", download.get("sources"));
        ObjectNode output = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("workspacePath", merged.required("workspacePath").asText())
                .put("manifestPath", manifest.toAbsolutePath().toString())
                .put("sourceBytes", merged.required("sourceBytes").asLong())
                .put("requestCount", merged.required("requestCount").asInt()).put("sourceCount", sources.size())
                .put("verifiedAt", Instant.now().toString());
        log.info("Package merge succeeded deliveryId={} taskId={} processId={} sourceCount={} requestCount={} workspace={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), sources.size(),
                merged.required("requestCount").asInt(), target);
        return NodeResult.success(context.pipeline().successor(context.node().id()), input, output);
    }
}
