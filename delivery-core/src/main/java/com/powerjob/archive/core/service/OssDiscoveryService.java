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

    public OssDiscoveryService(OssClientFactory clients, MvpProperties properties, SourcePackageRepository sources,
                               JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        this.clients = clients;
        this.properties = properties;
        this.sources = sources;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
    }

    public int discover(long runId) {
        var run = jdbc.queryForMap("SELECT bucket_name,object_prefix FROM delivery_run WHERE id=?", runId);
        OSS client = clients.create();
        int discovered = 0;
        try {
            String continuation = null;
            do {
                ListObjectsV2Result result = client.listObjectsV2(new ListObjectsV2Request(run.get("bucket_name").toString())
                        .withPrefix(run.get("object_prefix").toString()).withMaxKeys(100)
                        .withContinuationToken(continuation));
                List<DiscoveredObject> page = result.getObjectSummaries().stream()
                        .filter(object -> object.getKey().endsWith(".tar.gz") && object.getSize() > 0)
                        .sorted(java.util.Comparator.comparing(OSSObjectSummary::getKey))
                        .map(object -> discovered(object, run.get("bucket_name").toString())).toList();
                String next = result.getNextContinuationToken();
                transactions.executeWithoutResult(status -> {
                    page.forEach(object -> sources.upsertDiscovered(runId, object));
                    try {
                        jdbc.update("UPDATE ingest_source SET discovery_cursor=?::jsonb " +
                                        "WHERE bucket_ref=? AND object_prefix=?",
                                mapper.writeValueAsString(Map.of("continuationToken", next == null ? "" : next)),
                                run.get("bucket_name"), run.get("object_prefix"));
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
        OSS client = clients.create();
        try {
            ListObjectsV2Result result = client.listObjectsV2(new ListObjectsV2Request(properties.getOssSourceBucket())
                    .withPrefix(properties.getOssSourcePrefix()).withMaxKeys(100));
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
                return new DiscoveredObject(properties.getOssSourceBucket(), object.getKey(), etag,
                        etag, object.getSize(), index < properties.getGroupSize() ? 1L : 2L);
            }).collect(Collectors.toList());
        } finally {
            client.shutdown();
        }
    }

    private DiscoveredObject discovered(OSSObjectSummary object, String bucket) {
        String etag = object.getETag() == null ? null : object.getETag().replace("\"", "");
        return new DiscoveredObject(bucket, object.getKey(), etag, etag, object.getSize(), 0L);
    }
}
