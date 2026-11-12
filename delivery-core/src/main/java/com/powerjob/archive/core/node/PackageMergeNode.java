package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.config.ArchiveProperties;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeHandler;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.pipeline.PipelineNodeConfig;
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
    private final PipelineNodeConfig configs;
    private final ObjectMapper mapper;

    public PackageMergeNode(ArchiveProperties properties, PipelineNodeConfig configs, ObjectMapper mapper) {
        this.properties = properties;
        this.configs = configs;
        this.mapper = mapper;
    }

    @Override public String type() { return "package.merge"; }

    /** 每个源包只解包一次，输出请求目录供 repair 直接消费，不生成中间压缩包。 */
    @Override public NodeResult execute(NodeContext context) throws Exception {
        // 只有成功下载节点可以提供已核对 size/SHA 的源包路径。
        JsonNode download = NodeSupport.previousOutput(context, "oss.download");
        // 下载输出顺序与 delivery_mapping.source_seq 一致。
        JsonNode sources = download.required("sources");
        // 来源数量漂移或无效包上限必须阻断合并。
        if (sources.size() != context.sources().size() || context.pipeline().maxPackageBytes() <= 0) {
            throw new IllegalStateException("Downloaded source count or maxPackageBytes is invalid");
        }
        // 每个任务使用独立 merge/requests 工作目录。
        Path target = Path.of(properties.getWorkRoot(), "delivery-" + context.task().deliveryId(),
                "task-" + context.task().taskId(), "merge", "requests");
        // manifest 持久保存成员键、originalPath 和来源序号血缘。
        Path manifest = target.resolveSibling("merge-result.json");
        // 当前 PipelineVersion 决定脚本实现，Worker 执行前核对本地文件且不使用 Shell。
        List<String> command = new ArrayList<>(List.of(properties.getPythonCommand(),
                NodeSupport.script(configs, context.node()), "--target", target.toString(), "--manifest", manifest.toString()));
        // 按 mapping 顺序传入源包，禁止切分任何原始文件。
        sources.forEach(source -> {
            command.add("--source");
            command.add(source.required("localPath").asText());
        });
        // 开始日志记录任务血缘、来源数和工作目录。
        log.info("Package merge started deliveryId={} taskId={} processId={} sourceCount={} workspace={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), sources.size(), target);
        // 非零退出或结构化结果非法时不会进入 repair。
        JsonNode merged = mapper.readTree(ProcessSupport.runJson(command, null, Duration.ofMinutes(10), "MERGE_RESULT "));
        // 输入 locator 保存来源路径和包上限等脱敏事实。
        ObjectNode input = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("scriptPath", configs.script(context.node()))
                .put("sourceCount", sources.size()).put("maxPackageBytes", context.pipeline().maxPackageBytes())
                .set("sources", download.get("sources"));
        // 输出 locator 传递工作目录、成员清单和实际请求数。
        ObjectNode output = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("workspacePath", merged.required("workspacePath").asText())
                .put("manifestPath", manifest.toAbsolutePath().toString())
                .put("sourceBytes", merged.required("sourceBytes").asLong())
                .put("requestCount", merged.required("requestCount").asInt()).put("sourceCount", sources.size())
                .put("verifiedAt", Instant.now().toString());
        // 成功日志分别记录来源包数和包内请求目录数。
        log.info("Package merge succeeded deliveryId={} taskId={} processId={} sourceCount={} requestCount={} workspace={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), sources.size(),
                merged.required("requestCount").asInt(), target);
        // repair 是否执行只由当前 Pipeline 后继定义决定。
        return NodeResult.success(context.pipeline().successor(context.node().id()), input, output);
    }
}
