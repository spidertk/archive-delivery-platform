package com.powerjob.archive.server.service.ingestmonitor;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.ListObjectsV2Result;
import com.aliyun.oss.model.OSSObjectSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.pipeline.OssNodeConfig;
import com.powerjob.archive.core.pipeline.PipelineNodeConfig;
import com.powerjob.archive.core.pipeline.PipelineRepository;
import com.powerjob.archive.core.service.OssClientFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Component
public class OssSourceScanner {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PipelineRepository pipelines;
    private final PipelineNodeConfig configs;
    private final OssClientFactory clients;
    private final MonitorSettings settings;

    public OssSourceScanner(JdbcTemplate jdbc, ObjectMapper mapper, PipelineRepository pipelines,
                            PipelineNodeConfig configs, OssClientFactory clients, MonitorSettings settings) {
        // 复用交付节点的配置解析与 OSS 客户端边界，不创建独立全局 AK/SK 入口。
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.pipelines = pipelines;
        this.configs = configs;
        this.clients = clients;
        this.settings = settings;
    }

    public void scan(SourceSnapshot.Source source, Consumer<List<SourceSnapshot.Item>> sink) throws Exception {
        // 凭据只读取最新已发布 archive-batch 下载节点；从不落入来源表、通知、异常或快照。
        String definition = jdbc.queryForObject("SELECT pv.definition::text FROM pipeline_version pv " +
                "JOIN pipeline p USING(pipeline_id) WHERE p.pipeline_code=? AND pv.status='PUBLISHED' " +
                "ORDER BY pv.version_no DESC LIMIT 1", String.class, settings.getPipelineCode());
        var nodes = pipelines.read(mapper.readTree(definition)).nodes().stream()
                .filter(node -> node.type().equals("oss.download")).toList();
        if (nodes.size() != 1) throw new IllegalStateException("Monitor requires one published OSS download node");
        OssNodeConfig config = configs.oss(nodes.get(0), true);
        URI uri = URI.create(source.uri());
        OSS client = clients.create(config);
        try {
            list(client, uri.getHost(), uri.getPath().substring(1), sink);
        } finally {
            client.shutdown();
        }
    }

    private void list(OSS client, String bucket, String prefix, Consumer<List<SourceSnapshot.Item>> sink) {
        // 递归分页读取已完成对象，不下载正文；目录占位符不计入数据，空文件仍算一个对象。
        long count = 0;
        ListObjectsV2Request request = new ListObjectsV2Request(bucket).withPrefix(prefix).withMaxKeys(1000);
        ListObjectsV2Result page;
        do {
            page = client.listObjectsV2(request);
            List<SourceSnapshot.Item> items = new ArrayList<>(1000);
            for (OSSObjectSummary object : page.getObjectSummaries()) {
                if (object.getKey().endsWith("/")) continue;
                if (!object.getKey().startsWith(prefix)) throw new IllegalStateException("OSS prefix mismatch");
                items.add(new SourceSnapshot.Item(object.getKey(), object.getSize(),
                        object.getETag(), object.getLastModified().toInstant()));
                if (++count > settings.getMaxObjects()) throw new IllegalStateException("Monitor inventory limit exceeded");
            }
            sink.accept(items);
            String next = page.getNextContinuationToken();
            if (page.isTruncated() && (next == null || next.equals(request.getContinuationToken()))) {
                throw new IllegalStateException("OSS pagination did not progress");
            }
            request.setContinuationToken(next);
        } while (page.isTruncated());
    }
}
