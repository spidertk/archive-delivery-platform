package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.config.ArchiveProperties;
import com.powerjob.archive.core.domain.SourceData;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeHandler;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.pipeline.OssNodeConfig;
import com.powerjob.archive.core.pipeline.PipelineNodeConfig;
import com.powerjob.archive.core.service.FileTransferService;
import com.powerjob.archive.core.service.OssTransferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

@Component
public class OssDownloadNode implements NodeHandler {
    private static final Logger log = LoggerFactory.getLogger(OssDownloadNode.class);
    private final OssTransferService transfers;
    private final FileTransferService files;
    private final PipelineNodeConfig configs;
    private final ArchiveProperties properties;
    private final ObjectMapper mapper;

    public OssDownloadNode(OssTransferService transfers, FileTransferService files, PipelineNodeConfig configs,
                           ArchiveProperties properties, ObjectMapper mapper) {
        this.transfers = transfers;
        this.files = files;
        this.configs = configs;
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override public String type() { return "oss.download"; }

    /** 同机共享缓存命中时只复制并核验源包，不重复 OSS GET；其他执行组缓存不存在时安全回退源 OSS。 */
    @Override public NodeResult execute(NodeContext context) throws Exception {
        OssNodeConfig config = configs.oss(context.node(), true);
        ObjectNode input = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("sourceCount", context.sources().size());
        ArrayNode inputIds = input.putArray("sourceDataIds");
        ObjectNode output = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("sourceCount", context.sources().size()).put("verifiedAt", Instant.now().toString());
        ArrayNode downloaded = output.putArray("sources");
        for (SourceData source : context.sources()) {
            URI location = URI.create(source.sourceUri());
            if (!"oss".equals(location.getScheme()) || !config.bucket().equals(location.getHost()) || location.getQuery() != null) {
                throw new IllegalArgumentException("Source locator differs from current OSS node: dataId=" + source.dataId());
            }
            String key = location.getPath().replaceFirst("^/", "");
            Path target = Path.of(properties.getWorkRoot(), "delivery-" + context.task().deliveryId(),
                    "task-" + context.task().taskId(), "sources", "data-" + source.dataId(), source.originalFileName());
            log.info("Source download started deliveryId={} taskId={} processId={} dataId={} uri={} expectedSize={} expectedSha256={} target={}",
                    context.task().deliveryId(), context.task().taskId(), context.processId(), source.dataId(), source.sourceUri(),
                    source.sizeBytes(), source.sha256(), target);
            boolean cacheHit = false;
            String sha;
            if (source.cachePath() != null) {
                Path cached = Path.of(source.cachePath());
                if (Files.isRegularFile(cached) && Files.size(cached) == source.sizeBytes()
                        && files.sha256(cached).equals(source.sha256())) {
                    files.copyVerified(cached, target, source.sha256());
                    sha = files.sha256(target);
                    cacheHit = true;
                } else {
                    sha = transfers.download(config, location.getHost(), key, target, source.sizeBytes(), source.sha256());
                }
            } else {
                sha = transfers.download(config, location.getHost(), key, target, source.sizeBytes(), source.sha256());
            }
            inputIds.add(source.dataId());
            ObjectNode fact = mapper.createObjectNode().put("dataId", source.dataId()).put("uri", source.sourceUri())
                    .put("localPath", target.toAbsolutePath().toString()).put("sizeBytes", Files.size(target))
                    .put("sha256", sha).put("cacheHit", cacheHit);
            downloaded.add(fact);
            log.info("Source download succeeded deliveryId={} taskId={} processId={} dataId={} cacheHit={} sizeBytes={} sha256={} localPath={}",
                    context.task().deliveryId(), context.task().taskId(), context.processId(), source.dataId(), cacheHit,
                    Files.size(target), sha, target);
        }
        return NodeResult.success(context.pipeline().successor(context.node().id()), input, output);
    }
}
