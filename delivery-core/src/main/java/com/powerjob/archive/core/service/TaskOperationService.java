package com.powerjob.archive.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.domain.DeliveryTask;
import com.powerjob.archive.core.domain.SourceData;
import com.powerjob.archive.core.pipeline.NodeDefinition;
import com.powerjob.archive.core.pipeline.PipelineDefinition;
import com.powerjob.archive.core.pipeline.PipelineRepository;
import com.powerjob.archive.core.repository.DeliveryEventRepository;
import com.powerjob.archive.core.repository.DeliveryNodeRepository;
import com.powerjob.archive.core.repository.DeliveryTaskRepository;
import com.powerjob.archive.core.repository.SourceDataRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
public class TaskOperationService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DeliveryTaskRepository tasks;
    private final SourceDataRepository sources;
    private final DeliveryNodeRepository nodes;
    private final DeliveryEventRepository events;
    private final DeliveryProjectionService projections;
    private final PipelineRepository pipelines;
    private final ObjectMapper mapper;

    public TaskOperationService(JdbcTemplate jdbc, TransactionTemplate transactions, DeliveryTaskRepository tasks,
                                SourceDataRepository sources, DeliveryNodeRepository nodes, DeliveryEventRepository events,
                                DeliveryProjectionService projections, PipelineRepository pipelines, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.tasks = tasks;
        this.sources = sources;
        this.nodes = nodes;
        this.events = events;
        this.projections = projections;
        this.pipelines = pipelines;
        this.mapper = mapper;
    }

    /** retry 只释放当前失败任务，expectedFence 防止操作者重试已经被其他 Worker 推进的版本。 */
    public void retry(long taskId, long expectedFence, String reason, String operator) {
        requireReason(reason);
        transactions.executeWithoutResult(status -> {
            DeliveryTask task = tasks.findById(taskId).orElseThrow();
            if (!List.of("RETRY_WAIT", "BLOCKED").contains(task.executionStatus()) || task.fence() != expectedFence) {
                throw new ResumeConflictException("Task fence or retry state does not match");
            }
            if (tasks.retry(taskId, expectedFence) != 1) throw new ResumeConflictException("Task changed concurrently");
            events.append(task.deliveryId(), taskId, null, task.deliveryEntityId(), task.dataId(), "TASK_RETRIED", "INFO",
                    "MANUAL_RETRY", reason, mapper.createObjectNode().put("taskId", taskId).put("expectedFence", expectedFence),
                    operator, "retry-" + taskId + "-" + expectedFence);
            projections.refresh(task.deliveryId());
        });
    }

    /** resume 必须沿当前 DAG 从合法节点继续，除跨日起点外每个前置节点都要有当前任务的成功 locator。 */
    public int resume(long deliveryId, String fromNode, List<Long> taskIds, String reason, String operator) {
        requireReason(reason);
        PipelineDefinition pipeline = pipeline(deliveryId);
        NodeDefinition from = pipeline.node(fromNode);
        List<DeliveryTask> selected = tasks.byDelivery(deliveryId).stream()
                .filter(task -> task.executionStatus().equals("EXPIRED") ? false : taskIds == null || taskIds.isEmpty() || taskIds.contains(task.taskId())).toList();
        if (selected.isEmpty()) throw new ResumeConflictException("No active tasks matched resume request");
        for (DeliveryTask task : selected) {
            transactions.executeWithoutResult(status -> {
                if (List.of("RUNNING", "SUCCEEDED", "EXPIRED", "CANCELLED").contains(task.executionStatus())
                        || !task.currentNodeId().equals(fromNode)) throw new ResumeConflictException("Task is not resumable from " + fromNode);
                verifyPrerequisites(task, pipeline, from, sources.findById(task.dataId()).orElseThrow());
                if (tasks.resume(task.taskId(), task.fence(), fromNode) != 1) throw new ResumeConflictException("Task changed concurrently");
                events.append(deliveryId, task.taskId(), null, task.deliveryEntityId(), task.dataId(), "TASK_RESUMED", "INFO",
                        "MANUAL_RESUME", reason, mapper.createObjectNode().put("taskId", task.taskId()).put("fromNode", fromNode),
                        operator, "resume-" + task.taskId() + "-" + task.fence());
                projections.refresh(deliveryId);
            });
        }
        return selected.size();
    }

    private void verifyPrerequisites(DeliveryTask task, PipelineDefinition pipeline, NodeDefinition from, SourceData source) {
        if (from.id().equals(pipeline.firstNode())) return;
        if (from.id().equals(pipeline.rolloverStartNode()) && source.sha256() != null && !source.artifacts().isEmpty()) return;
        String cursor = pipeline.firstNode();
        while (!cursor.equals(from.id())) {
            if (!nodes.succeededOutputs(task.taskId()).containsKey(cursor)) {
                throw new ResumeConflictException("Missing verified prerequisite node " + cursor);
            }
            cursor = pipeline.node(cursor).next();
        }
        if (source.sha256() == null) throw new ResumeConflictException("Missing verified artifact SHA");
    }

    private PipelineDefinition pipeline(long deliveryId) {
        try {
            return pipelines.read(mapper.readTree(jdbc.queryForObject("SELECT pv.definition::text FROM delivery_submit s " +
                    "JOIN pipeline_version pv ON pv.pipeline_version_id=s.pipeline_version_id WHERE s.delivery_id=?", String.class, deliveryId)));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid delivery PipelineVersion", e);
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason is required");
    }
}
