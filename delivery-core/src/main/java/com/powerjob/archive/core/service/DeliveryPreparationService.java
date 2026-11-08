package com.powerjob.archive.core.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.ListObjectsV2Result;
import com.aliyun.oss.model.OSSObjectSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.config.ArchiveProperties;
import com.powerjob.archive.core.domain.DeliveryEntity;
import com.powerjob.archive.core.domain.DeliverySubmit;
import com.powerjob.archive.core.domain.DiscoveredObject;
import com.powerjob.archive.core.domain.SourceData;
import com.powerjob.archive.core.pipeline.OssNodeConfig;
import com.powerjob.archive.core.pipeline.PipelineDefinition;
import com.powerjob.archive.core.pipeline.PipelineNodeConfig;
import com.powerjob.archive.core.pipeline.PipelineRepository;
import com.powerjob.archive.core.repository.DeliveryEventRepository;
import com.powerjob.archive.core.repository.DeliveryRepository;
import com.powerjob.archive.core.repository.DeliveryTaskRepository;
import com.powerjob.archive.core.repository.SourceDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DeliveryPreparationService {
    private static final Logger log = LoggerFactory.getLogger(DeliveryPreparationService.class);
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

    /** selection.limit 限制扫描入选数；按厂商整体顺序累计来源，超限包过滤，所有目标装满后忽略后续来源。 */
    public int prepare(long deliveryId) {
        DeliverySubmit submit = deliveries.find(deliveryId).orElseThrow();
        if (submit.discoveryCompletedAt() != null) return tasks.byDelivery(deliveryId).size();
        deliveries.markDiscovering(deliveryId);
        PipelineDefinition pipeline = pipeline(submit.pipelineVersionId());
        int limit = pipeline.selectionLimit();
        long maxPackageBytes = pipeline.maxPackageBytes();
        List<Target> targets = targets(submit.targetStrategy().required("targets"));
        List<TargetSlot> slots = targetSlots(targets);
        if (slots.size() > limit) throw new IllegalArgumentException("Pipeline selection.limit must be at least the sum of targetCount");
        OssNodeConfig config = sourceConfig(pipeline);
        verifySourceLocation(submit.sourceId(), config);
        log.info("Delivery preparation started deliveryId={} selectionLimit={} targetTaskCount={} maxPackageBytes={} sourcePrefix=oss://{}/{}",
                deliveryId, limit, slots.size(), maxPackageBytes, config.bucket(), config.prefix());
        Discovery discovery = discover(config, limit, deliveryId, maxPackageBytes);
        List<List<Integer>> assignments = planSourceIndexes(discovery.objects().stream().map(OSSObjectSummary::getSize).toList(),
                slots.size(), maxPackageBytes);
        for (OSSObjectSummary skipped : discovery.oversized()) {
            String sourceUri = "oss://" + config.bucket() + "/" + skipped.getKey();
            ObjectNode payload = mapper.createObjectNode().put("sourceUri", sourceUri)
                    .put("sizeBytes", skipped.getSize()).put("maxPackageBytes", maxPackageBytes);
            String dedup = "source-filtered-oversized-" + UUID.nameUUIDFromBytes(sourceUri.getBytes(StandardCharsets.UTF_8));
            events.append(deliveryId, null, null, null, null, "SOURCE_FILTERED", "WARN",
                    "PACKAGE_TOO_LARGE", "Source package exceeds maxPackageBytes and was not downloaded", payload, null, dedup);
        }

        Map<Integer, SourceData> registered = new HashMap<>();
        List<PlannedTask> planned = new ArrayList<>();
        for (int slotIndex = 0; slotIndex < slots.size(); slotIndex++) {
            List<SourceData> taskSources = new ArrayList<>();
            for (int objectIndex : assignments.get(slotIndex)) {
                SourceData source = registered.computeIfAbsent(objectIndex, index -> register(deliveryId, config,
                        discovery.objects().get(index), index + 1));
                taskSources.add(source);
            }
            planned.add(new PlannedTask(slots.get(slotIndex), List.copyOf(taskSources)));
        }
        for (PlannedTask plan : planned) {
            DeliveryEntity entity = deliveries.entity(plan.slot().deliveryEntityId());
            long taskId = tasks.createPlanned(submit, plan.sources(), entity, plan.slot().packageSeq(), pipeline.firstNode());
            ObjectNode payload = mapper.createObjectNode().put("taskId", taskId)
                    .put("deliveryEntityId", entity.deliveryEntityId()).put("entityCode", entity.entityCode())
                    .put("vendorCode", entity.vendorCode()).put("workerGroup", entity.workerGroup())
                    .put("packageSeq", plan.slot().packageSeq())
                    .put("sourceCount", plan.sources().size())
                    .put("sourceBytes", plan.sources().stream().mapToLong(SourceData::sizeBytes).sum())
                    .put("maxPackageBytes", maxPackageBytes);
            ArrayNode sourceIds = payload.putArray("sourceDataIds");
            plan.sources().forEach(source -> sourceIds.add(source.dataId()));
            events.append(deliveryId, taskId, null, entity.deliveryEntityId(), plan.sources().get(0).dataId(), "TASK_PLANNED", "INFO", null,
                    "Source packages assigned to a merged delivery task", payload, null, "task-planned-" + taskId);
            log.info("Delivery task planned deliveryId={} taskId={} entityCode={} vendorCode={} packageSeq={} sourceDataIds={} sourceBytes={} maxPackageBytes={}",
                    deliveryId, taskId, entity.entityCode(), entity.vendorCode(), plan.slot().packageSeq(), sourceIds,
                    plan.sources().stream().mapToLong(SourceData::sizeBytes).sum(), maxPackageBytes);
        }
        int sourceCount = registered.size();
        transactions.executeWithoutResult(status -> {
            long totalBytes = registered.values().stream().mapToLong(SourceData::sizeBytes).sum();
            jdbc.update("UPDATE ingest_source SET previous_refresh_bytes=latest_refresh_bytes,latest_refresh_bytes=? WHERE source_id=?",
                    totalBytes, submit.sourceId());
            deliveries.markDiscoveryCompleted(deliveryId);
            projections.refresh(deliveryId);
        });
        if (tasks.byDelivery(deliveryId).size() != slots.size()) throw new IllegalStateException("Planning did not satisfy all delivery targets");
        log.info("Delivery preparation completed deliveryId={} sourceCount={} filteredOversizedCount={} taskCount={}",
                deliveryId, sourceCount, discovery.oversized().size(), planned.size());
        return sourceCount;
    }

    static List<PlannedTask> planTasks(List<SourceData> sources, List<TargetSlot> slots, long maxPackageBytes) {
        List<List<Integer>> assignments = planSourceIndexes(sources.stream().map(SourceData::sizeBytes).toList(), slots.size(), maxPackageBytes);
        List<PlannedTask> result = new ArrayList<>();
        for (int index = 0; index < assignments.size(); index++) {
            List<SourceData> group = assignments.get(index).stream().map(sources::get).toList();
            result.add(new PlannedTask(slots.get(index), group));
        }
        return List.copyOf(result);
    }

    /** 顺序装箱且不切分文件；最后一个目标无法容纳下一包时立即完成，避免把多余来源留在 PENDING。 */
    static List<List<Integer>> planSourceIndexes(List<Long> sizes, int slotCount, long maxPackageBytes) {
        if (slotCount <= 0 || maxPackageBytes <= 0) throw new IllegalArgumentException("Targets and maxPackageBytes are required");
        for (long size : sizes) {
            if (size <= 0 || size > maxPackageBytes) {
                throw new IllegalArgumentException("Oversized or empty source must be filtered before planning");
            }
        }
        List<List<Integer>> result = new ArrayList<>();
        int cursor = 0;
        for (int slotIndex = 0; slotIndex < slotCount; slotIndex++) {
            int remainingSlots = slotCount - slotIndex - 1;
            List<Integer> group = new ArrayList<>();
            long groupBytes = 0;
            while (cursor < sizes.size() - remainingSlots) {
                long candidate = sizes.get(cursor);
                if (!group.isEmpty() && groupBytes + candidate > maxPackageBytes) break;
                group.add(cursor++);
                groupBytes += candidate;
            }
            if (group.isEmpty()) {
                throw new IllegalArgumentException("Source OSS does not contain enough non-empty packages for all delivery targets");
            }
            result.add(List.copyOf(group));
            if (result.size() == slotCount) return List.copyOf(result);
        }
        return List.copyOf(result);
    }

    private SourceData register(long deliveryId, OssNodeConfig config, OSSObjectSummary object, int sequence) {
        OSS client = clients.create(config);
        try {
            String fileName = Path.of(object.getKey()).getFileName().toString();
            String etag = object.getETag() == null ? "" : object.getETag().replace("\"", "");
            String version = !etag.isBlank() ? etag : object.getLastModified().toInstant().toEpochMilli() + "-" + object.getSize();
            String identity = "oss://" + config.bucket() + "/" + object.getKey() + "#" + version;
            Path download = Path.of(properties.getWorkRoot(), "source-cache",
                    UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString(), fileName);
            String sha = download(client, config, object, download);
            DiscoveredObject discovered = new DiscoveredObject(config.bucket(), object.getKey(), version, etag,
                    object.getSize(), sha, download.toAbsolutePath().toString());
            SourceData source = sources.findById(sources.insertDiscovered(deliveryId, discovered).dataId()).orElseThrow();
            log.info("Source registered deliveryId={} dataId={} sequence={} uri={} sizeBytes={} sha256={} localPath={}",
                    deliveryId, source.dataId(), sequence, source.sourceUri(), source.sizeBytes(), source.sha256(), download);
            return source;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Source OSS discovery/download failed", e);
        } finally {
            client.shutdown();
        }
    }

    private Discovery discover(OssNodeConfig config, int limit, long deliveryId, long maxPackageBytes) {
        OSS client = clients.create(config);
        List<OSSObjectSummary> selected = new ArrayList<>();
        List<OSSObjectSummary> oversized = new ArrayList<>();
        try {
            String continuation = null;
            do {
                ListObjectsV2Result page = client.listObjectsV2(new ListObjectsV2Request(config.bucket())
                        .withPrefix(config.prefix()).withMaxKeys(Math.max(100, limit)).withContinuationToken(continuation));
                for (OSSObjectSummary object : page.getObjectSummaries()) {
                    if (!object.getKey().endsWith(".tar.gz") || object.getSize() <= 0) continue;
                    if (object.getSize() > maxPackageBytes) {
                        oversized.add(object);
                        log.warn("Source filtered before download because it exceeds maxPackageBytes deliveryId={} key={} sizeBytes={} maxPackageBytes={}",
                                deliveryId, object.getKey(), object.getSize(), maxPackageBytes);
                    } else if (selected.size() < limit) {
                        selected.add(object);
                    }
                }
                continuation = page.getNextContinuationToken();
            } while (selected.size() < limit && continuation != null && !continuation.isBlank());
            selected.sort(Comparator.comparing(OSSObjectSummary::getKey));
            oversized.sort(Comparator.comparing(OSSObjectSummary::getKey));
            return new Discovery(List.copyOf(selected), List.copyOf(oversized));
        } finally {
            client.shutdown();
        }
    }

    private String download(OSS client, OssNodeConfig config, OSSObjectSummary object, Path destination) throws Exception {
        if (Files.isRegularFile(destination) && Files.size(destination) == object.getSize()) {
            String sha = files.sha256(destination);
            log.info("Source cache reused key={} localPath={} sizeBytes={} sha256={}", object.getKey(), destination, object.getSize(), sha);
            return sha;
        }
        try (var input = client.getObject(config.bucket(), object.getKey()).getObjectContent()) {
            files.copyStream(input, destination);
        }
        if (Files.size(destination) != object.getSize()) throw new IllegalStateException("Discovered source size mismatch");
        return files.sha256(destination);
    }

    private List<Target> targets(com.fasterxml.jackson.databind.JsonNode values) {
        List<Target> result = new ArrayList<>();
        values.forEach(target -> result.add(new Target(
                deliveries.entityByBusinessCode(target.required("vendorCode").asText(), target.required("entityCode").asText()),
                target.required("targetCount").asInt())));
        return List.copyOf(result);
    }

    private static List<TargetSlot> targetSlots(List<Target> targets) {
        Map<String, List<Target>> vendorTargets = new LinkedHashMap<>();
        for (Target target : targets) vendorTargets.computeIfAbsent(target.entity().vendorCode(), key -> new ArrayList<>()).add(target);
        List<TargetSlot> result = new ArrayList<>();
        int packageSeq = 1;
        for (List<Target> sameVendor : vendorTargets.values()) {
            for (Target target : sameVendor) {
                for (int targetSeq = 1; targetSeq <= target.targetCount(); targetSeq++) {
                    result.add(new TargetSlot(target.entity().deliveryEntityId(), targetSeq, packageSeq++));
                }
            }
        }
        return List.copyOf(result);
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

    private record Target(DeliveryEntity entity, int targetCount) {}
    record TargetSlot(long deliveryEntityId, int targetSeq, int packageSeq) {
        TargetSlot(long deliveryEntityId, int targetSeq) { this(deliveryEntityId, targetSeq, targetSeq); }
    }
    record PlannedTask(TargetSlot slot, List<SourceData> sources) {}
    private record Discovery(List<OSSObjectSummary> objects, List<OSSObjectSummary> oversized) {}
}
