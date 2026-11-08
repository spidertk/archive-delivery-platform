package com.powerjob.archive.server.service;

import com.powerjob.archive.core.repository.DeliveryEventRepository;
import com.powerjob.archive.core.repository.DeliveryNodeRepository;
import com.powerjob.archive.core.repository.DeliveryTaskRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardQueryService {
    private final JdbcTemplate jdbc;
    private final DeliveryTaskRepository tasks;
    private final DeliveryNodeRepository nodes;
    private final DeliveryEventRepository events;

    public DashboardQueryService(JdbcTemplate jdbc, DeliveryTaskRepository tasks,
                                 DeliveryNodeRepository nodes, DeliveryEventRepository events) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.nodes = nodes;
        this.events = events;
        this.jdbc.setResultsMapCaseInsensitive(true);
    }

    /** 看板只统计最新有效任务，交付目标直接从提交快照和任务事实派生，不维护组状态。 */
    public Map<String, Object> summary(long deliveryId) {
        Map<String, Object> result = new LinkedHashMap<>(jdbc.queryForMap("SELECT s.status AS \"deliveryStatus\"," +
                "count(t.task_id) FILTER(WHERE t.execution_status NOT IN('EXPIRED','CANCELLED')) AS \"taskTotal\"," +
                "count(t.task_id) FILTER(WHERE t.execution_status='SUCCEEDED') AS succeeded," +
                "count(t.task_id) FILTER(WHERE t.execution_status='BLOCKED') AS blocked," +
                "count(t.task_id) FILTER(WHERE t.execution_status='RETRY_WAIT') AS retrying," +
                "count(t.task_id) FILTER(WHERE t.execution_status='WAITING') AS waiting," +
                "count(t.task_id) FILTER(WHERE t.execution_status='EXPIRED') AS expired," +
                "(SELECT COALESCE(sum((target->>'targetCount')::int),0) FROM jsonb_array_elements(s.target_strategy->'targets') target) AS \"targetTotal\" " +
                "FROM delivery_submit s LEFT JOIN delivery_task t ON t.delivery_id=s.delivery_id " +
                "WHERE s.delivery_id=? GROUP BY s.delivery_id,s.status,s.target_strategy", deliveryId));
        result.put("targets", targets(deliveryId));
        return result;
    }

    public List<Map<String, Object>> targets(long deliveryId) {
        return jdbc.queryForList("WITH requested AS (SELECT s.delivery_id,(target.value->>'deliveryEntityId')::bigint AS delivery_entity_id," +
                "(target.value->>'targetCount')::int AS target_count,target.target_order FROM delivery_submit s " +
                "CROSS JOIN LATERAL jsonb_array_elements(s.target_strategy->'targets') WITH ORDINALITY AS target(value,target_order) WHERE s.delivery_id=?) " +
                "SELECT r.delivery_entity_id AS \"deliveryEntityId\",r.target_count AS \"targetCount\"," +
                "count(t.task_id) FILTER(WHERE t.task_id IS NOT NULL) AS \"assignedCount\"," +
                "count(t.task_id) FILTER(WHERE t.execution_status='SUCCEEDED') AS \"completedCount\"," +
                "CASE WHEN r.target_count=count(t.task_id) FILTER(WHERE t.execution_status='SUCCEEDED') THEN 'DELIVERED' " +
                "WHEN EXISTS(SELECT 1 FROM delivery_task h WHERE h.delivery_id=r.delivery_id AND h.delivery_entity_id=r.delivery_entity_id " +
                "AND h.execution_status='EXPIRED') THEN 'PARTIALLY_DELIVERED' " +
                "WHEN count(t.task_id) FILTER(WHERE t.execution_status='BLOCKED')>0 THEN 'BLOCKED' " +
                "WHEN count(t.task_id)>0 THEN 'RUNNING' ELSE 'FILLING' END AS status," +
                "e.vendor_code AS \"vendorCode\",e.entity_code AS \"entityCode\",e.entity_name AS \"entityName\"," +
                "e.protocol,e.account_name AS \"accountName\",e.worker_group AS \"workerGroup\",e.endpoint_locator AS \"endpointLocator\" FROM requested r " +
                "JOIN delivery_entity e ON e.delivery_entity_id=r.delivery_entity_id " +
                "LEFT JOIN delivery_task t ON t.delivery_id=r.delivery_id AND t.delivery_entity_id=r.delivery_entity_id " +
                "AND t.execution_status NOT IN('EXPIRED','CANCELLED') " +
                "GROUP BY r.delivery_entity_id,r.target_count,r.target_order,e.delivery_entity_id ORDER BY r.target_order", deliveryId);
    }

    public Map<String, Object> delivery(long deliveryId) {
        Map<String, Object> result = new LinkedHashMap<>(jdbc.queryForMap("SELECT s.delivery_id AS \"deliveryId\"," +
                "s.delivery_batch AS \"deliveryBatch\",s.delivery_date AS \"deliveryDate\",s.cutoff_at AS \"cutoffAt\"," +
                "s.status,s.source_id AS \"sourceId\",i.oss_uri AS \"sourceUri\",s.pipeline_version_id AS \"pipelineVersionId\"," +
                "pv.version_no AS \"pipelineVersion\",s.target_strategy AS \"targetStrategy\",s.submitted_at AS \"submittedAt\"," +
                "s.discovery_completed_at AS \"discoveryCompletedAt\",s.completed_at AS \"completedAt\" FROM delivery_submit s " +
                "JOIN ingest_source i ON i.source_id=s.source_id JOIN pipeline_version pv ON pv.pipeline_version_id=s.pipeline_version_id " +
                "WHERE s.delivery_id=?", deliveryId));
        result.put("targets", targets(deliveryId));
        return result;
    }

    public Map<String, Object> tasks(long deliveryId, boolean includeHistory) {
        String history = includeHistory ? "" : " AND t.execution_status NOT IN('EXPIRED','CANCELLED')";
        List<Map<String, Object>> items = jdbc.queryForList(taskSelect() + " WHERE t.delivery_id=?" + history +
                " ORDER BY t.delivery_date,t.delivery_entity_id,t.target_seq,t.task_id", deliveryId);
        return Map.of("items", items, "total", items.size());
    }

    public Map<String, Object> taskDetail(long taskId) { return jdbc.queryForMap(taskSelect() + " WHERE t.task_id=?", taskId); }

    /** 时间轴同时返回 delivery_node 与不可变 event，locator 已由执行内核做 Secret/签名 URL 检查。 */
    public Map<String, Object> timeline(long taskId) {
        var task = tasks.findById(taskId).orElseThrow();
        return Map.of("taskId", taskId, "nodes", nodes.timeline(taskId), "events", events.timeline(task.deliveryId(), taskId));
    }

    private static String taskSelect() {
        return "SELECT t.task_id AS \"taskId\",t.delivery_id AS \"deliveryId\",t.data_id AS \"dataId\"," +
                "t.delivery_entity_id AS \"deliveryEntityId\",t.worker_group AS \"workerGroup\",t.target_seq AS \"targetSeq\",e.vendor_code AS \"vendorCode\"," +
                "e.entity_name AS \"entityName\",e.account_name AS \"accountName\",t.artifact_seq AS \"artifactSeq\"," +
                "t.delivery_date AS \"deliveryDate\",t.cutoff_at AS \"cutoffAt\",t.original_file_name AS \"originalFileName\"," +
                "t.new_file_name AS \"newFileName\",t.size_bytes AS \"sizeBytes\",t.token_count AS \"tokenCount\",t.sha256," +
                "t.current_node_id AS \"currentNodeId\",t.execution_status AS \"executionStatus\",t.fence,t.retry_count AS \"retryCount\"," +
                "t.next_retry_at AS \"nextRetryAt\",t.last_error_code AS \"lastErrorCode\",t.superseded_by_task_id AS \"supersededByTaskId\"," +
                "t.invalidated_at AS \"invalidatedAt\",t.invalidation_reason AS \"invalidationReason\",t.completed_at AS \"completedAt\"," +
                "sd.qualification,sd.artifacts,node.stage_code AS \"stageCode\",node.display_name AS \"displayName\" " +
                "FROM delivery_task t JOIN delivery_entity e ON e.delivery_entity_id=t.delivery_entity_id " +
                "JOIN source_data sd ON sd.data_id=t.data_id JOIN delivery_submit s ON s.delivery_id=t.delivery_id " +
                "JOIN pipeline_version pv ON pv.pipeline_version_id=s.pipeline_version_id " +
                "CROSS JOIN LATERAL(SELECT n->>'stageCode' AS stage_code,n->>'displayName' AS display_name " +
                "FROM jsonb_array_elements(pv.definition->'nodes') n WHERE n->>'id'=t.current_node_id) node";
    }
}
