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

    /** 跨日完整复制映射与产物对，新旧 taskId 和作废原因只写不可变事件，不在任务表保存反向指针。 */
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
                            "delivery_date,cutoff_at,new_file_name,size_bytes,token_count,artifacts,metadata_ids,current_node_id) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?) RETURNING task_id", Long.class, old.deliveryId(), old.deliveryEntityId(),
                    old.entityCode(), old.workerGroup(), nextDate, nextCutoff, old.newFileName(), old.sizeBytes(), old.tokenCount(),
                    old.artifacts().toString(), old.metadataIds().toString(), pipeline.rolloverStartNode());
            jdbc.update("INSERT INTO delivery_mapping(task_id,delivery_id,data_id,source_seq) " +
                    "SELECT ?,delivery_id,data_id,source_seq FROM delivery_mapping WHERE task_id=? ORDER BY source_seq", newTaskId, old.taskId());
            // targetUpload 等非首节点跨日时，新任务必须同时继承 repair/qualify 等前置成功 locator。
            copyPrerequisiteNodes(old.taskId(), newTaskId, pipeline);
            ObjectNode expiredPayload = mapper.createObjectNode().put("taskId", old.taskId()).put("oldTaskId", old.taskId())
                    .put("newTaskId", newTaskId).put("oldDeliveryDate", old.deliveryDate().toString()).put("newDeliveryDate", nextDate.toString());
            Long sourceDataId = jdbc.queryForObject("SELECT data_id FROM delivery_mapping WHERE task_id=? ORDER BY source_seq LIMIT 1",
                    Long.class, old.taskId());
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
        // 按 DAG 顺序收集跨日起点之前的节点，绝不复制目标节点自身或后续失败记录。
        List<String> prerequisiteIds = new ArrayList<>();
        String cursor = pipeline.firstNode();
        while (!cursor.equals(pipeline.rolloverStartNode())) {
            prerequisiteIds.add(cursor);
            cursor = pipeline.node(cursor).next();
        }
        // 跨日起点就是首节点时没有任何前置 locator 需要复制。
        if (prerequisiteIds.isEmpty()) return;
        // IN 列表只来自当前不可变 Pipeline 的节点 ID，不接收外部 SQL。
        String placeholders = String.join(",", java.util.Collections.nCopies(prerequisiteIds.size(), "?"));
        // 每个前置节点只复制最新成功 attempt，新 process_id 由数据库生成。
        String sql = "INSERT INTO delivery_node(delivery_id,task_id,original_file_name,new_file_name,node_id,node_type,stage_code," +
                "attempt,fence,status,worker_instance_id,input_locator,output_locator,error_code,error_detail,started_at,heartbeat_at,finished_at) " +
                "SELECT delivery_id,?,original_file_name,new_file_name,node_id,node_type,stage_code,attempt,fence,status,worker_instance_id," +
                "input_locator,output_locator,error_code,error_detail,started_at,heartbeat_at,finished_at FROM (" +
                "SELECT DISTINCT ON(node_id) * FROM delivery_node WHERE task_id=? AND status='SUCCEEDED' AND node_id IN(" + placeholders + ") " +
                "ORDER BY node_id,attempt DESC) prerequisite";
        // 参数顺序依次为新 task、旧 task 和 DAG 前置节点 ID。
        List<Object> arguments = new ArrayList<>();
        arguments.add(newTaskId);
        arguments.add(oldTaskId);
        arguments.addAll(prerequisiteIds);
        int copied = jdbc.update(sql, arguments.toArray());
        // 日志记录新旧任务及复制数量，便于核对跨日 locator 血缘。
        org.slf4j.LoggerFactory.getLogger(DeliveryRolloverService.class).info(
                "Rollover prerequisite locators copied oldTaskId={} newTaskId={} rolloverStartNode={} copiedNodes={}",
                oldTaskId, newTaskId, pipeline.rolloverStartNode(), copied);
    }

    private PipelineDefinition pipeline(long deliveryId) {
        try {
            return pipelines.read(mapper.readTree(jdbc.queryForObject("SELECT pv.definition::text FROM delivery_submit s " +
                    "JOIN pipeline_version pv ON pv.pipeline_version_id=s.pipeline_version_id WHERE s.delivery_id=?", String.class, deliveryId)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid delivery PipelineVersion", e);
        }
    }
}
