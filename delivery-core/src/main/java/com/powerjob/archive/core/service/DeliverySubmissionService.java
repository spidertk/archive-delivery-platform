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
import com.powerjob.archive.core.repository.DeliveryTaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class DeliverySubmissionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final DeliveryRepository deliveries;
    private final DeliveryTaskRepository tasks;
    private final DeliveryEventRepository events;
    private final DeliveryProjectionService projections;
    private final PipelineRepository pipelines;
    private final IngestSourceService sources;

    public DeliverySubmissionService(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper,
                                     DeliveryRepository deliveries, DeliveryTaskRepository tasks, DeliveryEventRepository events,
                                     DeliveryProjectionService projections, PipelineRepository pipelines, IngestSourceService sources) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
        this.deliveries = deliveries;
        this.tasks = tasks;
        this.events = events;
        this.projections = projections;
        this.pipelines = pipelines;
        this.sources = sources;
    }

    /** 提交使用 vendorCode+entityCode 复合业务键；cutoffAt 由不可变 Pipeline 配置推导，deliveryBatch 全局幂等。 */
    public Submission submit(SubmissionCommand command) {
        ObjectNode strategy = validateAndSnapshot(command);
        var existing = deliveries.findByDeliveryBatch(command.deliveryBatch());
        if (existing.isPresent()) return sameRequest(existing.get(), command, strategy);
        try {
            return transactions.execute(status -> {
                ResolvedReferences references = resolveReferences(command);
                long deliveryId = jdbc.queryForObject("INSERT INTO delivery_submit(delivery_batch,source_id," +
                                "pipeline_version_id,delivery_date,cutoff_at,target_strategy) VALUES(?,?,?,?,?,?::jsonb) RETURNING delivery_id",
                        Long.class, command.deliveryBatch(), references.sourceId(), references.pipelineVersionId(),
                        command.deliveryDate(), references.cutoffAt(), strategy.toString());
                events.append(deliveryId, null, null, null, null, "DELIVERY_SUBMITTED", "INFO", null,
                        "Delivery accepted", mapper.createObjectNode().put("deliveryId", deliveryId), "api", "submitted");
                return new Submission(deliveryId, "SUBMITTED", command.deliveryDate(), references.cutoffAt());
            });
        } catch (DuplicateKeyException e) {
            var concurrent = deliveries.findByDeliveryBatch(command.deliveryBatch());
            if (concurrent.isPresent()) return sameRequest(concurrent.get(), command, strategy);
            throw new IdempotencyConflictException("deliveryBatch or a referenced business code is already used");
        }
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

    private ResolvedReferences resolveReferences(SubmissionCommand command) {
        long sourceId = sources.resolve(command.ossUri());
        Integer available = jdbc.queryForObject("SELECT count(*) FROM ingest_source WHERE source_id=? AND availability_status='AVAILABLE'",
                Integer.class, sourceId);
        if (available != 1) throw new IllegalArgumentException("Source is unavailable");
        var row = jdbc.query("SELECT pv.pipeline_version_id,pv.definition::text FROM pipeline_version pv JOIN pipeline p ON p.pipeline_id=pv.pipeline_id " +
                        "WHERE p.pipeline_code=? AND pv.status='PUBLISHED' ORDER BY pv.version_no DESC LIMIT 1",
                (rs, n) -> new PipelineRow(rs.getLong(1), rs.getString(2)), command.pipelineCode()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("pipelineCode has no published version"));
        PipelineDefinition pipeline;
        try {
            pipeline = pipelines.read(mapper.readTree(row.definition()));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Pipeline definition", e);
        }
        for (TargetCommand target : command.targets()) {
            DeliveryEntity entity = deliveries.entityByBusinessCode(target.vendorCode(), target.entityCode());
            if (!entity.enabled()) throw new IllegalArgumentException("Delivery entity is disabled: " + target.entityCode());
            boolean supported = pipeline.nodes().stream().anyMatch(node -> node.type().startsWith(entity.protocol().toLowerCase(java.util.Locale.ROOT) + "."));
            if (!supported) throw new IllegalArgumentException("Pipeline does not support entity protocol " + entity.protocol());
        }
        OffsetDateTime cutoff = command.deliveryDate().atTime(LocalTime.parse(String.valueOf(pipeline.config().getOrDefault("deliveryCutoffTime", "18:00:00"))))
                .atZone(ZoneId.of(String.valueOf(pipeline.config().getOrDefault("businessTimezone", "Asia/Shanghai")))).toOffsetDateTime();
        if (!cutoff.isAfter(OffsetDateTime.now())) throw new IllegalArgumentException("Pipeline delivery cutoff must be in the future");
        return new ResolvedReferences(sourceId, row.id(), cutoff);
    }

    private Submission sameRequest(DeliverySubmit existing, SubmissionCommand command, ObjectNode strategy) {
        String sourceUri = jdbc.queryForObject("SELECT oss_uri FROM ingest_source WHERE source_id=?", String.class, existing.sourceId());
        String pipelineCode = jdbc.queryForObject("SELECT p.pipeline_code FROM pipeline p JOIN pipeline_version pv ON p.pipeline_id=pv.pipeline_id " +
                "WHERE pv.pipeline_version_id=?", String.class, existing.pipelineVersionId());
        boolean same = existing.deliveryDate().equals(command.deliveryDate()) && existing.targetStrategy().equals(strategy)
                && sourceUri.equals(command.ossUri()) && pipelineCode.equals(command.pipelineCode());
        if (!same) throw new IdempotencyConflictException("deliveryBatch is already used by different delivery parameters");
        if ("PAUSED".equals(existing.status())) {
            transactions.executeWithoutResult(status -> {
                int resumed = tasks.resumePaused(existing.deliveryId());
                if (resumed > 0) {
                    events.append(existing.deliveryId(), null, null, null, null, "DELIVERY_RESUMED", "INFO", "MANUAL_RESUBMIT",
                            "Paused delivery resumed by identical deliveryBatch submission", mapper.createObjectNode()
                                    .put("deliveryId", existing.deliveryId()).put("resumed", resumed), "api",
                            "delivery-resumed-" + existing.deliveryId() + "-" + UUID.randomUUID());
                }
                projections.refresh(existing.deliveryId());
            });
        }
        DeliverySubmit current = deliveries.find(existing.deliveryId()).orElseThrow();
        return new Submission(current.deliveryId(), current.status(), current.deliveryDate(), current.cutoffAt());
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private record PipelineRow(long id, String definition) {}
    private record ResolvedReferences(long sourceId, long pipelineVersionId, OffsetDateTime cutoffAt) {}
    public record TargetCommand(int targetCount, String vendorCode, String entityCode) {}
    public record SubmissionCommand(String ossUri, String pipelineCode, LocalDate deliveryDate, String deliveryBatch,
                                    List<TargetCommand> targets) {}
    public record Submission(long deliveryId, String status, LocalDate deliveryDate, OffsetDateTime cutoffAt) {}
}
