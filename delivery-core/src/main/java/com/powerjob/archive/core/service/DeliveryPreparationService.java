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
import com.powerjob.archive.core.domain.DeliveryTask;
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
        long minPackageBytes = sourceMinPackageBytes(pipeline);
        List<Target> targets = targets(submit.targetStrategy().required("targets"));
        List<DeliveryTask> existingTasks = tasks.byDelivery(deliveryId);
        List<TargetSlot> slots = targetSlots(targets, existingTasks);
        OssNodeConfig config = sourceConfig(pipeline);
        SourceLocation location = sourceLocation(submit.sourceId());
        if (slots.isEmpty()) {
            transactions.executeWithoutResult(status -> {
                deliveries.markDiscoveryCompleted(deliveryId);
                projections.refresh(deliveryId);
            });
            return 0;
        }
        java.util.Set<String> usedSources = new java.util.HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT sd.original_locator->>'sourceUri' FROM source_data sd " +
                        "JOIN delivery_mapping mapping ON mapping.data_id=sd.data_id AND mapping.delivery_id=sd.delivery_id " +
                        "WHERE sd.delivery_id=? AND sd.qualification<>'FILTERED'",
                String.class, deliveryId));
        log.info("Delivery preparation started deliveryId={} newTaskCount={} cumulativeTaskCount={} minPackageBytes={} maxPackageBytes={} sourcePrefix=oss://{}/{} excludedSources={}",
                deliveryId, slots.size(), targets.stream().mapToInt(Target::targetCount).sum(), minPackageBytes, maxPackageBytes,
                location.bucket(), location.prefix(), usedSources.size());
        Discovery discovery = discover(config, location, deliveryId, slots.size(), maxPackageBytes, minPackageBytes, usedSources);
        List<List<Integer>> assignments = discovery.assignments();
        if (assignments.size() != slots.size() || assignments.stream().anyMatch(List::isEmpty)) {
            throw new IllegalStateException("Planner returned an empty or incomplete target assignment");
        }
        for (OSSObjectSummary skipped : discovery.oversized()) {
            String sourceUri = "oss://" + location.bucket() + "/" + skipped.getKey();
            ObjectNode payload = mapper.createObjectNode().put("sourceUri", sourceUri)
                    .put("sizeBytes", skipped.getSize()).put("maxPackageBytes", maxPackageBytes);
            String dedup = "source-filtered-oversized-" + UUID.nameUUIDFromBytes(sourceUri.getBytes(StandardCharsets.UTF_8));
            events.append(deliveryId, null, null, null, null, "SOURCE_FILTERED", "WARN",
                    "PACKAGE_TOO_LARGE", "Source package exceeds maxPackageBytes and was not downloaded", payload, null, dedup);
        }

        Map<Integer, SourceData> registered = new java.util.concurrent.ConcurrentHashMap<>();
        List<PlannedTask> planned = registerAndCreateTasks(deliveryId, submit, pipeline, config, location,
                discovery.objects(), assignments, slots, minPackageBytes, maxPackageBytes, registered);
        int sourceCount = registered.size();
        transactions.executeWithoutResult(status -> {
            long totalBytes = registered.values().stream().mapToLong(SourceData::sizeBytes).sum();
            jdbc.update("UPDATE ingest_source SET previous_refresh_bytes=latest_refresh_bytes,latest_refresh_bytes=? WHERE source_id=?",
                    totalBytes, submit.sourceId());
            deliveries.markDiscoveryCompleted(deliveryId);
            projections.refresh(deliveryId);
        });
        long activeTasks = tasks.byDelivery(deliveryId).stream()
                .filter(task -> task.executionStatus() != null && !List.of("EXPIRED", "CANCELLED").contains(task.executionStatus())).count();
        if (activeTasks != targets.stream().mapToInt(Target::targetCount).sum()) {
            throw new IllegalStateException("Planning did not satisfy all cumulative delivery targets");
        }
        log.info("Delivery preparation completed deliveryId={} sourceCount={} filteredOversizedCount={} taskCount={}",
                deliveryId, sourceCount, discovery.oversized().size(), planned.size());
        return sourceCount;
    }

    public List<Long> replanUnderfilled(long taskId, long expectedFence, String reason) {
        DeliveryTask task = tasks.findById(taskId).orElseThrow();
        if (!"BLOCKED".equals(task.executionStatus()) || !"qualify".equals(task.currentNodeId()) || task.fence() != expectedFence) {
            throw new ResumeConflictException("Only the expected BLOCKED qualify task can be replanned");
        }
        DeliverySubmit submit = deliveries.find(task.deliveryId()).orElseThrow();
        PipelineDefinition pipeline = pipeline(submit.pipelineVersionId());
        List<SourceData> existing = sources.byTaskId(taskId);
        long existingBytes = existing.stream().mapToLong(SourceData::sizeBytes).sum();
        long required = sourceMinPackageBytes(pipeline) - existingBytes;
        long capacity = pipeline.maxPackageBytes() - existingBytes;
        if (required <= 0 || required > capacity) throw new IllegalArgumentException("Task cannot be expanded within source package bounds");
        java.util.Set<String> usedSources = new java.util.HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT sd.original_locator->>'sourceUri' FROM source_data sd " +
                        "JOIN delivery_mapping mapping ON mapping.data_id=sd.data_id AND mapping.delivery_id=sd.delivery_id " +
                        "WHERE sd.delivery_id=? AND sd.qualification<>'FILTERED'", String.class, task.deliveryId()));
        Discovery discovery = discover(sourceConfig(pipeline), sourceLocation(submit.sourceId()), task.deliveryId(), 1,
                capacity, required, usedSources);
        Map<Integer, SourceData> registered = registerAssigned(task.deliveryId(), sourceConfig(pipeline),
                sourceLocation(submit.sourceId()), discovery.objects(), discovery.assignments());
        List<SourceData> additions = discovery.assignments().get(0).stream().map(registered::get).toList();
        List<Long> ids = transactions.execute(status -> {
            List<Long> added = tasks.appendSourcesForReplan(taskId, expectedFence, additions, pipeline.firstNode());
            events.append(task.deliveryId(), taskId, null, task.deliveryEntityId(), existing.get(0).dataId(), "TASK_REPLANNED",
                    "INFO", "UNDERFILLED_AFTER_REPAIR", reason, mapper.createObjectNode().put("taskId", taskId)
                            .put("expectedFence", expectedFence).put("previousSourceBytes", existingBytes)
                            .put("additionalSourceBytes", additions.stream().mapToLong(SourceData::sizeBytes).sum())
                            .set("addedDataIds", mapper.valueToTree(added)), "api", "replan-" + taskId + "-" + expectedFence);
            projections.refresh(task.deliveryId());
            return added;
        });
        log.info("Underfilled task replanned deliveryId={} taskId={} addedDataIds={} previousSourceBytes={} additionalSourceBytes={}",
                task.deliveryId(), taskId, ids, existingBytes, additions.stream().mapToLong(SourceData::sizeBytes).sum());
        return ids;
    }

    static long sourceMinPackageBytes(PipelineDefinition pipeline) {
        long minimum = pipeline.minPackageBytes();
        return Math.addExact(minimum, (minimum + 3) / 4);
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

    /** 优先连续装箱；尺寸分布不满足时按降序平衡各槽位，不切分文件且所有目标达到下限后才交付。 */
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
        List<List<Integer>> result = planWithMinimums(sizes, slotCount, maxPackageBytes, minPackageBytes);
        if (result == null) {
            long available = Math.min(sizes.stream().mapToLong(Long::longValue).sum(), minPackageBytes - 1);
            throw new IllegalArgumentException("Source package is below minPackageBytes without splitting: "
                    + available + "<" + minPackageBytes);
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

    private static List<List<Integer>> planWithMinimums(List<Long> sizes, int slotCount, long maxPackageBytes, long minPackageBytes) {
        List<Integer> ordered = java.util.stream.IntStream.range(0, sizes.size()).boxed()
                .sorted(java.util.Comparator.<Integer>comparingLong(sizes::get).reversed()).toList();
        java.util.PriorityQueue<BalancedBin> queue = new java.util.PriorityQueue<>(
                java.util.Comparator.comparingLong(BalancedBin::bytes).thenComparing(BalancedBin::id));
        List<BalancedBin> bins = new ArrayList<>();
        for (int sourceIndex : ordered) {
            BalancedBin bin;
            if (bins.size() < slotCount) {
                bin = new BalancedBin(bins.size());
                bins.add(bin);
            } else {
                bin = queue.poll();
            }
            long sourceBytes = sizes.get(sourceIndex);
            if (bin.bytes + sourceBytes > maxPackageBytes) {
                queue.add(bin);
                continue;
            }
            bin.add(sourceIndex, sourceBytes);
            queue.add(bin);
        }
        if (bins.size() == slotCount && bins.stream().allMatch(item -> item.bytes >= minPackageBytes)) {
            return bins.stream().map(item -> List.copyOf(item.sources)).toList();
        }
        return null;
    }

    private static final class BalancedBin {
        private final int id;
        private final List<Integer> sources = new ArrayList<>();
        private long bytes;

        private BalancedBin(int id) { this.id = id; }
        private int id() { return id; }
        private long bytes() { return bytes; }
        private void add(int sourceIndex, long sourceBytes) {
            sources.add(sourceIndex);
            bytes += sourceBytes;
        }
    }

    private List<PlannedTask> registerAndCreateTasks(long deliveryId, DeliverySubmit submit, PipelineDefinition pipeline,
                                                      OssNodeConfig config, SourceLocation location, List<OSSObjectSummary> objects,
                                                      List<List<Integer>> assignments, List<TargetSlot> slots, long minPackageBytes,
                                                      long maxPackageBytes, Map<Integer, SourceData> registered) {
        int threads = Math.max(1, Math.min(properties.getPreparationDownloadThreads(), slots.size()));
        log.info("Task preparation parallelism deliveryId={} tasks={} threads={}", deliveryId, slots.size(), threads);
        List<PlannedTask> planned = new ArrayList<>();
        if (threads == 1) {
            for (int slotIndex = 0; slotIndex < slots.size(); slotIndex++) {
                PlannedTask plan = plannedTask(deliveryId, config, location, objects, assignments.get(slotIndex), slots.get(slotIndex), registered);
                createPlannedTask(deliveryId, submit, pipeline, plan, minPackageBytes, maxPackageBytes);
                planned.add(plan);
            }
            return planned;
        }
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CompletionService<PlannedTask> completion = new java.util.concurrent.ExecutorCompletionService<>(executor);
        try {
            for (int slotIndex = 0; slotIndex < slots.size(); slotIndex++) {
                int current = slotIndex;
                completion.submit(() -> plannedTask(deliveryId, config, location, objects, assignments.get(current), slots.get(current), registered));
            }
            for (int slotIndex = 0; slotIndex < slots.size(); slotIndex++) {
                PlannedTask plan = completion.take().get();
                createPlannedTask(deliveryId, submit, pipeline, plan, minPackageBytes, maxPackageBytes);
                planned.add(plan);
            }
            return planned;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Task preparation was interrupted", error);
        } catch (java.util.concurrent.ExecutionException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Task preparation failed", error.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    private PlannedTask plannedTask(long deliveryId, OssNodeConfig config, SourceLocation location,
                                    List<OSSObjectSummary> objects, List<Integer> sourceIndexes, TargetSlot slot,
                                    Map<Integer, SourceData> registered) {
        List<SourceData> taskSources = new ArrayList<>();
        for (int objectIndex : sourceIndexes) {
            taskSources.add(registered.computeIfAbsent(objectIndex,
                    index -> register(deliveryId, config, location, objects.get(index), index + 1)));
        }
        return new PlannedTask(slot, List.copyOf(taskSources));
    }

    private void createPlannedTask(long deliveryId, DeliverySubmit submit, PipelineDefinition pipeline, PlannedTask plan,
                                   long minPackageBytes, long maxPackageBytes) {
        DeliveryEntity entity = deliveries.entity(plan.slot().deliveryEntityId());
        long taskId = tasks.createPlanned(submit, plan.sources(), entity, plan.slot().packageSeq(), pipeline.firstNode());
        ObjectNode payload = mapper.createObjectNode().put("taskId", taskId)
                .put("deliveryEntityId", entity.deliveryEntityId()).put("entityCode", entity.entityCode())
                .put("vendorCode", entity.vendorCode()).put("workerGroup", entity.workerGroup())
                .put("packageSeq", plan.slot().packageSeq()).put("sourceCount", plan.sources().size())
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

    private Map<Integer, SourceData> registerAssigned(long deliveryId, OssNodeConfig config, SourceLocation location,
                                                       List<OSSObjectSummary> objects, List<List<Integer>> assignments) {
        List<Integer> indexes = assignments.stream().flatMap(List::stream).distinct().toList();
        int threads = Math.max(1, Math.min(properties.getPreparationDownloadThreads(), indexes.size()));
        log.info("Source registration parallelism deliveryId={} sources={} threads={}", deliveryId, indexes.size(), threads);
        if (threads == 1) {
            Map<Integer, SourceData> result = new HashMap<>();
            for (int index : indexes) result.put(index, register(deliveryId, config, location, objects.get(index), index + 1));
            return result;
        }
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Callable<Map.Entry<Integer, SourceData>>> work = indexes.stream()
                    .<java.util.concurrent.Callable<Map.Entry<Integer, SourceData>>>map(index -> () ->
                            Map.entry(index, register(deliveryId, config, location, objects.get(index), index + 1))).toList();
            Map<Integer, SourceData> result = new HashMap<>();
            for (var future : executor.invokeAll(work)) result.put(future.get().getKey(), future.get().getValue());
            return result;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Source registration was interrupted", error);
        } catch (java.util.concurrent.ExecutionException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Source registration failed", error.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    private SourceData register(long deliveryId, OssNodeConfig config, SourceLocation location, OSSObjectSummary object, int sequence) {
        OSS client = clients.create(config);
        try {
            String fileName = Path.of(object.getKey()).getFileName().toString();
            String etag = object.getETag() == null ? "" : object.getETag().replace("\"", "");
            String version = !etag.isBlank() ? etag : object.getLastModified().toInstant().toEpochMilli() + "-" + object.getSize();
            String identity = "oss://" + location.bucket() + "/" + object.getKey() + "#" + version;
            Path download = Path.of(properties.getWorkRoot(), "source-cache",
                    UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString(), fileName);
            String sha = download(client, location, object, download);
            DiscoveredObject discovered = new DiscoveredObject(location.bucket(), object.getKey(), version, etag,
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

    private Discovery discover(OssNodeConfig config, SourceLocation location, long deliveryId, int slotCount,
                               long maxPackageBytes, long minPackageBytes, java.util.Set<String> usedSources) {
        OSS client = clients.create(config);
        List<OSSObjectSummary> selected = new ArrayList<>();
        List<OSSObjectSummary> oversized = new ArrayList<>();
        List<List<Integer>> assignments = null;
        try {
            String continuation = null;
            do {
                ListObjectsV2Result page = client.listObjectsV2(new ListObjectsV2Request(location.bucket())
                        .withPrefix(location.prefix()).withMaxKeys(1000).withContinuationToken(continuation));
                for (OSSObjectSummary object : page.getObjectSummaries()) {
                    if (!object.getKey().endsWith(".tar.gz") || object.getSize() <= 0
                            || usedSources.contains("oss://" + location.bucket() + "/" + object.getKey())) continue;
                    if (object.getSize() > maxPackageBytes) {
                        oversized.add(object);
                        log.warn("Source filtered before download because it exceeds maxPackageBytes deliveryId={} key={} sizeBytes={} maxPackageBytes={}",
                                deliveryId, object.getKey(), object.getSize(), maxPackageBytes);
                    } else {
                        selected.add(object);
                    }
                }
                selected.sort(Comparator.comparing(OSSObjectSummary::getKey));
                assignments = planOrNull(selected.stream().map(OSSObjectSummary::getSize).toList(),
                        slotCount, maxPackageBytes, minPackageBytes);
                continuation = page.getNextContinuationToken();
            } while (assignments == null && continuation != null && !continuation.isBlank());
            oversized.sort(Comparator.comparing(OSSObjectSummary::getKey));
            if (assignments == null) {
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

    private String download(OSS client, SourceLocation location, OSSObjectSummary object, Path destination) throws Exception {
        if (Files.isRegularFile(destination) && Files.size(destination) == object.getSize()) {
            String sha = files.sha256(destination);
            log.info("Source cache reused key={} localPath={} sizeBytes={} sha256={}", object.getKey(), destination, object.getSize(), sha);
            return sha;
        }
        try (var input = client.getObject(location.bucket(), object.getKey()).getObjectContent()) {
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

    private static List<TargetSlot> targetSlots(List<Target> targets, List<DeliveryTask> existingTasks) {
        Map<String, List<Target>> vendorTargets = new LinkedHashMap<>();
        for (Target target : targets) vendorTargets.computeIfAbsent(target.entity().vendorCode(), key -> new ArrayList<>()).add(target);
        Map<Long, Integer> existingByEntity = new HashMap<>();
        int packageSeq = 1;
        for (DeliveryTask task : existingTasks) {
            packageSeq = Math.max(packageSeq, Integer.parseInt(task.newFileName()
                    .replaceFirst("^batch_", "").replaceFirst("\\.tar\\.gz$", "")) + 1);
            if (!List.of("EXPIRED", "CANCELLED").contains(task.executionStatus())) {
                existingByEntity.merge(task.deliveryEntityId(), 1, Integer::sum);
            }
        }
        List<TargetSlot> result = new ArrayList<>();
        for (List<Target> sameVendor : vendorTargets.values()) {
            for (Target target : sameVendor) {
                int existing = existingByEntity.getOrDefault(target.entity().deliveryEntityId(), 0);
                for (int targetSeq = existing + 1; targetSeq <= target.targetCount(); targetSeq++) {
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

    private SourceLocation sourceLocation(long sourceId) {
        URI location = URI.create(jdbc.queryForObject("SELECT oss_uri FROM ingest_source WHERE source_id=?", String.class, sourceId));
        if (!"oss".equals(location.getScheme()) || location.getHost() == null || location.getUserInfo() != null
                || location.getQuery() != null || location.getFragment() != null) {
            throw new IllegalArgumentException("ingest_source must contain an unsigned oss://bucket/prefix URI");
        }
        String prefix = location.getPath() == null ? "" : location.getPath().replaceFirst("^/", "");
        return new SourceLocation(location.getHost(), prefix);
    }

    private record SourceLocation(String bucket, String prefix) {}
    private record Target(DeliveryEntity entity, int targetCount) {}
    record TargetSlot(long deliveryEntityId, int targetSeq, int packageSeq) {
        TargetSlot(long deliveryEntityId, int targetSeq) { this(deliveryEntityId, targetSeq, targetSeq); }
    }
    record PlannedTask(TargetSlot slot, List<SourceData> sources) {}
    private record Discovery(List<OSSObjectSummary> objects, List<OSSObjectSummary> oversized,
                             List<List<Integer>> assignments) {}
}
