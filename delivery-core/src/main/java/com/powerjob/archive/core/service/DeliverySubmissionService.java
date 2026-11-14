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
        strategy.putArray("requests").add(targets.deepCopy());
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
        OffsetDateTime cutoff = command.deliveryDate().plusDays(1).atTime(LocalTime.parse(String.valueOf(pipeline.config().getOrDefault("deliveryCutoffTime", "18:00:00"))))
                .atZone(ZoneId.of(String.valueOf(pipeline.config().getOrDefault("businessTimezone", "Asia/Shanghai")))).toOffsetDateTime();
        if (!cutoff.isAfter(OffsetDateTime.now())) throw new IllegalArgumentException("Pipeline delivery cutoff must be in the future");
        return new ResolvedReferences(sourceId, row.id(), cutoff);
    }

    private Submission sameRequest(DeliverySubmit existing, SubmissionCommand command, ObjectNode strategy) {
        return transactions.execute(status -> {
            DeliverySubmit current = deliveries.findByDeliveryBatchForUpdate(command.deliveryBatch()).orElseThrow();
            String sourceUri = jdbc.queryForObject("SELECT oss_uri FROM ingest_source WHERE source_id=?", String.class, current.sourceId());
            String pipelineCode = jdbc.queryForObject("SELECT p.pipeline_code FROM pipeline p JOIN pipeline_version pv ON p.pipeline_id=pv.pipeline_id " +
                    "WHERE pv.pipeline_version_id=?", String.class, current.pipelineVersionId());
            if (!current.deliveryDate().equals(command.deliveryDate()) || !sourceUri.equals(command.ossUri())
                    || !pipelineCode.equals(command.pipelineCode())) {
                throw new IdempotencyConflictException("deliveryBatch is already used by different delivery parameters");
            }
            if (previousRequest(current.targetStrategy(), strategy.required("targets"))) {
                retryPreparation(current);
                resumePaused(current);
                DeliverySubmit latest = deliveries.find(current.deliveryId()).orElseThrow();
                return new Submission(latest.deliveryId(), latest.status(), latest.deliveryDate(), latest.cutoffAt());
            }
            if (!"DELIVERED".equals(current.status())) {
                throw new IdempotencyConflictException("A delivered batch is required before appending delivery targets");
            }
            ObjectNode extended = extendTargets(current.targetStrategy(), strategy.required("targets"));
            jdbc.update("UPDATE delivery_submit SET target_strategy=?::jsonb,discovery_completed_at=NULL,completed_at=NULL," +
                            "status='SUBMITTED',updated_at=now() WHERE delivery_id=?",
                    extended.toString(), current.deliveryId());
            events.append(current.deliveryId(), null, null, null, null, "DELIVERY_APPENDED", "INFO", null,
                    "Additional targets appended to the delivered batch", mapper.createObjectNode()
                            .put("deliveryId", current.deliveryId()).set("targets", strategy.required("targets")), "api",
                    "delivery-appended-" + current.deliveryId() + "-" + UUID.randomUUID());
            return new Submission(current.deliveryId(), "SUBMITTED", current.deliveryDate(), current.cutoffAt());
        });
    }

    private boolean previousRequest(com.fasterxml.jackson.databind.JsonNode strategy, com.fasterxml.jackson.databind.JsonNode requested) {
        com.fasterxml.jackson.databind.JsonNode requests = strategy.get("requests");
        if (requests instanceof ArrayNode history) {
            for (com.fasterxml.jackson.databind.JsonNode previous : history) if (previous.equals(requested)) return true;
            return false;
        }
        return strategy.required("targets").equals(requested);
    }

    private ObjectNode extendTargets(com.fasterxml.jackson.databind.JsonNode current, com.fasterxml.jackson.databind.JsonNode requested) {
        java.util.Map<String, Integer> totals = new java.util.HashMap<>();
        java.util.Map<String, TargetCommand> identities = new java.util.HashMap<>();
        for (com.fasterxml.jackson.databind.JsonNode target : current.required("targets")) {
            String key = target.required("vendorCode").asText() + "\0" + target.required("entityCode").asText();
            totals.put(key, target.required("targetCount").asInt());
            identities.put(key, new TargetCommand(0, target.required("vendorCode").asText(), target.required("entityCode").asText()));
        }
        for (com.fasterxml.jackson.databind.JsonNode target : requested) {
            String key = target.required("vendorCode").asText() + "\0" + target.required("entityCode").asText();
            if (!totals.containsKey(key)) throw new IdempotencyConflictException("An appended delivery cannot add new target entities");
            totals.put(key, Math.addExact(totals.get(key), target.required("targetCount").asInt()));
        }
        ObjectNode extended = ((ObjectNode) current).deepCopy();
        ArrayNode targets = extended.putArray("targets");
        for (com.fasterxml.jackson.databind.JsonNode target : current.required("targets")) {
            TargetCommand identity = identities.get(target.required("vendorCode").asText() + "\0" + target.required("entityCode").asText());
            targets.addObject().put("targetCount", totals.get(identity.vendorCode() + "\0" + identity.entityCode()))
                    .put("vendorCode", identity.vendorCode()).put("entityCode", identity.entityCode());
        }
        ArrayNode requests = extended.withArray("requests");
        requests.add(requested.deepCopy());
        return extended;
    }

    private void retryPreparation(DeliverySubmit existing) {
        if (!"BLOCKED".equals(existing.status()) || existing.discoveryCompletedAt() != null) return;
        Integer blocked = jdbc.queryForObject("SELECT count(*) FROM delivery_task WHERE delivery_id=? AND execution_status='BLOCKED'",
                Integer.class, existing.deliveryId());
        if (blocked != null && blocked > 0) {
            throw new IdempotencyConflictException("Blocked tasks must be repaired before preparing additional sources");
        }
        jdbc.update("UPDATE delivery_submit SET status='SUBMITTED',updated_at=now() WHERE delivery_id=?", existing.deliveryId());
        events.append(existing.deliveryId(), null, null, null, null, "DELIVERY_PREPARATION_RETRIED", "INFO", "MANUAL_RESUBMIT",
                "Preparation retry after no task was blocked", mapper.createObjectNode().put("deliveryId", existing.deliveryId()),
                "api", "preparation-retry-" + existing.deliveryId() + "-" + UUID.randomUUID());
    }

    private void resumePaused(DeliverySubmit existing) {
        if (!"PAUSED".equals(existing.status())) return;
        int resumed = tasks.resumePaused(existing.deliveryId());
        if (resumed > 0) {
            events.append(existing.deliveryId(), null, null, null, null, "DELIVERY_RESUMED", "INFO", "MANUAL_RESUBMIT",
                    "Paused delivery resumed by identical deliveryBatch submission", mapper.createObjectNode()
                            .put("deliveryId", existing.deliveryId()).put("resumed", resumed), "api",
                    "delivery-resumed-" + existing.deliveryId() + "-" + UUID.randomUUID());
        }
        projections.refresh(existing.deliveryId());
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private record PipelineRow(long id, String definition) {}
    private record ResolvedReferences(long sourceId, long pipelineVersionId, OffsetDateTime cutoffAt) {}
    public record TargetCommand(int targetCount, String vendorCode, String entityCode) {}
    public record SubmissionCommand(String ossUri, String pipelineCode, LocalDate deliveryDate, String deliveryBatch,
                                    List<TargetCommand> targets) {}
    public record Submission(long deliveryId, String status, LocalDate deliveryDate, OffsetDateTime cutoffAt) {}
}
