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

    /** targetCount 展开为任务槽位；持续分页扫描到全部槽位可装包，超限包过滤且不设置额外来源条数上限。 */
    public int prepare(long deliveryId) {
        DeliverySubmit submit = deliveries.find(deliveryId).orElseThrow();
        if (submit.discoveryCompletedAt() != null) return tasks.byDelivery(deliveryId).size();
        deliveries.markDiscovering(deliveryId);
        PipelineDefinition pipeline = pipeline(submit.pipelineVersionId());
        long maxPackageBytes = pipeline.maxPackageBytes();
        long minPackageBytes = pipeline.minPackageBytes();
        List<Target> targets = targets(submit.targetStrategy().required("targets"));
        List<TargetSlot> slots = targetSlots(targets);
        OssNodeConfig config = sourceConfig(pipeline);
        verifySourceLocation(submit.sourceId(), config);
        log.info("Delivery preparation started deliveryId={} targetTaskCount={} minPackageBytes={} maxPackageBytes={} sourcePrefix=oss://{}/{}",
                deliveryId, slots.size(), minPackageBytes, maxPackageBytes, config.bucket(), config.prefix());
        Discovery discovery = discover(config, deliveryId, slots.size(), maxPackageBytes, minPackageBytes);
        List<List<Integer>> assignments = discovery.assignments();
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
                    .put("minPackageBytes", minPackageBytes).put("maxPackageBytes", maxPackageBytes);
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
        return planTasks(sources, slots, maxPackageBytes, 1);
    }

    static List<PlannedTask> planTasks(List<SourceData> sources, List<TargetSlot> slots, long maxPackageBytes, long minPackageBytes) {
        List<List<Integer>> assignments = planSourceIndexes(sources.stream().map(SourceData::sizeBytes).toList(),
                slots.size(), maxPackageBytes, minPackageBytes);
        List<PlannedTask> result = new ArrayList<>();
        for (int index = 0; index < assignments.size(); index++) {
            List<SourceData> group = assignments.get(index).stream().map(sources::get).toList();
            result.add(new PlannedTask(slots.get(index), group));
        }
        return List.copyOf(result);
    }

    static List<List<Integer>> planSourceIndexes(List<Long> sizes, int slotCount, long maxPackageBytes) {
        return planSourceIndexes(sizes, slotCount, maxPackageBytes, 1);
    }

    /** 顺序装箱且不切分文件；有限回溯调整相邻包边界，使全部目标满足下限后才允许交付。 */
    static List<List<Integer>> planSourceIndexes(List<Long> sizes, int slotCount, long maxPackageBytes, long minPackageBytes) {
        if (slotCount <= 0 || maxPackageBytes <= 0 || minPackageBytes <= 0 || minPackageBytes > maxPackageBytes) {
            throw new IllegalArgumentException("Targets and package byte bounds are required");
        }
        for (long size : sizes) {
            if (size <= 0 || size > maxPackageBytes) {
                throw new IllegalArgumentException("Oversized or empty source must be filtered before planning");
            }
        }
        List<List<Integer>> contiguous = planContiguous(sizes, slotCount, maxPackageBytes, minPackageBytes);
        if (contiguous != null) return contiguous;
        OrderedPackagePlanner planner = new OrderedPackagePlanner(sizes, slotCount, maxPackageBytes, minPackageBytes);
        List<List<Integer>> result = planner.plan(0, planner.allIndexes());
        if (result == null) {
            throw new IllegalArgumentException("Source package is below minPackageBytes without splitting: "
                    + planner.bestBelowMin + "<" + minPackageBytes);
        }
        return result;
    }

    private static List<List<Integer>> planContiguous(List<Long> sizes, int slotCount, long maxPackageBytes, long minPackageBytes) {
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
            if (groupBytes < minPackageBytes) return null;
            result.add(List.copyOf(group));
        }
        return result;
    }

    private static final class OrderedPackagePlanner {
        private final List<Long> sizes;
        private final int slotCount;
        private final long maxPackageBytes;
        private final long minPackageBytes;
        private long bestBelowMin;

        private OrderedPackagePlanner(List<Long> sizes, int slotCount, long maxPackageBytes, long minPackageBytes) {
            this.sizes = sizes;
            this.slotCount = slotCount;
            this.maxPackageBytes = maxPackageBytes;
            this.minPackageBytes = minPackageBytes;
        }

        private List<Integer> allIndexes() {
            return java.util.stream.IntStream.range(0, sizes.size()).boxed().toList();
        }

        private List<List<Integer>> plan(int slotIndex, List<Integer> remaining) {
            List<List<Integer>> candidates = candidates(remaining);
            if (slotIndex == slotCount - 1) {
                return candidates.isEmpty() ? null : List.of(candidates.get(0));
            }
            for (List<Integer> group : candidates) {
                java.util.Set<Integer> selected = new java.util.HashSet<>(group);
                List<Integer> next = remaining.stream().filter(index -> !selected.contains(index)).toList();
                List<List<Integer>> tail = plan(slotIndex + 1, next);
                if (tail != null) {
                    List<List<Integer>> result = new ArrayList<>();
                    result.add(group);
                    result.addAll(tail);
                    return result;
                }
            }
            return null;
        }

        private List<List<Integer>> candidates(List<Integer> remaining) {
            Map<Long, List<Integer>> byBytes = new HashMap<>();
            byBytes.put(0L, List.of());
            for (int index : remaining) {
                for (Map.Entry<Long, List<Integer>> entry : new ArrayList<>(byBytes.entrySet())) {
                    long nextBytes = entry.getKey() + sizes.get(index);
                    if (nextBytes > maxPackageBytes) continue;
                    byBytes.computeIfAbsent(nextBytes, key -> {
                        List<Integer> group = new ArrayList<>(entry.getValue());
                        group.add(index);
                        return group;
                    });
                }
            }
            bestBelowMin = Math.max(bestBelowMin, byBytes.keySet().stream()
                    .filter(bytes -> bytes < minPackageBytes).mapToLong(Long::longValue).max().orElse(0));
            return byBytes.entrySet().stream().filter(entry -> entry.getKey() >= minPackageBytes)
                    .sorted(Map.Entry.comparingByKey(java.util.Comparator.reverseOrder()))
                    .map(Map.Entry::getValue).toList();
        }
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

    private Discovery discover(OssNodeConfig config, long deliveryId, int slotCount, long maxPackageBytes, long minPackageBytes) {
        OSS client = clients.create(config);
        List<OSSObjectSummary> selected = new ArrayList<>();
        List<OSSObjectSummary> oversized = new ArrayList<>();
        List<List<Integer>> assignments = null;
        try {
            String continuation = null;
            do {
                // OSS 页大小只控制列表请求，不再截断可用于装包的来源集合。
                ListObjectsV2Result page = client.listObjectsV2(new ListObjectsV2Request(config.bucket())
                        .withPrefix(config.prefix()).withMaxKeys(100).withContinuationToken(continuation));
                for (OSSObjectSummary object : page.getObjectSummaries()) {
                    if (!object.getKey().endsWith(".tar.gz") || object.getSize() <= 0) continue;
                    if (object.getSize() > maxPackageBytes) {
                        oversized.add(object);
                        log.warn("Source filtered before download because it exceeds maxPackageBytes deliveryId={} key={} sizeBytes={} maxPackageBytes={}",
                                deliveryId, object.getKey(), object.getSize(), maxPackageBytes);
                    } else {
                        selected.add(object);
                    }
                }
                // 每页按 key 固定顺序后尝试满足全部 targetCount 槽位，成功即停止扫描和后续下载。
                selected.sort(Comparator.comparing(OSSObjectSummary::getKey));
                assignments = planOrNull(selected.stream().map(OSSObjectSummary::getSize).toList(),
                        slotCount, maxPackageBytes, minPackageBytes);
                continuation = page.getNextContinuationToken();
            } while (assignments == null && continuation != null && !continuation.isBlank());
            oversized.sort(Comparator.comparing(OSSObjectSummary::getKey));
            if (assignments == null) {
                // 列表耗尽时复用规划器的明确下限错误，不把来源不足误报为配置错误。
                assignments = planSourceIndexes(selected.stream().map(OSSObjectSummary::getSize).toList(),
                        slotCount, maxPackageBytes, minPackageBytes);
            }
            return new Discovery(List.copyOf(selected), List.copyOf(oversized), assignments);
        } finally {
            client.shutdown();
        }
    }

    private static List<List<Integer>> planOrNull(List<Long> sizes, int slotCount, long maxPackageBytes, long minPackageBytes) {
        try {
            return planSourceIndexes(sizes, slotCount, maxPackageBytes, minPackageBytes);
        } catch (IllegalArgumentException insufficientSources) {
            return null;
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
    private record Discovery(List<OSSObjectSummary> objects, List<OSSObjectSummary> oversized,
                             List<List<Integer>> assignments) {}
}
