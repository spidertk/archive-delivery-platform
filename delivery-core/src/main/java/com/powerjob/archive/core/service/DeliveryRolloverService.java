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
import java.util.ArrayList;
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
        List<Long> deliveryIds = jdbc.queryForList("SELECT DISTINCT t.delivery_id FROM delivery_task t " +
                "JOIN delivery_submit s ON s.delivery_id=t.delivery_id " +
                "JOIN delivery_submit b ON b.delivery_id=s.batch_delivery_id AND b.submission_type='BATCH_CREATE' " +
                "WHERE s.submission_type='BATCH_SUBMIT' AND s.batch_delivery_id IS NOT NULL " +
                "AND t.cutoff_at<=now() AND t.execution_status NOT IN('SUCCEEDED','EXPIRED','CANCELLED') ORDER BY t.delivery_id", Long.class);
        return deliveryIds.stream().mapToInt(id -> transactions.execute(status -> rollover(id, null, null, true, "system"))).sum();
    }

    public int rolloverDelivery(long deliveryId, LocalDate expectedDeliveryDate, LocalDate deliveryDate, String reason, String operator) {
        if (expectedDeliveryDate == null || deliveryDate == null || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("expectedDeliveryDate, deliveryDate and reason are required");
        }
        if (!deliveryDate.isAfter(expectedDeliveryDate)) throw new IllegalArgumentException("deliveryDate must be after expectedDeliveryDate");
        return transactions.execute(status -> rollover(deliveryId, expectedDeliveryDate, deliveryDate, false, operator));
    }

    private int rollover(long deliveryId, LocalDate expectedDate, LocalDate requestedDate, boolean dueOnly, String operator) {
        jdbc.queryForObject("SELECT delivery_id FROM delivery_submit WHERE delivery_id=? FOR UPDATE", Long.class, deliveryId);
        PipelineDefinition pipeline = pipeline(deliveryId);
        String sql = "SELECT task_id FROM delivery_task WHERE delivery_id=? AND execution_status NOT IN('SUCCEEDED','EXPIRED','CANCELLED')";
        if (dueOnly) sql += " AND cutoff_at<=now()";
        if (expectedDate != null) sql += " AND delivery_date=?";
        sql += " ORDER BY delivery_date,task_id FOR UPDATE SKIP LOCKED";
        List<Long> dueIds = expectedDate == null ? jdbc.queryForList(sql, Long.class, deliveryId)
                : jdbc.queryForList(sql, Long.class, deliveryId, expectedDate);
        List<DeliveryTask> due = new ArrayList<>();
        dueIds.forEach(id -> tasks.findById(id).ifPresent(due::add));
        int changed = 0;
        for (DeliveryTask old : due) {
            LocalDate nextDate = requestedDate == null ? old.deliveryDate().plusDays(1) : requestedDate;
            OffsetDateTime nextCutoff = nextDate.atTime(LocalTime.parse(pipeline.config().getOrDefault("deliveryCutoffTime", "18:00:00").toString()))
                    .atZone(ZoneId.of(pipeline.config().getOrDefault("businessTimezone", "Asia/Shanghai").toString())).toOffsetDateTime();
            int expired = jdbc.update("UPDATE delivery_task SET execution_status='EXPIRED',lease_owner=NULL,lease_until=NULL,updated_at=now() " +
                    "WHERE task_id=? AND execution_status NOT IN('SUCCEEDED','EXPIRED','CANCELLED')", old.taskId());
            if (expired != 1) continue;
            jdbc.update("UPDATE delivery_node SET status='CANCELLED',finished_at=now() WHERE task_id=? AND status IN('RUNNING','WAITING')", old.taskId());
            long newTaskId = jdbc.queryForObject("INSERT INTO delivery_task(delivery_id,delivery_entity_id,entity_code,worker_group," +
                            "delivery_date,cutoff_at,new_file_name,size_bytes,token_count,artifacts,current_node_id) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,?) RETURNING task_id", Long.class, old.deliveryId(), old.deliveryEntityId(),
                    old.entityCode(), old.workerGroup(), nextDate, nextCutoff, old.newFileName(), old.sizeBytes(), old.tokenCount(),
                    old.artifacts().toString(), pipeline.rolloverStartNode());
            copyPrerequisiteNodes(old.taskId(), newTaskId, pipeline);
            ObjectNode expiredPayload = mapper.createObjectNode().put("taskId", old.taskId()).put("oldTaskId", old.taskId())
                    .put("newTaskId", newTaskId).put("oldDeliveryDate", old.deliveryDate().toString()).put("newDeliveryDate", nextDate.toString());
            Long sourceDataId = jdbc.queryForObject("SELECT b.data_id FROM delivery_batch b WHERE b.delivery_id=? AND b.new_file_name=? " +
                    "ORDER BY b.source_seq LIMIT 1", Long.class, old.deliveryId(), old.newFileName());
            events.append(deliveryId, old.taskId(), null, old.deliveryEntityId(), sourceDataId, "TASK_EXPIRED", "WARN",
                    "CUTOFF_EXCEEDED", "Task expired and continued with a new taskId", expiredPayload, operator, "task-expired-" + old.taskId());
            events.append(deliveryId, newTaskId, null, old.deliveryEntityId(), sourceDataId, "TASK_REGENERATED", "INFO",
                    "CUTOFF_EXCEEDED", "Next-date task created", expiredPayload, operator, "task-regenerated-" + newTaskId);
            changed++;
        }
        if (expectedDate != null && changed == 0) throw new ResumeConflictException("No unfinished tasks matched expectedDeliveryDate");
        if (changed > 0) projections.refresh(deliveryId);
        return changed;
    }

    private void copyPrerequisiteNodes(long oldTaskId, long newTaskId, PipelineDefinition pipeline) {
        List<String> prerequisiteIds = new ArrayList<>();
        String cursor = pipeline.firstNode();
        while (!cursor.equals(pipeline.rolloverStartNode())) {
            prerequisiteIds.add(cursor);
            cursor = pipeline.node(cursor).next();
        }
        if (prerequisiteIds.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(prerequisiteIds.size(), "?"));
        String sql = "INSERT INTO delivery_node(delivery_id,task_id,original_file_name,new_file_name,node_id,node_type,stage_code," +
                "attempt,fence,status,worker_instance_id,input_locator,output_locator,error_code,error_detail,started_at,heartbeat_at,finished_at) " +
                "SELECT delivery_id,?,original_file_name,new_file_name,node_id,node_type,stage_code,attempt,fence,status,worker_instance_id," +
                "input_locator,output_locator,error_code,error_detail,started_at,heartbeat_at,finished_at FROM (" +
                "SELECT DISTINCT ON(node_id) * FROM delivery_node WHERE task_id=? AND status='SUCCEEDED' AND node_id IN(" + placeholders + ") " +
                "ORDER BY node_id,attempt DESC) prerequisite";
        List<Object> arguments = new ArrayList<>();
        arguments.add(newTaskId);
        arguments.add(oldTaskId);
        arguments.addAll(prerequisiteIds);
        jdbc.update(sql, arguments.toArray());
    }

    private PipelineDefinition pipeline(long deliveryId) {
        try {
            return pipelines.read(mapper.readTree(jdbc.queryForObject("SELECT pv.definition::text FROM delivery_submit s " +
                    "JOIN pipeline_version pv ON pv.pipeline_version_id=s.pipeline_version_id WHERE s.delivery_id=?", String.class, deliveryId)));
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid delivery PipelineVersion", error);
        }
    }
}
