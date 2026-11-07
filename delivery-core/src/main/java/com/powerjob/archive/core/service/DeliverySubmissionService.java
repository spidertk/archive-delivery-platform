package com.powerjob.archive.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.domain.DeliverySubmit;
import com.powerjob.archive.core.pipeline.PipelineDefinition;
import com.powerjob.archive.core.pipeline.PipelineRepository;
import com.powerjob.archive.core.repository.DeliveryEventRepository;
import com.powerjob.archive.core.repository.DeliveryRepository;
import org.springframework.dao.DuplicateKeyException;
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

    /** 提交只固化来源、不可变版本、日期和实体目标；不接受组编码、Secret、节点链或任意路径。 */
    public Submission submit(SubmissionCommand command) {
        ObjectNode strategy = validateAndSnapshot(command);
        var existing = deliveries.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) return sameRequest(existing.get(), command, strategy);
        validateReferences(command);
        try {
            return transactions.execute(status -> {
                long deliveryId = jdbc.queryForObject("INSERT INTO delivery_submit(delivery_batch,source_id," +
                                "pipeline_version_id,idempotency_key,delivery_date,cutoff_at,target_strategy) " +
                                "VALUES(?,?,?,?,?,?,?::jsonb) RETURNING delivery_id", Long.class, command.deliveryBatch(),
                        command.sourceId(), command.pipelineVersionId(), command.idempotencyKey(), command.deliveryDate(),
                        command.cutoffAt(), strategy.toString());
                events.append(deliveryId, null, null, null, null, "DELIVERY_SUBMITTED", "INFO", null,
                        "Delivery accepted", mapper.createObjectNode().put("deliveryId", deliveryId), "api", "submitted");
                return new Submission(deliveryId, "SUBMITTED", command.deliveryDate(), command.cutoffAt());
            });
        } catch (DuplicateKeyException e) {
            var concurrent = deliveries.findByIdempotencyKey(command.idempotencyKey());
            if (concurrent.isPresent()) return sameRequest(concurrent.get(), command, strategy);
            throw new IdempotencyConflictException("sourceId and deliveryBatch are already used");
        }
    }

    private ObjectNode validateAndSnapshot(SubmissionCommand command) {
        if (command.sourceId() <= 0 || command.pipelineVersionId() <= 0 || blank(command.deliveryBatch())
                || blank(command.idempotencyKey()) || command.deliveryDate() == null || command.cutoffAt() == null
                || command.targets() == null || command.targets().isEmpty()) {
            throw new IllegalArgumentException("Complete source, Pipeline, dates, batch, idempotency key and targets are required");
        }
        if (!command.cutoffAt().isAfter(OffsetDateTime.now())) throw new IllegalArgumentException("cutoffAt must be in the future");
        Set<Long> entities = new HashSet<>();
        ObjectNode strategy = mapper.createObjectNode().put("schemaVersion", 1);
        ArrayNode targets = strategy.putArray("targets");
        for (TargetCommand target : command.targets()) {
            if (target.targetCount() <= 0 || target.deliveryEntityId() <= 0 || !entities.add(target.deliveryEntityId())) {
                throw new IllegalArgumentException("targets require distinct deliveryEntityId and positive targetCount");
            }
            targets.addObject().put("targetCount", target.targetCount()).put("deliveryEntityId", target.deliveryEntityId());
        }
        return strategy;
    }

    private void validateReferences(SubmissionCommand command) {
        Integer available = jdbc.queryForObject("SELECT count(*) FROM ingest_source WHERE source_id=? AND availability_status='AVAILABLE'",
                Integer.class, command.sourceId());
        if (available != 1) throw new IllegalArgumentException("Source is missing or unavailable");
        String definition = jdbc.query("SELECT definition::text FROM pipeline_version WHERE pipeline_version_id=? AND status='PUBLISHED'",
                (rs, n) -> rs.getString(1), command.pipelineVersionId()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("PipelineVersion is missing or not published"));
        PipelineDefinition pipeline;
        try {
            pipeline = pipelines.read(mapper.readTree(definition));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Pipeline definition", e);
        }
        for (TargetCommand target : command.targets()) {
            var entity = deliveries.entity(target.deliveryEntityId());
            if (!entity.enabled()) throw new IllegalArgumentException("Delivery entity is disabled");
            boolean supported = pipeline.nodes().stream().anyMatch(node -> node.type().startsWith(entity.protocol().toLowerCase(java.util.Locale.ROOT) + "."));
            if (!supported) throw new IllegalArgumentException("Pipeline does not support entity protocol " + entity.protocol());
        }
    }

    private Submission sameRequest(DeliverySubmit existing, SubmissionCommand command, ObjectNode strategy) {
        boolean same = existing.sourceId() == command.sourceId() && existing.pipelineVersionId() == command.pipelineVersionId()
                && existing.deliveryBatch().equals(command.deliveryBatch()) && existing.deliveryDate().equals(command.deliveryDate())
                && existing.cutoffAt().toInstant().equals(command.cutoffAt().toInstant()) && existing.targetStrategy().equals(strategy);
        if (!same) throw new IdempotencyConflictException("idempotencyKey is already used by different delivery parameters");
        return new Submission(existing.deliveryId(), existing.status(), existing.deliveryDate(), existing.cutoffAt());
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record TargetCommand(int targetCount, long deliveryEntityId) {}
    public record SubmissionCommand(long sourceId, long pipelineVersionId, LocalDate deliveryDate, OffsetDateTime cutoffAt,
                                    String deliveryBatch, String idempotencyKey, List<TargetCommand> targets) {}
    public record Submission(long deliveryId, String status, LocalDate deliveryDate, OffsetDateTime cutoffAt) {}
}
