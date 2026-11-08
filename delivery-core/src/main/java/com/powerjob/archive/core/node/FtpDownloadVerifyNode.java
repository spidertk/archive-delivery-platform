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
        FtpNodeConfig config = configs.ftp(context.node());
        var endpoint = context.entity().endpointLocator();
        String host = endpoint.required("host").asText();
        int port = endpoint.path("port").asInt(21);
        String rootPath = endpoint.path("rootPath").asText("/");
        String deliveryBatch = deliveries.find(context.task().deliveryId()).orElseThrow().deliveryBatch();
        String template = String.valueOf(context.node().config().getOrDefault("remotePathTemplate",
                "{deliveryId}/{deliveryDate}/entity-{deliveryEntityId}"));
        String relative = paths.ftpRelativePath(template, context.task(), context.entity().vendorCode(), deliveryBatch);
        Path ossCopy = NodeSupport.ossVerifiedPath(context, properties.getWorkRoot());
        Path download = Path.of(properties.getFtpVerificationRoot(), Long.toString(context.task().deliveryId()),
                context.task().deliveryDate().toString(), "entity-" + context.task().deliveryEntityId(), context.task().newFileName());
        ObjectNode request = mapper.createObjectNode();
        request.put("host", host).put("port", port).put("username", context.entity().accountName())
                .put("password", config.passwordFor(context.entity().accountName())).put("rootPath", rootPath)
                .put("remotePath", relative).put("sourcePath", ossCopy.toAbsolutePath().toString())
                .put("downloadPath", download.toAbsolutePath().toString())
                .put("expectedSize", context.task().sizeBytes()).put("expectedSha256", context.task().sha256());
        log.info("FTP verification started deliveryId={} taskId={} processId={} host={} port={} rootPath={} remotePath={} expectedSize={} expectedSha256={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), host, port, rootPath, relative,
                context.task().sizeBytes(), context.task().sha256());
        JsonNode result = mapper.readTree(ProcessSupport.runJson(List.of(properties.getPythonCommand(), properties.getFtpVerifyScript()),
                request.toString(), Duration.ofMinutes(5), "FTP_RESULT "));
        ObjectNode input = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("localPath", ossCopy.toAbsolutePath().toString()).put("deliveryEntityId", context.task().deliveryEntityId());
        ObjectNode output = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("uri", result.required("uri").asText()).put("downloadPath", result.required("downloadPath").asText())
                .put("sizeBytes", result.required("sizeBytes").asLong()).put("sha256", result.required("sha256").asText())
                .put("verifiedAt", Instant.now().toString());
        log.info("FTP verification succeeded deliveryId={} taskId={} processId={} uri={} downloadPath={} sizeBytes={} sha256={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), result.required("uri").asText(),
                result.required("downloadPath").asText(), result.required("sizeBytes").asLong(), result.required("sha256").asText());
        return NodeResult.success(context.pipeline().successor(context.node().id()), input, output);
    }
}
