package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.config.ArchiveProperties;
import com.powerjob.archive.core.pipeline.FtpNodeConfig;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeHandler;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.pipeline.PipelineNodeConfig;
import com.powerjob.archive.core.repository.DeliveryRepository;
import com.powerjob.archive.core.service.RemotePathService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class FtpDownloadVerifyNode implements NodeHandler {
    private static final Logger log = LoggerFactory.getLogger(FtpDownloadVerifyNode.class);
    private final PipelineNodeConfig configs;
    private final RemotePathService paths;
    private final ArchiveProperties properties;
    private final DeliveryRepository deliveries;
    private final ObjectMapper mapper;

    public FtpDownloadVerifyNode(PipelineNodeConfig configs, RemotePathService paths, ArchiveProperties properties,
                                 DeliveryRepository deliveries, ObjectMapper mapper) {
        this.configs = configs;
        this.paths = paths;
        this.properties = properties;
        this.deliveries = deliveries;
        this.mapper = mapper;
    }

    @Override public String type() { return "ftp.download-verify"; }

    /** 使用直属实体账户上传并重新下载；密码仅经 stdin 传递，日志只记录 host、路径和核验事实。 */
    @Override public NodeResult execute(NodeContext context) throws Exception {
        // FTP Secret 只从当前 Pipeline 节点读取。
        FtpNodeConfig config = configs.ftp(context.node());
        // OSS 成功 locator 提供已 GET 回验的最终 SHA，任务 artifacts 不再保存交付产物。
        JsonNode ossOutput = NodeSupport.previousOutput(context, "oss.upload");
        String expectedSha = ossOutput.required("sha256").asText();
        if (ossOutput.required("sizeBytes").asLong() != context.task().sizeBytes()) {
            throw new IllegalStateException("OSS verified size differs from the delivery task");
        }
        // 实体 locator 只包含非 Secret 的连接定位。
        var endpoint = context.entity().endpointLocator();
        // 主机地址用于直属 FTP 连接，不经过跳板机。
        String host = endpoint.required("host").asText();
        // 未显式配置时使用受控 FTP 默认端口。
        int port = endpoint.path("port").asInt(21);
        // rootPath 限定账户可访问的远端根目录。
        String rootPath = endpoint.path("rootPath").asText("/");
        // 提交批次用于远端路径隔离和幂等。
        String deliveryBatch = deliveries.find(context.task().deliveryId()).orElseThrow().deliveryBatch();
        // 远端路径模板完全由当前 Pipeline 节点定义。
        String template = String.valueOf(context.node().config().getOrDefault("remotePathTemplate",
                "{deliveryId}/{deliveryDate}/entity-{deliveryEntityId}"));
        // 模板只替换受控业务字段，不接受任意 Shell。
        String relative = paths.ftpRelativePath(template, context.task(), context.entity().vendorCode(),
                context.entity().accountName(), deliveryBatch);
        // 上传源使用 OSS 已 GET 回验的副本。
        Path ossCopy = NodeSupport.ossVerifiedPath(context, properties.getWorkRoot());
        // 下载文件按 delivery、日期和实体隔离，并保留任务级唯一包名。
        Path download = Path.of(properties.getFtpVerificationRoot(), Long.toString(context.task().deliveryId()),
                context.task().deliveryDate().toString(), "entity-" + context.task().deliveryEntityId(), context.task().newFileName());
        // 密码只写入 stdin JSON，不进入命令行、日志或 locator。
        ObjectNode request = mapper.createObjectNode();
        request.put("host", host).put("port", port).put("username", context.entity().accountName())
                .put("password", config.passwordFor(context.entity().accountName())).put("rootPath", rootPath)
                .put("remotePath", relative).put("sourcePath", ossCopy.toAbsolutePath().toString())
                .put("downloadPath", download.toAbsolutePath().toString())
                .put("expectedSize", context.task().sizeBytes()).put("expectedSha256", expectedSha);
        // 开始日志只记录连接位置、业务血缘和预期文件事实。
        log.info("FTP verification started deliveryId={} taskId={} processId={} entityCode={} host={} port={} rootPath={} remotePath={} expectedSize={} expectedSha256={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), context.task().entityCode(),
                host, port, rootPath, relative, context.task().sizeBytes(), expectedSha);
        // 上传、重新下载、size/SHA 和 .partial 清理由受控脚本一次完成。
        JsonNode result = mapper.readTree(ProcessSupport.runJson(List.of(properties.getPythonCommand(),
                NodeSupport.script(configs, context.node())), request.toString(), Duration.ofMinutes(5), "FTP_RESULT "));
        // 输入 locator 不包含密码或完整请求 JSON。
        ObjectNode input = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("scriptPath", configs.script(context.node()))
                .put("localPath", ossCopy.toAbsolutePath().toString()).put("deliveryEntityId", context.task().deliveryEntityId());
        // 输出 locator 保留远端 URI 和本地下载文件的复核事实。
        ObjectNode output = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("uri", result.required("uri").asText()).put("downloadPath", result.required("downloadPath").asText())
                .put("sizeBytes", result.required("sizeBytes").asLong()).put("sha256", result.required("sha256").asText())
                .put("verifiedAt", Instant.now().toString());
        // 成功日志证明最终下载文件已核对 size/SHA。
        log.info("FTP verification succeeded deliveryId={} taskId={} processId={} uri={} downloadPath={} sizeBytes={} sha256={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), result.required("uri").asText(),
                result.required("downloadPath").asText(), result.required("sizeBytes").asLong(), result.required("sha256").asText());
        // 终态提交仍由统一执行服务和 Pipeline 后继决定。
        return NodeResult.success(context.pipeline().successor(context.node().id()), input, output);
    }
}
