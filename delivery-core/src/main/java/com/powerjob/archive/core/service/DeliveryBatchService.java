package com.powerjob.archive.core.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.OSSObjectSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.config.ArchiveProperties;
import com.powerjob.archive.core.domain.DiscoveredObject;
import com.powerjob.archive.core.domain.DeliverySubmit;
import com.powerjob.archive.core.domain.SourceData;
import com.powerjob.archive.core.pipeline.OssNodeConfig;
import com.powerjob.archive.core.pipeline.PipelineDefinition;
import com.powerjob.archive.core.pipeline.PipelineNodeConfig;
import com.powerjob.archive.core.pipeline.PipelineRepository;
import com.powerjob.archive.core.repository.DeliveryBatchRepository;
import com.powerjob.archive.core.repository.DeliveryEventRepository;
import com.powerjob.archive.core.repository.DeliveryRepository;
import com.powerjob.archive.core.repository.SourceDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class DeliveryBatchService {
    private static final Logger log = LoggerFactory.getLogger(DeliveryBatchService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final DeliveryRepository deliveries;
    private final DeliveryBatchRepository batches;
    private final SourceDataRepository sources;
    private final DeliveryEventRepository events;
    private final DeliveryProjectionService projections;
    private final PipelineRepository pipelines;
    private final PipelineNodeConfig configs;
    private final IngestSourceService ingestSources;
    private final OssClientFactory clients;
    private final FileTransferService files;
    private final ArchiveProperties properties;

    public DeliveryBatchService(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper,
                                DeliveryRepository deliveries, DeliveryBatchRepository batches, SourceDataRepository sources,
                                DeliveryEventRepository events, DeliveryProjectionService projections, PipelineRepository pipelines,
                                PipelineNodeConfig configs, IngestSourceService ingestSources, OssClientFactory clients,
                                FileTransferService files, ArchiveProperties properties) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
        this.deliveries = deliveries;
        this.batches = batches;
        this.sources = sources;
        this.events = events;
        this.projections = projections;
        this.pipelines = pipelines;
        this.configs = configs;
        this.ingestSources = ingestSources;
        this.clients = clients;
        this.files = files;
        this.properties = properties;
    }

    public DeliverySubmissionService.Submission create(BatchCommand command) {
        validate(command);
        var existing = deliveries.findBatchCreation(command.deliveryBatch());
        if (existing.isPresent()) return sameRequest(existing.get(), command);
        try {
            return transactions.execute(status -> {
                Resolved resolved = resolve(command);
                ObjectNode strategy = mapper.createObjectNode().put("schemaVersion", 1);
                strategy.putArray("targets");
                long id = jdbc.queryForObject("INSERT INTO delivery_submit(delivery_batch,source_id,pipeline_version_id," +
                                "delivery_date,cutoff_at,submitted_at,target_strategy,submission_type) VALUES(?,?,?,?,?,NULL,?::jsonb,'BATCH_CREATE') " +
                                "RETURNING delivery_id", Long.class, command.deliveryBatch(), resolved.sourceId(), resolved.pipelineVersionId(),
                        command.deliveryDate(), resolved.cutoffAt(), strategy.toString());
                events.append(id, null, null, null, null, "BATCH_CREATE_SUBMITTED", "INFO", null,
                        "OSS batch creation accepted", mapper.createObjectNode().put("deliveryId", id), "api", "batch-submitted");
                return new DeliverySubmissionService.Submission(id, "SUBMITTED", command.deliveryDate(), resolved.cutoffAt(),
                        "BATCH_CREATE", null);
            });
        } catch (DuplicateKeyException error) {
            return deliveries.findBatchCreation(command.deliveryBatch()).map(found -> sameRequest(found, command))
                    .orElseThrow(() -> error);
        }
    }

    public int prepare(long deliveryId) {
        DeliverySubmit submit = deliveries.find(deliveryId).orElseThrow();
        if (!submit.batchCreation()) throw new IllegalArgumentException("Only BATCH_CREATE can archive OSS sources");
        if (submit.discoveryCompletedAt() != null) return batches.byBatch(deliveryId).size();
        deliveries.markDiscovering(deliveryId);
        PipelineDefinition pipeline = pipeline(submit.pipelineVersionId());
        BatchPlanningService.Limits limits = BatchPlanningService.limits(pipeline);
        List<BatchPlanningService.EntityWorker> workers = BatchPlanningService.entityWorkers(pipeline, deliveries);
        OssNodeConfig config = configs.oss(pipeline.nodes().stream().filter(node -> node.type().equals("oss.download")).findFirst()
                .orElseThrow(), true);
        SourceLocation location = sourceLocation(submit.sourceId());
        Discovery discovery = discover(config, location, limits.maxPackageBytes());
        List<ArchivedSource> archived = downloadAll(deliveryId, config, location, discovery.objects());
        long sourceMaximum = limits.maxPackageBytes() * 95 / 100;
        List<List<Integer>> assignments = BatchPlanningService.planPackages(
                archived.stream().map(item -> item.source().sizeBytes()).toList(),
                new BatchPlanningService.Limits(sourceMaximum), limits.maxPackageBytes());
        List<BatchPlanningService.PlanningSlot> slots = BatchPlanningService.slots(assignments.size(), workers);
        transactions.executeWithoutResult(status -> {
            Map<String, Integer> distribution = new LinkedHashMap<>();
            for (int packageIndex = 0; packageIndex < assignments.size(); packageIndex++) {
                String newFileName = String.format("batch_%07d.tar.gz", packageIndex + 1);
                BatchPlanningService.PlanningSlot slot = slots.get(packageIndex);
                String distributionKey = slot.entity().entityCode() + "/" + slot.workerGroup();
                distribution.merge(distributionKey, 1, Integer::sum);
                int sourceSeq = 0;
                for (int sourceIndex : assignments.get(packageIndex)) {
                    ArchivedSource item = archived.get(sourceIndex);
                    batches.insertPlanned(deliveryId, item.source(), submit.deliveryBatch(), item.originalFileName(),
                            newFileName, ++sourceSeq, slot.entity().deliveryEntityId(), slot.entity().entityCode(), slot.workerGroup());
                }
            }
            ObjectNode extension = mapper.createObjectNode().put("maxPackageBytes", limits.maxPackageBytes())
                    .put("sourceCount", archived.size())
                    .put("distinctSourceCount", archived.stream().map(item -> item.source().dataId()).distinct().count())
                    .put("packageCount", 0).put("filteredOversizedCount", discovery.oversized().size());
            ArrayNode allocations = extension.putArray("allocations");
            distribution.forEach((key, count) -> allocations.addObject().put("entityWorker", key).put("packageCount", count));
            jdbc.update("UPDATE delivery_submit SET batch_extension=?::jsonb WHERE delivery_id=?", extension.toString(), deliveryId);
            long totalBytes = archived.stream().mapToLong(item -> item.source().sizeBytes()).sum();
            jdbc.update("UPDATE ingest_source SET previous_refresh_bytes=latest_refresh_bytes,latest_refresh_bytes=? WHERE source_id=?",
                    totalBytes, submit.sourceId());
            deliveries.markDiscoveryCompleted(deliveryId);
            events.append(deliveryId, null, null, null, null, "BATCH_PLANNED", "INFO", null,
                    "OSS sources archived and batch packages planned", extension, "worker", "batch-planned");
            projections.refresh(deliveryId);
        });
        for (OSSObjectSummary skipped : discovery.oversized()) {
            String sourceUri = "oss://" + location.bucket() + "/" + skipped.getKey();
            events.append(deliveryId, null, null, null, null, "SOURCE_FILTERED", "WARN", "PACKAGE_TOO_LARGE",
                    "Source package exceeds maxPackageBytes", mapper.createObjectNode().put("sourceUri", sourceUri)
                            .put("sizeBytes", skipped.getSize()).put("maxPackageBytes", limits.maxPackageBytes()), null,
                    "source-filtered-" + UUID.nameUUIDFromBytes(sourceUri.getBytes(StandardCharsets.UTF_8)));
        }
        log.info("OSS batch planned deliveryId={} deliveryBatch={} sources={} distinctSources={} packages={} oversized={}",
                deliveryId, submit.deliveryBatch(), archived.size(),
                archived.stream().map(item -> item.source().dataId()).distinct().count(), assignments.size(), discovery.oversized().size());
        return archived.size();
    }

    private Discovery discover(OssNodeConfig config, SourceLocation location, long maxPackageBytes) {
        OSS client = clients.create(config);
        List<OSSObjectSummary> objects = new ArrayList<>();
        List<OSSObjectSummary> oversized = new ArrayList<>();
        try {
            String continuation = null;
            do {
                var page = client.listObjectsV2(new ListObjectsV2Request(location.bucket()).withPrefix(location.prefix())
                        .withMaxKeys(1000).withContinuationToken(continuation));
                for (OSSObjectSummary object : page.getObjectSummaries()) {
                    if (!object.getKey().endsWith(".tar.gz") || object.getSize() <= 0) continue;
                    (object.getSize() > maxPackageBytes ? oversized : objects).add(object);
                }
                continuation = page.getNextContinuationToken();
            } while (continuation != null && !continuation.isBlank());
            objects.sort(Comparator.comparing(OSSObjectSummary::getKey));
            oversized.sort(Comparator.comparing(OSSObjectSummary::getKey));
            return new Discovery(List.copyOf(objects), List.copyOf(oversized));
        } finally {
            client.shutdown();
        }
    }

    private List<ArchivedSource> downloadAll(long deliveryId, OssNodeConfig config, SourceLocation location,
                                             List<OSSObjectSummary> objects) {
        int threads = Math.max(1, Math.min(properties.getPreparationDownloadThreads(), objects.size()));
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<ArchivedSource>> work = new ArrayList<>();
            for (OSSObjectSummary object : objects) work.add(() -> download(deliveryId, config, location, object));
            List<ArchivedSource> result = new ArrayList<>(objects.size());
            for (var future : executor.invokeAll(work)) result.add(future.get());
            return List.copyOf(result);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Batch download interrupted", error);
        } catch (java.util.concurrent.ExecutionException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Batch download failed", error.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    private ArchivedSource download(long deliveryId, OssNodeConfig config, SourceLocation location, OSSObjectSummary object) {
        OSS client = clients.create(config);
        try {
            String fileName = Path.of(object.getKey()).getFileName().toString();
            String etag = object.getETag() == null ? "" : object.getETag().replace("\"", "");
            String version = !etag.isBlank() ? etag : object.getLastModified().toInstant().toEpochMilli() + "-" + object.getSize();
            String sourceUri = "oss://" + location.bucket() + "/" + object.getKey();
            String identity = sourceUri + "#" + version;
            Path destination = Path.of(properties.getWorkRoot(), "source-cache",
                    UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString(), fileName);
            if (!Files.isRegularFile(destination) || Files.size(destination) != object.getSize()) {
                try (var input = client.getObject(location.bucket(), object.getKey()).getObjectContent()) {
                    files.copyStream(input, destination);
                }
            }
            if (Files.size(destination) != object.getSize()) throw new IllegalStateException("Source OSS size mismatch");
            String sha = files.sha256(destination);
            SourceData source = sources.findById(sources.insertDiscovered(deliveryId,
                    new DiscoveredObject(location.bucket(), object.getKey(), version, etag, object.getSize(), sha,
                            destination.toAbsolutePath().toString())).dataId()).orElseThrow();
            return new ArchivedSource(source, fileName);
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Source OSS batch download failed", error);
        } finally {
            client.shutdown();
        }
    }

    private DeliverySubmissionService.Submission sameRequest(DeliverySubmit existing, BatchCommand command) {
        String sourceUri = jdbc.queryForObject("SELECT oss_uri FROM ingest_source WHERE source_id=?", String.class, existing.sourceId());
        String pipelineCode = jdbc.queryForObject("SELECT p.pipeline_code FROM pipeline p JOIN pipeline_version pv ON p.pipeline_id=pv.pipeline_id " +
                "WHERE pv.pipeline_version_id=?", String.class, existing.pipelineVersionId());
        if (!existing.deliveryDate().equals(command.deliveryDate()) || !sourceUri.equals(command.ossUri())
                || !pipelineCode.equals(command.pipelineCode())) {
            throw new IdempotencyConflictException("deliveryBatch is already used by different batch creation parameters");
        }
        String status = existing.status();
        if ("BLOCKED".equals(status) && existing.discoveryCompletedAt() == null) {
            status = transactions.execute(transaction -> {
                int resumed = jdbc.update("UPDATE delivery_submit SET status='SUBMITTED',updated_at=now() " +
                        "WHERE delivery_id=? AND status='BLOCKED' AND discovery_completed_at IS NULL", existing.deliveryId());
                if (resumed == 1) {
                    events.append(existing.deliveryId(), null, null, null, null, "BATCH_CREATE_RESUMED", "INFO", null,
                            "Explicit identical batch request resumes failed discovery", mapper.createObjectNode()
                                    .put("deliveryId", existing.deliveryId()), "api",
                            "batch-resumed-" + existing.deliveryId() + "-" + UUID.randomUUID());
                }
                return resumed == 1 ? "SUBMITTED" : "BLOCKED";
            });
        }
        return new DeliverySubmissionService.Submission(existing.deliveryId(), status, existing.deliveryDate(),
                existing.cutoffAt(), existing.submissionType(), null);
    }

    private Resolved resolve(BatchCommand command) {
        long sourceId = ingestSources.resolve(command.ossUri());
        Integer available = jdbc.queryForObject("SELECT count(*) FROM ingest_source WHERE source_id=? AND availability_status='AVAILABLE'",
                Integer.class, sourceId);
        if (available != 1) throw new IllegalArgumentException("Source is unavailable");
        var row = jdbc.query("SELECT pv.pipeline_version_id,pv.definition::text FROM pipeline_version pv JOIN pipeline p ON p.pipeline_id=pv.pipeline_id " +
                        "WHERE p.pipeline_code=? AND p.pipeline_code='archive-batch' AND pv.status='PUBLISHED' ORDER BY pv.version_no DESC LIMIT 1",
                (rs, n) -> new PipelineRow(rs.getLong(1), rs.getString(2)), command.pipelineCode()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("archive-batch has no published version"));
        PipelineDefinition pipeline;
        try {
            pipeline = pipelines.read(mapper.readTree(row.definition()));
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid archive-batch definition", error);
        }
        OffsetDateTime cutoff = cutoff(command.deliveryDate(), pipeline);
        return new Resolved(sourceId, row.id(), cutoff);
    }

    static OffsetDateTime cutoff(LocalDate deliveryDate, PipelineDefinition pipeline) {
        OffsetDateTime cutoff = deliveryDate.plusDays(1).atTime(LocalTime.parse(String.valueOf(
                        pipeline.config().getOrDefault("deliveryCutoffTime", "18:00:00"))))
                .atZone(ZoneId.of(String.valueOf(pipeline.config().getOrDefault("businessTimezone", "Asia/Shanghai")))).toOffsetDateTime();
        if (!cutoff.isAfter(OffsetDateTime.now())) throw new IllegalArgumentException("Pipeline delivery cutoff must be in the future");
        return cutoff;
    }

    private PipelineDefinition pipeline(long versionId) {
        try {
            return pipelines.read(mapper.readTree(jdbc.queryForObject("SELECT definition::text FROM pipeline_version WHERE pipeline_version_id=?",
                    String.class, versionId)));
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid batch PipelineVersion", error);
        }
    }

    private SourceLocation sourceLocation(long sourceId) {
        URI location = URI.create(jdbc.queryForObject("SELECT oss_uri FROM ingest_source WHERE source_id=?", String.class, sourceId));
        if (!"oss".equals(location.getScheme()) || location.getHost() == null || location.getUserInfo() != null
                || location.getQuery() != null || location.getFragment() != null) {
            throw new IllegalArgumentException("ingest_source must contain an unsigned oss://bucket/prefix URI");
        }
        return new SourceLocation(location.getHost(), location.getPath() == null ? "" : location.getPath().replaceFirst("^/", ""));
    }

    private static void validate(BatchCommand command) {
        if (command == null || blank(command.ossUri()) || blank(command.pipelineCode()) || blank(command.deliveryBatch())
                || command.deliveryBatch().length() > 128 || command.deliveryDate() == null) {
            throw new IllegalArgumentException("ossUri, pipelineCode, deliveryDate and deliveryBatch are required");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record BatchCommand(String ossUri, String pipelineCode, LocalDate deliveryDate, String deliveryBatch) {}
    private record PipelineRow(long id, String definition) {}
    private record Resolved(long sourceId, long pipelineVersionId, OffsetDateTime cutoffAt) {}
    private record SourceLocation(String bucket, String prefix) {}
    private record Discovery(List<OSSObjectSummary> objects, List<OSSObjectSummary> oversized) {}
    private record ArchivedSource(SourceData source, String originalFileName) {}
}
