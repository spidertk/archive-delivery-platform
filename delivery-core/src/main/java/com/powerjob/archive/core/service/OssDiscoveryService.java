package com.powerjob.archive.core.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.ListObjectsV2Result;
import com.aliyun.oss.model.OSSObjectSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.config.MvpProperties;
import com.powerjob.archive.core.domain.DiscoveredObject;
import com.powerjob.archive.core.repository.SourcePackageRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "archive.discovery-mode", havingValue = "oss")
public class OssDiscoveryService implements DiscoveryService {
    private final OssClientFactory clients;
    private final MvpProperties properties;
    private final SourcePackageRepository sources;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final com.powerjob.archive.core.pipeline.PipelineRepository pipelines;
    private final com.powerjob.archive.core.pipeline.PipelineNodeConfig configs;

    public OssDiscoveryService(OssClientFactory clients, MvpProperties properties, SourcePackageRepository sources,
                               JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper,
                               com.powerjob.archive.core.pipeline.PipelineRepository pipelines,
                               com.powerjob.archive.core.pipeline.PipelineNodeConfig configs) {
        this.clients = clients;
        this.properties = properties;
        this.sources = sources;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
        this.pipelines = pipelines;
        this.configs = configs;
    }

    public int discover(long runId) {
        var run = jdbc.queryForMap("SELECT r.bucket_name,r.object_prefix,pv.definition FROM delivery_run r " +
                "JOIN pipeline_version pv ON pv.id=r.pipeline_version_id WHERE r.id=?", runId);
        var config = sourceConfig(run.get("definition"));
        requireSameLocation(config, run.get("bucket_name").toString(), run.get("object_prefix").toString());
        OSS client = clients.create(config);
        int discovered = 0;
        try {
            String continuation = null;
            do {
                ListObjectsV2Result result = client.listObjectsV2(new ListObjectsV2Request(config.bucket())
                        .withPrefix(config.prefix()).withMaxKeys(100).withContinuationToken(continuation));
                List<DiscoveredObject> page = result.getObjectSummaries().stream()
                        .filter(object -> object.getKey().endsWith(".tar.gz") && object.getSize() > 0)
                        .sorted(java.util.Comparator.comparing(OSSObjectSummary::getKey))
                        .map(object -> discovered(object, config.bucket())).toList();
                String next = result.getNextContinuationToken();
                transactions.executeWithoutResult(status -> {
                    page.forEach(object -> sources.upsertDiscovered(runId, object));
                    try {
                        jdbc.update("UPDATE ingest_source SET discovery_cursor=?::jsonb " +
                                        "WHERE bucket_ref=? AND object_prefix=?",
                                mapper.writeValueAsString(Map.of("continuationToken", next == null ? "" : next)),
                                config.bucket(), config.prefix());
                    } catch (Exception e) {
                        throw new IllegalStateException("Cannot persist discovery cursor", e);
                    }
                });
                discovered += page.size();
                continuation = next;
            } while (continuation != null && !continuation.isBlank());
        } finally {
            client.shutdown();
        }
        jdbc.update("UPDATE delivery_run SET status='DISCOVERED',updated_at=now() WHERE id=? AND status='DISCOVERING'", runId);
        return discovered;
    }

    @Override
    public List<DiscoveredObject> discover() {
        var source = jdbc.queryForMap("SELECT pv.definition FROM source_pipeline_binding b " +
                "JOIN pipeline_version pv ON pv.id=b.pipeline_version_id " +
                "WHERE b.effective_to IS NULL AND pv.status='PUBLISHED' ORDER BY b.effective_from DESC LIMIT 1");
        var config = sourceConfig(source.get("definition"));
        OSS client = clients.create(config);
        try {
            ListObjectsV2Result result = client.listObjectsV2(new ListObjectsV2Request(config.bucket())
                    .withPrefix(config.prefix()).withMaxKeys(100));
            List<OSSObjectSummary> objects = result.getObjectSummaries().stream()
                    .filter(object -> object.getKey().endsWith(".tar.gz") && object.getSize() > 0)
                    .sorted(java.util.Comparator.comparing(OSSObjectSummary::getKey))
                    .limit(properties.getGroupSize() * 2L)
                    .collect(Collectors.toList());
            if (objects.size() < properties.getGroupSize() * 2L) {
                throw new IllegalStateException("OSS prefix contains fewer than 10 tar.gz packages");
            }
            return java.util.stream.IntStream.range(0, objects.size()).mapToObj(index -> {
                OSSObjectSummary object = objects.get(index);
                String etag = object.getETag() == null ? null : object.getETag().replace("\"", "");
                return new DiscoveredObject(config.bucket(), object.getKey(), etag,
                        etag, object.getSize(), index < properties.getGroupSize() ? 1L : 2L);
            }).collect(Collectors.toList());
        } finally {
            client.shutdown();
        }
    }

    private com.powerjob.archive.core.pipeline.OssNodeConfig sourceConfig(Object json) {
        try {
            var definition = pipelines.read(mapper.readTree(json.toString()));
            return configs.oss(definition.node("download"), true);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid source Pipeline configuration", e);
        }
    }

    private static void requireSameLocation(com.powerjob.archive.core.pipeline.OssNodeConfig config,
                                            String bucket, String prefix) {
        if (!config.bucket().equals(bucket) || !config.prefix().equals(prefix)) {
            throw new IllegalStateException("Run OSS location differs from bound Pipeline download config");
        }
    }

    private DiscoveredObject discovered(OSSObjectSummary object, String bucket) {
        String etag = object.getETag() == null ? null : object.getETag().replace("\"", "");
        return new DiscoveredObject(bucket, object.getKey(), etag, etag, object.getSize(), 0L);
    }
}
