package com.powerjob.archive.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.domain.DeliveryTask;
import com.powerjob.archive.core.pipeline.PipelineDefinition;
import com.powerjob.archive.core.pipeline.PipelineRepository;
import com.powerjob.archive.core.repository.DeliveryEventRepository;
import com.powerjob.archive.core.repository.DeliveryTaskRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class DeliveryRolloverService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DeliveryTaskRepository tasks;
    private final DeliveryEventRepository events;
    private final DeliveryProjectionService projections;
    private final PipelineRepository pipelines;
    private final ObjectMapper mapper;

    public DeliveryRolloverService(JdbcTemplate jdbc, TransactionTemplate transactions, DeliveryTaskRepository tasks,
                                   DeliveryEventRepository events, DeliveryProjectionService projections,
                                   PipelineRepository pipelines, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.tasks = tasks;
        this.events = events;
        this.projections = projections;
        this.pipelines = pipelines;
        this.mapper = mapper;
    }

    public int rolloverDue() {
        List<Long> deliveryIds = jdbc.queryForList("SELECT DISTINCT delivery_id FROM delivery_task WHERE cutoff_at<=now() " +
                "AND execution_status NOT IN('SUCCEEDED','EXPIRED','CANCELLED') ORDER BY delivery_id", Long.class);
        return deliveryIds.stream().mapToInt(id -> transactions.execute(status -> rollover(id, null, null, true, "system"))).sum();
    }

    /** 人工续交必须指定原日期和新交付日期；同一主单只迁移未完成任务，新任务占用新日期目标配额。 */
    public int rolloverDelivery(long deliveryId, LocalDate expectedDeliveryDate, LocalDate deliveryDate, String reason, String operator) {
        if (expectedDeliveryDate == null || deliveryDate == null || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("expectedDeliveryDate, deliveryDate and reason are required");
        }
        if (!deliveryDate.isAfter(expectedDeliveryDate)) throw new IllegalArgumentException("deliveryDate must be after expectedDeliveryDate");
        return transactions.execute(status -> rollover(deliveryId, expectedDeliveryDate, deliveryDate, false, operator));
    }

    /** 同一事务作废旧 fence、关闭活动节点并创建指定日期新 taskId；实体、目标和 artifact 血缘保持不变。 */
    private int rollover(long deliveryId, LocalDate expectedDate, LocalDate requestedDate, boolean dueOnly, String operator) {
        jdbc.queryForObject("SELECT delivery_id FROM delivery_submit WHERE delivery_id=? FOR UPDATE", Long.class, deliveryId);
        PipelineDefinition pipeline = pipeline(deliveryId);
        String sql = "SELECT * FROM delivery_task WHERE delivery_id=? AND execution_status NOT IN('SUCCEEDED','EXPIRED','CANCELLED')";
        if (dueOnly) sql += " AND cutoff_at<=now()";
        if (expectedDate != null) sql += " AND delivery_date=?";
        sql += " ORDER BY delivery_date,task_id FOR UPDATE SKIP LOCKED";
        List<DeliveryTask> due = expectedDate == null ? jdbc.query(sql, this::mapTask, deliveryId)
                : jdbc.query(sql, this::mapTask, deliveryId, expectedDate);
        int changed = 0;
        for (DeliveryTask old : due) {
            LocalDate nextDate = requestedDate == null ? old.deliveryDate().plusDays(1) : requestedDate;
            OffsetDateTime nextCutoff = nextDate.atTime(LocalTime.parse(pipeline.config().getOrDefault("deliveryCutoffTime", "18:00:00").toString()))
                    .atZone(ZoneId.of(pipeline.config().getOrDefault("businessTimezone", "Asia/Shanghai").toString())).toOffsetDateTime();
            int expired = jdbc.update("UPDATE delivery_task SET execution_status='EXPIRED',invalidated_at=now()," +
                    "invalidation_reason='CUTOFF_EXCEEDED',lease_owner=NULL,lease_until=NULL,updated_at=now() " +
                    "WHERE task_id=? AND execution_status NOT IN('SUCCEEDED','EXPIRED','CANCELLED')", old.taskId());
            if (expired != 1) continue;
            jdbc.update("UPDATE delivery_node SET status='CANCELLED',finished_at=now() WHERE task_id=? AND status IN('RUNNING','WAITING')", old.taskId());
            long newTaskId = jdbc.queryForObject("INSERT INTO delivery_task(delivery_id,data_id,delivery_entity_id,target_seq,artifact_seq," +
                            "delivery_date,cutoff_at,original_file_name,new_file_name,size_bytes,token_count,sha256,current_node_id) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING task_id", Long.class, old.deliveryId(), old.dataId(), old.deliveryEntityId(),
                    old.targetSeq(), old.artifactSeq(), nextDate, nextCutoff, old.originalFileName(), old.newFileName(), old.sizeBytes(), old.tokenCount(),
                    old.sha256(), pipeline.rolloverStartNode());
            jdbc.update("UPDATE delivery_task SET superseded_by_task_id=? WHERE task_id=?", newTaskId, old.taskId());
            ObjectNode expiredPayload = mapper.createObjectNode().put("taskId", old.taskId()).put("newTaskId", newTaskId)
                    .put("oldDeliveryDate", old.deliveryDate().toString()).put("newDeliveryDate", nextDate.toString());
            events.append(deliveryId, old.taskId(), null, old.deliveryEntityId(), old.dataId(), "TASK_EXPIRED", "WARN",
                    "CUTOFF_EXCEEDED", "Task expired and was superseded", expiredPayload, operator, "task-expired-" + old.taskId());
            events.append(deliveryId, newTaskId, null, old.deliveryEntityId(), old.dataId(), "TASK_REGENERATED", "INFO",
                    "CUTOFF_EXCEEDED", "Next-date task created", expiredPayload, operator, "task-regenerated-" + newTaskId);
            changed++;
        }
        if (expectedDate != null && changed == 0) throw new ResumeConflictException("No unfinished tasks matched expectedDeliveryDate");
        if (changed > 0) projections.refresh(deliveryId);
        return changed;
    }

    private PipelineDefinition pipeline(long deliveryId) {
        try {
            return pipelines.read(mapper.readTree(jdbc.queryForObject("SELECT pv.definition::text FROM delivery_submit s " +
                    "JOIN pipeline_version pv ON pv.pipeline_version_id=s.pipeline_version_id WHERE s.delivery_id=?", String.class, deliveryId)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid delivery PipelineVersion", e);
        }
    }

    private DeliveryTask mapTask(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new DeliveryTask(rs.getLong("task_id"), rs.getLong("delivery_id"), rs.getLong("data_id"), rs.getLong("delivery_entity_id"),
                rs.getInt("target_seq"), rs.getInt("artifact_seq"), rs.getObject("delivery_date", LocalDate.class), rs.getObject("cutoff_at", OffsetDateTime.class),
                rs.getString("original_file_name"), rs.getString("new_file_name"), rs.getLong("size_bytes"), rs.getLong("token_count"),
                rs.getString("sha256"), rs.getString("current_node_id"), rs.getString("execution_status"), rs.getString("lease_owner"),
                rs.getObject("lease_until", OffsetDateTime.class), rs.getLong("fence"), rs.getInt("retry_count"),
                rs.getObject("next_retry_at", OffsetDateTime.class), rs.getString("last_error_code"),
                (Long) rs.getObject("superseded_by_task_id"), rs.getObject("invalidated_at", OffsetDateTime.class),
                rs.getString("invalidation_reason"), rs.getObject("completed_at", OffsetDateTime.class));
    }
}
