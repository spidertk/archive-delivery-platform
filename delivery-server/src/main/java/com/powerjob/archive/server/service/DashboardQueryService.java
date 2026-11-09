package com.powerjob.archive.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper mapper;

    public DashboardQueryService(JdbcTemplate jdbc, DeliveryTaskRepository tasks,
                                 DeliveryNodeRepository nodes, DeliveryEventRepository events, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.nodes = nodes;
        this.events = events;
        this.mapper = mapper;
        this.jdbc.setResultsMapCaseInsensitive(true);
    }

    /** 看板只统计最新有效任务，交付目标直接从提交快照和任务事实派生，不维护组状态。 */
    public Map<String, Object> summary(long deliveryId) {
        Map<String, Object> result = jsonRow(jdbc.queryForMap("SELECT s.status AS \"deliveryStatus\"," +
                "count(t.task_id) FILTER(WHERE t.execution_status NOT IN('EXPIRED','CANCELLED')) AS \"taskTotal\"," +
                "count(t.task_id) FILTER(WHERE t.execution_status='SUCCEEDED') AS succeeded," +
                "count(t.task_id) FILTER(WHERE t.execution_status='BLOCKED') AS blocked," +
                "count(t.task_id) FILTER(WHERE t.execution_status='RETRY_WAIT') AS retrying," +
                "count(t.task_id) FILTER(WHERE t.execution_status='WAITING') AS waiting," +
                "count(t.task_id) FILTER(WHERE t.execution_status='PAUSED') AS paused," +
                "count(t.task_id) FILTER(WHERE t.execution_status='EXPIRED') AS expired," +
                "(SELECT COALESCE(sum((target->>'targetCount')::int),0) FROM jsonb_array_elements(s.target_strategy->'targets') target) AS \"targetTotal\" " +
                "FROM delivery_submit s LEFT JOIN delivery_task t ON t.delivery_id=s.delivery_id " +
                "WHERE s.delivery_id=? GROUP BY s.delivery_id,s.status,s.target_strategy", deliveryId));
        result.put("targets", targets(deliveryId));
        return result;
    }

    public List<Map<String, Object>> targets(long deliveryId) {
        return jsonRows(jdbc.queryForList("WITH requested AS (SELECT s.delivery_id,target.value->>'vendorCode' AS vendor_code,target.value->>'entityCode' AS entity_code," +
                "(target.value->>'targetCount')::int AS target_count,target.target_order FROM delivery_submit s " +
                "CROSS JOIN LATERAL jsonb_array_elements(s.target_strategy->'targets') WITH ORDINALITY AS target(value,target_order) WHERE s.delivery_id=?) " +
                "SELECT e.delivery_entity_id AS \"deliveryEntityId\",e.vendor_code AS \"vendorCode\",r.entity_code AS \"entityCode\",r.target_count AS \"targetCount\"," +
                "count(t.task_id) FILTER(WHERE t.task_id IS NOT NULL) AS \"assignedCount\"," +
                "count(t.task_id) FILTER(WHERE t.execution_status='SUCCEEDED') AS \"completedCount\"," +
                "CASE WHEN r.target_count=count(t.task_id) FILTER(WHERE t.execution_status='SUCCEEDED') THEN 'DELIVERED' " +
                "WHEN count(t.task_id) FILTER(WHERE t.execution_status='PAUSED')>0 THEN 'PAUSED' " +
                "WHEN EXISTS(SELECT 1 FROM delivery_task h WHERE h.delivery_id=r.delivery_id AND h.delivery_entity_id=e.delivery_entity_id " +
                "AND h.execution_status='EXPIRED') THEN 'PARTIALLY_DELIVERED' " +
                "WHEN count(t.task_id) FILTER(WHERE t.execution_status='BLOCKED')>0 THEN 'BLOCKED' " +
                "WHEN count(t.task_id)>0 THEN 'RUNNING' ELSE 'FILLING' END AS status," +
                "e.entity_name AS \"entityName\",e.protocol,e.account_name AS \"accountName\"," +
                "e.worker_group AS \"workerGroup\",e.endpoint_locator AS \"endpointLocator\" FROM requested r " +
                "JOIN delivery_entity e ON e.vendor_code=r.vendor_code AND e.entity_code=r.entity_code " +
                "LEFT JOIN delivery_task t ON t.delivery_id=r.delivery_id AND t.delivery_entity_id=e.delivery_entity_id " +
                "AND t.execution_status NOT IN('EXPIRED','CANCELLED') " +
                "GROUP BY r.delivery_id,e.delivery_entity_id,e.vendor_code,r.entity_code,r.target_count,r.target_order ORDER BY r.target_order", deliveryId));
    }

    public Map<String, Object> delivery(long deliveryId) {
        Map<String, Object> result = jsonRow(jdbc.queryForMap("SELECT s.delivery_id AS \"deliveryId\"," +
                "s.delivery_batch AS \"deliveryBatch\",s.delivery_date AS \"deliveryDate\",s.cutoff_at AS \"cutoffAt\"," +
                "s.status,i.oss_uri AS \"sourceUri\",p.pipeline_code AS \"pipelineCode\",pv.version_no AS \"pipelineVersion\"," +
                "s.target_strategy AS \"targetStrategy\",s.submitted_at AS \"submittedAt\",s.discovery_completed_at AS \"discoveryCompletedAt\"," +
                "s.completed_at AS \"completedAt\" FROM delivery_submit s JOIN ingest_source i ON i.source_id=s.source_id " +
                "JOIN pipeline_version pv ON pv.pipeline_version_id=s.pipeline_version_id JOIN pipeline p ON p.pipeline_id=pv.pipeline_id " +
                "WHERE s.delivery_id=?", deliveryId));
        result.put("targets", targets(deliveryId));
        return result;
    }

    public Map<String, Object> tasks(long deliveryId, boolean includeHistory) {
        String history = includeHistory ? "" : " AND t.execution_status NOT IN('EXPIRED','CANCELLED')";
        List<Map<String, Object>> items = jsonRows(jdbc.queryForList(taskSelect() + " WHERE t.delivery_id=?" + history +
                " ORDER BY t.delivery_date,t.delivery_entity_id,t.task_id", deliveryId));
        return Map.of("items", items, "total", items.size());
    }

    public Map<String, Object> taskDetail(long taskId) { return jsonRow(jdbc.queryForMap(taskSelect() + " WHERE t.task_id=?", taskId)); }

    /** 时间轴同时返回 delivery_node 与不可变 event，locator 已由执行内核做 Secret/签名 URL 检查。 */
    public Map<String, Object> timeline(long taskId) {
        var task = tasks.findById(taskId).orElseThrow();
        return Map.of("taskId", taskId, "nodes", nodes.timeline(taskId), "events", events.timeline(task.deliveryId(), taskId));
    }

    private List<Map<String, Object>> jsonRows(List<Map<String, Object>> rows) {
        return rows.stream().map(this::jsonRow).toList();
    }

    private Map<String, Object> jsonRow(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> result.put(key, jsonValue(value)));
        return result;
    }

    private Object jsonValue(Object value) {
        if (value == null || !value.getClass().getName().equals("org.postgresql.util.PGobject")) return value;
        try {
            return mapper.readTree(value.toString());
        } catch (Exception error) {
            throw new IllegalStateException("Invalid JSONB value in dashboard projection", error);
        }
    }

    private static String taskSelect() {
        return "SELECT t.task_id AS \"taskId\",t.delivery_id AS \"deliveryId\"," +
                "t.delivery_entity_id AS \"deliveryEntityId\",t.entity_code AS \"entityCode\",t.worker_group AS \"workerGroup\",e.vendor_code AS \"vendorCode\"," +
                "e.entity_name AS \"entityName\",e.account_name AS \"accountName\",t.delivery_date AS \"deliveryDate\"," +
                "t.cutoff_at AS \"cutoffAt\",t.new_file_name AS \"newFileName\",t.size_bytes AS \"sizeBytes\"," +
                "t.token_count AS \"tokenCount\",(SELECT repair_node.output_locator->>'sha256' FROM delivery_node repair_node " +
                "WHERE repair_node.task_id=t.task_id AND repair_node.node_type='python.repair' AND repair_node.status='SUCCEEDED' " +
                "ORDER BY repair_node.attempt DESC LIMIT 1) AS sha256," +
                "t.artifacts,t.metadata_ids AS \"metadataIds\",t.current_node_id AS \"currentNodeId\"," +
                "t.execution_status AS \"executionStatus\",t.fence,t.retry_count AS \"retryCount\"," +
                "t.next_retry_at AS \"nextRetryAt\",t.last_error_code AS \"lastErrorCode\",t.completed_at AS \"completedAt\"," +
                "sd.qualification,(SELECT jsonb_agg(mapping.data_id ORDER BY mapping.source_seq) FROM delivery_mapping mapping " +
                "WHERE mapping.task_id=t.task_id) AS \"sourceDataIds\",node.stage_code AS \"stageCode\",node.display_name AS \"displayName\" " +
                "FROM delivery_task t JOIN delivery_entity e ON e.delivery_entity_id=t.delivery_entity_id " +
                "LEFT JOIN delivery_mapping primary_source ON primary_source.task_id=t.task_id AND primary_source.source_seq=1 " +
                "LEFT JOIN source_data sd ON sd.data_id=primary_source.data_id JOIN delivery_submit s ON s.delivery_id=t.delivery_id " +
                "JOIN pipeline_version pv ON pv.pipeline_version_id=s.pipeline_version_id " +
                "CROSS JOIN LATERAL(SELECT n->>'stageCode' AS stage_code,n->>'displayName' AS display_name " +
                "FROM jsonb_array_elements(pv.definition->'nodes') n WHERE n->>'id'=t.current_node_id) node";
    }
}
