package com.powerjob.archive.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.domain.BatchPackage;
import com.powerjob.archive.core.domain.DeliveryEntity;
import com.powerjob.archive.core.domain.DeliverySubmit;
import com.powerjob.archive.core.domain.DeliveryTask;
import com.powerjob.archive.core.pipeline.PipelineDefinition;
import com.powerjob.archive.core.pipeline.PipelineRepository;
import com.powerjob.archive.core.repository.DeliveryBatchRepository;
import com.powerjob.archive.core.repository.DeliveryEventRepository;
import com.powerjob.archive.core.repository.DeliveryRepository;
import com.powerjob.archive.core.repository.DeliveryTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
public class DeliveryPreparationService {
    private static final Logger log = LoggerFactory.getLogger(DeliveryPreparationService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final DeliveryRepository deliveries;
    private final DeliveryBatchRepository batches;
    private final DeliveryTaskRepository tasks;
    private final DeliveryEventRepository events;
    private final DeliveryProjectionService projections;
    private final PipelineRepository pipelines;

    public DeliveryPreparationService(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper,
                                      DeliveryRepository deliveries, DeliveryBatchRepository batches, DeliveryTaskRepository tasks,
                                      DeliveryEventRepository events, DeliveryProjectionService projections, PipelineRepository pipelines) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
        this.deliveries = deliveries;
        this.batches = batches;
        this.tasks = tasks;
        this.events = events;
        this.projections = projections;
        this.pipelines = pipelines;
    }

    public int prepare(long deliveryId) {
        DeliverySubmit submit = deliveries.find(deliveryId).orElseThrow();
        if (submit.batchCreation()) throw new IllegalArgumentException("Batch creation uses DeliveryBatchService");
        if (submit.discoveryCompletedAt() != null) return tasks.byDelivery(deliveryId).size();
        deliveries.markDiscovering(deliveryId);
        PipelineDefinition pipeline = pipeline(submit.pipelineVersionId());
        return transactions.execute(status -> {
            deliveries.findBatchCreationForUpdate(submit.deliveryBatch()).orElseThrow();
            int consumedSources = 0;
            for (var target : submit.targetStrategy().required("targets")) {
                DeliveryEntity entity = deliveries.entityByBusinessCode(target.required("vendorCode").asText(),
                        target.required("entityCode").asText());
                int existing = (int) tasks.byDelivery(deliveryId).stream().filter(task ->
                        task.deliveryEntityId() == entity.deliveryEntityId()
                                && !List.of("EXPIRED", "CANCELLED").contains(task.executionStatus())).count();
                int required = target.required("targetCount").asInt() - existing;
                if (required < 0) throw new IllegalStateException("Existing task count exceeds submission targetCount");
                List<BatchPackage> available = batches.availablePackages(submit.batchDeliveryId(), entity.deliveryEntityId(), required);
                if (available.size() != required) {
                    throw new IllegalArgumentException("Insufficient unassigned batch packages for " + entity.entityCode()
                            + ": required=" + required + ", available=" + available.size());
                }
                for (BatchPackage planned : available) {
                    long taskId = tasks.createPlanned(submit, planned, entity, pipeline.firstNode());
                    consumedSources += planned.sourceCount();
                    ObjectNode payload = mapper.createObjectNode().put("taskId", taskId)
                            .put("batchDeliveryId", submit.batchDeliveryId()).put("newFileName", planned.newFileName())
                            .put("entityCode", entity.entityCode()).put("workerGroup", planned.workerGroup())
                            .put("sourceCount", planned.sourceCount()).put("sourceBytes", planned.sourceBytes());
                    events.append(deliveryId, taskId, null, entity.deliveryEntityId(), null, "BATCH_PACKAGE_CONSUMED",
                            "INFO", null, "Pre-planned batch package assigned to delivery task", payload, null,
                            "batch-package-consumed-" + taskId);
                }
            }
            deliveries.markDiscoveryCompleted(deliveryId);
            projections.refresh(deliveryId);
            log.info("Batch delivery prepared deliveryId={} batchDeliveryId={} taskCount={} sourceCount={}",
                    deliveryId, submit.batchDeliveryId(), tasks.byDelivery(deliveryId).size(), consumedSources);
            return consumedSources;
        });
    }

    private PipelineDefinition pipeline(long versionId) {
        try {
            return pipelines.read(mapper.readTree(jdbc.queryForObject("SELECT definition::text FROM pipeline_version WHERE pipeline_version_id=?",
                    String.class, versionId)));
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid PipelineVersion", error);
        }
    }
}
