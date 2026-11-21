package com.powerjob.archive.core.service;

import com.powerjob.archive.core.domain.DeliveryEntity;
import com.powerjob.archive.core.domain.DeliverySubmit;
import com.powerjob.archive.core.pipeline.PipelineDefinition;
import com.powerjob.archive.core.repository.DeliveryRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class BatchPlanningService {
    private final DeliveryRepository deliveries;

    public BatchPlanningService(DeliveryRepository deliveries) {
        this.deliveries = deliveries;
    }

    public Limits limits(long deliveryId) {
        DeliverySubmit submit = deliveries.find(deliveryId).orElseThrow();
        var extension = submit.batchExtension();
        if (extension == null || !extension.hasNonNull("maxPackageBytes")) {
            throw new IllegalStateException("Delivery batch package limit is missing");
        }
        return new Limits(extension.required("maxPackageBytes").asLong());
    }

    public static Limits limits(PipelineDefinition pipeline) {
        Object assignment = pipeline.config().get("assignment");
        if (!(assignment instanceof Map<?, ?> map)) throw new IllegalArgumentException("Batch assignment is required");
        long maximum = number(map.get("maxPackageBytes"), "maxPackageBytes");
        if (maximum <= 0) throw new IllegalArgumentException("Invalid batch package byte limit");
        return new Limits(maximum);
    }

    public static List<EntityWorker> entityWorkers(PipelineDefinition pipeline, DeliveryRepository deliveries) {
        Object assignment = pipeline.config().get("assignment");
        Object configured = assignment instanceof Map<?, ?> map ? map.get("entityWorkers") : null;
        if (!(configured instanceof List<?> mappings) || mappings.isEmpty()) {
            throw new IllegalArgumentException("archive-batch config.assignment.entityWorkers is required");
        }
        List<EntityWorker> result = new ArrayList<>();
        for (Object value : mappings) {
            if (!(value instanceof Map<?, ?> mapping)) throw new IllegalArgumentException("Invalid entityWorkers mapping");
            String vendor = String.valueOf(mapping.get("vendorCode"));
            String entityCode = String.valueOf(mapping.get("entityCode"));
            DeliveryEntity entity = deliveries.entityByBusinessCode(vendor, entityCode);
            if (!entity.enabled()) throw new IllegalArgumentException("Delivery entity is disabled: " + entityCode);
            if (!(mapping.get("workers") instanceof List<?> workers) || workers.isEmpty()) {
                throw new IllegalArgumentException("Entity workers are required: " + entityCode);
            }
            for (Object item : workers) {
                if (!(item instanceof Map<?, ?> worker)) throw new IllegalArgumentException("Invalid entity worker");
                String group = String.valueOf(worker.get("workerGroup"));
                int capacity = Math.toIntExact(number(worker.get("maxPackagesPerRound"), "maxPackagesPerRound"));
                if (capacity <= 0) throw new IllegalArgumentException("maxPackagesPerRound must be positive");
                result.add(new EntityWorker(entity, group, capacity));
            }
        }
        return List.copyOf(result);
    }

    public static List<PlanningSlot> slots(int packageCount, List<EntityWorker> workers) {
        List<PlanningSlot> result = new ArrayList<>(packageCount);
        int round = 0;
        while (result.size() < packageCount) {
            for (EntityWorker worker : workers) {
                for (int sequence = 0; sequence < worker.maxPackagesPerRound() && result.size() < packageCount; sequence++) {
                    result.add(new PlanningSlot(worker.entity(), worker.workerGroup(), round));
                }
            }
            round++;
        }
        return List.copyOf(result);
    }

    public static List<List<Integer>> planPackages(List<Long> sizes, Limits limits, long sourceMaxPackageBytes) {
        if (sizes.isEmpty()) throw new IllegalArgumentException("No deliverable OSS source packages");
        if (limits.maxPackageBytes() <= 0 || sourceMaxPackageBytes < limits.maxPackageBytes()) {
            throw new IllegalArgumentException("Source maximum must not be below the package planning target");
        }
        // 按固定源顺序贪心安整包装箱；只受上限约束，非空尾包也保留为独立交付包。
        List<List<Integer>> result = new ArrayList<>();
        List<Integer> group = new ArrayList<>();
        long groupBytes = 0;
        for (int sourceIndex = 0; sourceIndex < sizes.size(); sourceIndex++) {
            long sourceBytes = sizes.get(sourceIndex);
            if (sourceBytes <= 0 || sourceBytes > sourceMaxPackageBytes) {
                throw new IllegalArgumentException("Source package size " + sourceBytes + " is empty or exceeds source maximum "
                        + sourceMaxPackageBytes);
            }
            if (!group.isEmpty() && groupBytes + sourceBytes > limits.maxPackageBytes()) {
                result.add(List.copyOf(group));
                group.clear();
                groupBytes = 0;
            }
            group.add(sourceIndex);
            groupBytes += sourceBytes;
        }
        if (!group.isEmpty()) result.add(List.copyOf(group));
        return List.copyOf(result);
    }

    private static long number(Object value, String name) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException(name + " must be a number");
        return number.longValue();
    }

    public record Limits(long maxPackageBytes) {}
    public record EntityWorker(DeliveryEntity entity, String workerGroup, int maxPackagesPerRound) {}
    public record PlanningSlot(DeliveryEntity entity, String workerGroup, int round) {}
}
