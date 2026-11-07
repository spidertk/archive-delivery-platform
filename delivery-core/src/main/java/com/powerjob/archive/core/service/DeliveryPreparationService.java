package com.powerjob.archive.core.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.ListObjectsV2Result;
import com.aliyun.oss.model.OSSObjectSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.config.ArchiveProperties;
import com.powerjob.archive.core.domain.DeliverySubmit;
import com.powerjob.archive.core.domain.DiscoveredObject;
import com.powerjob.archive.core.pipeline.OssNodeConfig;
import com.powerjob.archive.core.pipeline.PipelineDefinition;
import com.powerjob.archive.core.pipeline.PipelineNodeConfig;
import com.powerjob.archive.core.pipeline.PipelineRepository;
import com.powerjob.archive.core.repository.DeliveryEventRepository;
import com.powerjob.archive.core.repository.DeliveryRepository;
import com.powerjob.archive.core.repository.DeliveryTaskRepository;
import com.powerjob.archive.core.repository.SourceDataRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class DeliveryPreparationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DeliveryRepository deliveries;
    private final DeliveryTaskRepository tasks;
    private final SourceDataRepository sources;
    private final DeliveryEventRepository events;
    private final DeliveryProjectionService projections;
    private final PipelineRepository pipelines;
    private final PipelineNodeConfig configs;
    private final OssClientFactory clients;
    private final FileTransferService files;
    private final ArchiveProperties properties;
    private final ObjectMapper mapper;

    public DeliveryPreparationService(JdbcTemplate jdbc, TransactionTemplate transactions, DeliveryRepository deliveries,
                                      DeliveryTaskRepository tasks, SourceDataRepository sources, DeliveryEventRepository events,
                                      DeliveryProjectionService projections, PipelineRepository pipelines, PipelineNodeConfig configs,
                                      OssClientFactory clients, FileTransferService files, ArchiveProperties properties, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.deliveries = deliveries;
        this.tasks = tasks;
        this.sources = sources;
        this.events = events;
        this.projections = projections;
        this.pipelines = pipelines;
        this.configs = configs;
        this.clients = clients;
        this.files = files;
        this.properties = properties;
        this.mapper = mapper;
    }

    /** 发现由 selection 驱动，目标任务由提交 targets 顺序和 targetCount 分配；源文件真实下载取得 SHA 后保留。 */
    public int prepare(long deliveryId) {
        DeliverySubmit submit = deliveries.find(deliveryId).orElseThrow();
        if (submit.discoveryCompletedAt() != null) return tasks.byDelivery(deliveryId).size();
        deliveries.markDiscovering(deliveryId);
        PipelineDefinition pipeline = pipeline(submit.pipelineVersionId());
        int limit = pipeline.selectionLimit();
        List<Target> targets = targets(submit.targetStrategy().required("targets"));
        int target = targets.stream().mapToInt(Target::targetCount).sum();
        if (target != limit) throw new IllegalArgumentException("Pipeline selection.limit must equal the sum of targetCount");
        OssNodeConfig config = sourceConfig(pipeline);
        verifySourceLocation(submit.sourceId(), config);
        List<DiscoveredObject> objects = discover(config, limit, deliveryId);
        if (objects.size() != limit) throw new IllegalArgumentException("Source OSS does not contain the required non-empty tar.gz objects");
        for (int i = 0; i < objects.size(); i++) {
            DiscoveredObject object = objects.get(i);
            Assignment assignment = assignmentFor(targets, i);
            deliveries.entity(assignment.deliveryEntityId());
            transactions.executeWithoutResult(status -> {
                SourceDataRepository.SourceInsert inserted = sources.insertDiscovered(deliveryId, object);
                if (!inserted.deliverable()) {
                    events.append(deliveryId, null, null, assignment.deliveryEntityId(), inserted.duplicateDataId(), "SOURCE_FILTERED", "WARN",
                            "DUPLICATE_ASSET", "Duplicate source asset filtered", mapper.createObjectNode()
                                    .put("dataId", inserted.duplicateDataId()).put("deliveryEntityId", assignment.deliveryEntityId()), null,
                            "source-filtered-" + inserted.duplicateDataId());
                    return;
                }
                long taskId = tasks.createPlanned(submit, sources.findById(inserted.dataId()).orElseThrow(),
                        assignment.deliveryEntityId(), assignment.targetSeq(), pipeline.firstNode());
                events.append(deliveryId, taskId, null, assignment.deliveryEntityId(), inserted.dataId(), "TASK_PLANNED", "INFO", null,
                        "Source artifact assigned to delivery entity", mapper.createObjectNode().put("taskId", taskId)
                                .put("dataId", inserted.dataId()).put("deliveryEntityId", assignment.deliveryEntityId())
                                .put("targetSeq", assignment.targetSeq()), null, "task-planned-" + taskId);
            });
        }
        transactions.executeWithoutResult(status -> {
            long totalBytes = objects.stream().mapToLong(DiscoveredObject::size).sum();
            jdbc.update("UPDATE ingest_source SET previous_refresh_bytes=latest_refresh_bytes,latest_refresh_bytes=? WHERE source_id=?",
                    totalBytes, submit.sourceId());
            deliveries.markDiscoveryCompleted(deliveryId);
            projections.refresh(deliveryId);
        });
        if (tasks.byDelivery(deliveryId).size() != target) throw new IllegalStateException("Planning did not satisfy all delivery targets");
        return objects.size();
    }

    private List<DiscoveredObject> discover(OssNodeConfig config, int limit, long deliveryId) {
        OSS client = clients.create(config);
        List<OSSObjectSummary> selected = new ArrayList<>();
        try {
            String continuation = null;
            do {
                ListObjectsV2Result page = client.listObjectsV2(new ListObjectsV2Request(config.bucket())
                        .withPrefix(config.prefix()).withMaxKeys(Math.max(100, limit)).withContinuationToken(continuation));
                page.getObjectSummaries().stream().filter(object -> object.getKey().endsWith(".tar.gz") && object.getSize() > 0)
                        .forEach(selected::add);
                continuation = page.getNextContinuationToken();
                if (selected.size() >= limit) break;
            } while (continuation != null && !continuation.isBlank());
            selected.sort(Comparator.comparing(OSSObjectSummary::getKey));
            List<DiscoveredObject> result = new ArrayList<>();
            for (int i = 0; i < Math.min(limit, selected.size()); i++) {
                OSSObjectSummary object = selected.get(i);
                String fileName = Path.of(object.getKey()).getFileName().toString();
                Path download = Path.of(properties.getWorkRoot(), "discovery", Long.toString(deliveryId),
                        String.format("%03d-", i + 1) + fileName);
                String sha = download(client, config, object, download);
                String etag = object.getETag() == null ? "" : object.getETag().replace("\"", "");
                String version = !etag.isBlank() ? etag : object.getLastModified().toInstant().toEpochMilli() + "-" + object.getSize();
                result.add(new DiscoveredObject(config.bucket(), object.getKey(), version, etag, object.getSize(), sha));
            }
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Source OSS discovery/download failed", e);
        } finally {
            client.shutdown();
        }
    }

    private String download(OSS client, OssNodeConfig config, OSSObjectSummary object, Path destination) throws Exception {
        if (Files.isRegularFile(destination) && Files.size(destination) == object.getSize()) return files.sha256(destination);
        try (var input = client.getObject(config.bucket(), object.getKey()).getObjectContent()) {
            files.copyStream(input, destination);
        }
        if (Files.size(destination) != object.getSize()) throw new IllegalStateException("Discovered source size mismatch");
        return files.sha256(destination);
    }

    private static List<Target> targets(JsonNode values) {
        List<Target> result = new ArrayList<>();
        values.forEach(target -> result.add(new Target(target.required("deliveryEntityId").asLong(), target.required("targetCount").asInt())));
        return List.copyOf(result);
    }

    private static Assignment assignmentFor(List<Target> targets, int objectIndex) {
        int offset = objectIndex;
        for (Target target : targets) {
            if (offset < target.targetCount()) return new Assignment(target.deliveryEntityId(), offset + 1);
            offset -= target.targetCount();
        }
        throw new IllegalArgumentException("No delivery target capacity for selected artifact");
    }

    private PipelineDefinition pipeline(long versionId) {
        try {
            return pipelines.read(mapper.readTree(jdbc.queryForObject("SELECT definition::text FROM pipeline_version WHERE pipeline_version_id=?",
                    String.class, versionId)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid PipelineVersion", e);
        }
    }

    private OssNodeConfig sourceConfig(PipelineDefinition pipeline) {
        return configs.oss(pipeline.nodes().stream().filter(node -> node.type().equals("oss.download")).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Pipeline requires one oss.download node")), true);
    }

    private void verifySourceLocation(long sourceId, OssNodeConfig config) {
        String uri = jdbc.queryForObject("SELECT oss_uri FROM ingest_source WHERE source_id=?", String.class, sourceId);
        URI location = URI.create(uri);
        String prefix = location.getPath() == null ? "" : location.getPath().replaceFirst("^/", "");
        if (!"oss".equals(location.getScheme()) || !config.bucket().equals(location.getHost()) || !config.prefix().equals(prefix)) {
            throw new IllegalArgumentException("ingest_source OSS URI differs from Pipeline sourceDownload config");
        }
    }

    private record Target(long deliveryEntityId, int targetCount) {}
    private record Assignment(long deliveryEntityId, int targetSeq) {}
}
