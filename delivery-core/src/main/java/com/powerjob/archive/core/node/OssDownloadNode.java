package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.config.ArchiveProperties;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeHandler;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.pipeline.OssNodeConfig;
import com.powerjob.archive.core.pipeline.PipelineNodeConfig;
import com.powerjob.archive.core.service.OssTransferService;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;

@Component
public class OssDownloadNode implements NodeHandler {
    private final OssTransferService transfers;
    private final PipelineNodeConfig configs;
    private final ArchiveProperties properties;
    private final ObjectMapper mapper;

    public OssDownloadNode(OssTransferService transfers, PipelineNodeConfig configs, ArchiveProperties properties, ObjectMapper mapper) {
        this.transfers = transfers;
        this.configs = configs;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override public String type() { return "oss.download"; }

    /** 杭州源 OSS 通过当前节点 AK/SK 直接下载，不经过 SSH、CRR 或中间 Bucket。 */
    @Override public NodeResult execute(NodeContext context) throws Exception {
        OssNodeConfig config = configs.oss(context.node(), true);
        URI source = URI.create(context.source().sourceUri());
        if (!"oss".equals(source.getScheme()) || !config.bucket().equals(source.getHost()) || source.getQuery() != null) {
            throw new IllegalArgumentException("Source locator differs from current OSS node");
        }
        String key = source.getPath().replaceFirst("^/", "");
        Path target = Path.of(properties.getWorkRoot(), "delivery-" + context.task().deliveryId(),
                "data-" + context.source().dataId(), "source", context.task().originalFileName());
        String sha = transfers.download(config, source.getHost(), key, target, context.source().sizeBytes(), context.source().sha256());
        ObjectNode input = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id()).put("uri", context.source().sourceUri());
        ObjectNode output = input.deepCopy().put("localPath", target.toAbsolutePath().toString())
                .put("sizeBytes", java.nio.file.Files.size(target)).put("sha256", sha).put("verifiedAt", Instant.now().toString());
        return NodeResult.success(context.pipeline().successor(context.node().id()), input, output);
    }
}
