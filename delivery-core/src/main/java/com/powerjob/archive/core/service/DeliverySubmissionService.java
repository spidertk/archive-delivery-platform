package com.powerjob.archive.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.domain.DeliveryEntity;
import com.powerjob.archive.core.domain.DeliverySubmit;
import com.powerjob.archive.core.pipeline.PipelineDefinition;
import com.powerjob.archive.core.pipeline.PipelineRepository;
import com.powerjob.archive.core.repository.DeliveryEventRepository;
import com.powerjob.archive.core.repository.DeliveryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class DeliverySubmissionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final DeliveryRepository deliveries;
    private final DeliveryEventRepository events;
    private final PipelineRepository pipelines;

    public DeliverySubmissionService(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper,
                                     DeliveryRepository deliveries, DeliveryEventRepository events, PipelineRepository pipelines) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
        this.deliveries = deliveries;
        this.events = events;
        this.pipelines = pipelines;
    }

    public Submission submit(SubmissionCommand command) {
        ObjectNode strategy = validateAndSnapshot(command);
        return transactions.execute(status -> {
            DeliverySubmit batch = deliveries.findBatchCreationForUpdate(command.deliveryBatch())
                    .orElseThrow(() -> new IllegalArgumentException("Create the OSS delivery batch before submitting deliveries"));
            if (batch.discoveryCompletedAt() == null || !List.of("PROCESSING", "DELIVERED").contains(batch.status())) {
                throw new IdempotencyConflictException("OSS batch planning must complete before delivery submission");
            }
            String batchSource = jdbc.queryForObject("SELECT oss_uri FROM ingest_source WHERE source_id=?", String.class, batch.sourceId());
            if (!batchSource.equals(command.ossUri()) || !batch.deliveryDate().equals(command.deliveryDate())) {
                throw new IllegalArgumentException("ossUri and deliveryDate must match the created delivery batch");
            }
            Resolved resolved = resolve(command);
            for (TargetCommand target : command.targets()) {
                DeliveryEntity entity = deliveries.entityByBusinessCode(target.vendorCode(), target.entityCode());
                if (!entity.enabled()) throw new IllegalArgumentException("Delivery entity is disabled: " + target.entityCode());
                boolean supported = resolved.pipeline().nodes().stream()
                        .anyMatch(node -> node.type().startsWith(entity.protocol().toLowerCase(java.util.Locale.ROOT) + "."));
                if (!supported) throw new IllegalArgumentException("Pipeline does not support entity protocol " + entity.protocol());
            }
            long deliveryId = jdbc.queryForObject("INSERT INTO delivery_submit(delivery_batch,source_id,pipeline_version_id," +
                            "delivery_date,cutoff_at,target_strategy,submission_type,batch_delivery_id,batch_extension) " +
                            "VALUES(?,?,?,?,?,?::jsonb,'BATCH_SUBMIT',?,?::jsonb) RETURNING delivery_id", Long.class,
                    command.deliveryBatch(), batch.sourceId(), resolved.pipelineVersionId(), command.deliveryDate(), resolved.cutoffAt(),
                    strategy.toString(), batch.deliveryId(), batch.batchExtension().toString());
            events.append(deliveryId, null, null, null, null, "DELIVERY_SUBMITTED", "INFO", null,
                    "Batch delivery accepted", mapper.createObjectNode().put("deliveryId", deliveryId)
                            .put("batchDeliveryId", batch.deliveryId()), "api", "submitted");
            return new Submission(deliveryId, "SUBMITTED", command.deliveryDate(), resolved.cutoffAt(),
                    "BATCH_SUBMIT", batch.deliveryId());
        });
    }

    private ObjectNode validateAndSnapshot(SubmissionCommand command) {
        if (command == null || blank(command.ossUri()) || blank(command.pipelineCode()) || blank(command.deliveryBatch())
                || command.deliveryBatch().length() > 128 || command.deliveryDate() == null
                || command.targets() == null || command.targets().isEmpty()) {
            throw new IllegalArgumentException("ossUri, pipelineCode, deliveryDate, deliveryBatch and targets are required");
        }
        Set<String> entities = new HashSet<>();
        ObjectNode strategy = mapper.createObjectNode().put("schemaVersion", 1);
        ArrayNode targets = strategy.putArray("targets");
        for (TargetCommand target : command.targets()) {
            String businessKey = target == null ? "" : target.vendorCode() + "\0" + target.entityCode();
            if (target == null || target.targetCount() <= 0 || blank(target.vendorCode()) || blank(target.entityCode())
                    || !entities.add(businessKey)) {
                throw new IllegalArgumentException("targets require distinct vendorCode+entityCode and positive targetCount");
            }
            targets.addObject().put("targetCount", target.targetCount())
                    .put("vendorCode", target.vendorCode()).put("entityCode", target.entityCode());
        }
        return strategy;
    }

    private Resolved resolve(SubmissionCommand command) {
        var row = jdbc.query("SELECT pv.pipeline_version_id,pv.definition::text FROM pipeline_version pv JOIN pipeline p ON p.pipeline_id=pv.pipeline_id " +
                        "WHERE p.pipeline_code=? AND p.pipeline_code='archive-delivery' AND pv.status='PUBLISHED' ORDER BY pv.version_no DESC LIMIT 1",
                (rs, n) -> new PipelineRow(rs.getLong(1), rs.getString(2)), command.pipelineCode()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("archive-delivery has no published version"));
        PipelineDefinition pipeline;
        try {
            pipeline = pipelines.read(mapper.readTree(row.definition()));
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid archive-delivery definition", error);
        }
        return new Resolved(row.id(), DeliveryBatchService.cutoff(command.deliveryDate(), pipeline), pipeline);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private record PipelineRow(long id, String definition) {}
    private record Resolved(long pipelineVersionId, OffsetDateTime cutoffAt, PipelineDefinition pipeline) {}
    public record TargetCommand(int targetCount, String vendorCode, String entityCode) {}
    public record SubmissionCommand(String ossUri, String pipelineCode, LocalDate deliveryDate, String deliveryBatch,
                                    List<TargetCommand> targets) {}
    public record Submission(long deliveryId, String status, LocalDate deliveryDate, OffsetDateTime cutoffAt,
                             String submissionType, Long batchDeliveryId) {}
}
