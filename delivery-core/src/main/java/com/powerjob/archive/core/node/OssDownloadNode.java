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
        // 源 OSS Secret 只从当前 Pipeline 下载节点获取。
        OssNodeConfig config = configs.oss(context.node(), true);
        // 输入 locator 保存本次任务的来源数量和有序 dataId。
        ObjectNode input = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("sourceCount", context.sources().size());
        ArrayNode inputIds = input.putArray("sourceDataIds");
        // 输出 locator 逐项记录本地文件、size/SHA 和缓存命中事实。
        ObjectNode output = mapper.createObjectNode().put("schemaVersion", 1).put("nodeId", context.node().id())
                .put("sourceCount", context.sources().size()).put("verifiedAt", Instant.now().toString());
        ArrayNode downloaded = output.putArray("sources");
        // 节点级开始日志用于关联同一 processId 下的全部来源。
        log.info("Source download node started deliveryId={} taskId={} processId={} entityCode={} sourceCount={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), context.task().entityCode(),
                context.sources().size());
        // mapping 顺序决定源包下载和后续 merge 的来源序号。
        for (SourceData source : context.sources()) {
            // 原始 locator 必须是当前 OSS 节点桶内的普通 oss URI。
            URI location = URI.create(source.sourceUri());
            if (!"oss".equals(location.getScheme()) || !config.bucket().equals(location.getHost()) || location.getQuery() != null) {
                throw new IllegalArgumentException("Source locator differs from current OSS node: dataId=" + source.dataId());
            }
            // 对象键只取 URI 路径，不接收签名参数或任意命令。
            String key = location.getPath().replaceFirst("^/", "");
            // 每个 dataId 使用独立目录并保留安全原始文件名。
            Path target = Path.of(properties.getWorkRoot(), "delivery-" + context.task().deliveryId(),
                    "task-" + context.task().taskId(), "sources", "data-" + source.dataId(), source.originalFileName());
            // 来源级开始日志记录预期 size/SHA 和实际目标路径。
            log.info("Source download started deliveryId={} taskId={} processId={} dataId={} uri={} expectedSize={} expectedSha256={} target={}",
                    context.task().deliveryId(), context.task().taskId(), context.processId(), source.dataId(), source.sourceUri(),
                    source.sizeBytes(), source.sha256(), target);
            // 默认执行真实 OSS GET，只有完整缓存核验通过才改变为缓存命中。
            boolean cacheHit = false;
            String sha;
            if (source.cachePath() != null) {
                // 同机缓存可能来自其他 delivery 或厂商，但必须使用同一 URI+版本事实。
                Path cached = Path.of(source.cachePath());
                if (Files.isRegularFile(cached) && Files.size(cached) == source.sizeBytes()
                        && files.sha256(cached).equals(source.sha256())) {
                    // 命中缓存仍复制到任务目录并再次校验目标 SHA。
                    files.copyVerified(cached, target, source.sha256());
                    sha = files.sha256(target);
                    cacheHit = true;
                } else {
                    // 缓存缺失或校验不一致时回退真实源 OSS，不使用坏缓存。
                    log.info("Source cache fallback deliveryId={} taskId={} processId={} dataId={} cachePath={}",
                            context.task().deliveryId(), context.task().taskId(), context.processId(), source.dataId(), cached);
                    sha = transfers.download(config, location.getHost(), key, target, source.sizeBytes(), source.sha256());
                }
            } else {
                // 新地址没有缓存定位，必须执行真实 OSS GET。
                sha = transfers.download(config, location.getHost(), key, target, source.sizeBytes(), source.sha256());
            }
            // 输入 ID 顺序与输出 sources 顺序保持一致。
            inputIds.add(source.dataId());
            // 单个来源 locator 不包含 OSS Secret。
            ObjectNode fact = mapper.createObjectNode().put("dataId", source.dataId()).put("uri", source.sourceUri())
                    .put("localPath", target.toAbsolutePath().toString()).put("sizeBytes", Files.size(target))
                    .put("sha256", sha).put("cacheHit", cacheHit);
            downloaded.add(fact);
            // 来源级成功日志明确 cacheHit 和最终文件事实。
            log.info("Source download succeeded deliveryId={} taskId={} processId={} dataId={} cacheHit={} sizeBytes={} sha256={} localPath={}",
                    context.task().deliveryId(), context.task().taskId(), context.processId(), source.dataId(), cacheHit,
                    Files.size(target), sha, target);
        }
        // 节点级成功日志证明全部 mapping 来源均已完成本地核验。
        log.info("Source download node succeeded deliveryId={} taskId={} processId={} sourceCount={} cacheHits={}",
                context.task().deliveryId(), context.task().taskId(), context.processId(), downloaded.size(),
                java.util.stream.StreamSupport.stream(downloaded.spliterator(), false)
                        .filter(fact -> fact.required("cacheHit").asBoolean()).count());
        // 后继 merge 节点由 Pipeline DAG 决定。
        return NodeResult.success(context.pipeline().successor(context.node().id()), input, output);
    }
}
