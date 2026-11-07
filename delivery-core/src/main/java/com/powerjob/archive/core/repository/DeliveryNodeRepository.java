package com.powerjob.archive.core.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.domain.DeliveryNode;
import com.powerjob.archive.core.domain.DeliveryTask;
import com.powerjob.archive.core.pipeline.NodeDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class DeliveryNodeRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DeliveryNodeRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 每次 claim 追加一个 attempt，文件名和 delivery/task 血缘均取任务快照。 */
    public long start(DeliveryTask task, NodeDefinition node, String workerId) {
        Integer attempt = jdbc.queryForObject("SELECT COALESCE(max(attempt),0)+1 FROM delivery_node WHERE task_id=? AND node_id=?",
                Integer.class, task.taskId(), node.id());
        return jdbc.queryForObject("INSERT INTO delivery_node(delivery_id,task_id,original_file_name,new_file_name," +
                        "node_id,node_type,stage_code,attempt,fence,status,worker_instance_id,heartbeat_at,input_locator) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,'RUNNING',?,now(),jsonb_build_object('schemaVersion',1,'nodeId',?)) RETURNING process_id",
                Long.class, task.deliveryId(), task.taskId(), task.originalFileName(), task.newFileName(), node.id(), node.type(),
                node.stageCode(), attempt, task.fence(), workerId, node.id());
    }

    public void finish(long processId, String status, JsonNode input, JsonNode output, String errorCode, String errorDetail) {
        jdbc.update("UPDATE delivery_node SET status=?,input_locator=COALESCE(?::jsonb,input_locator)," +
                "output_locator=COALESCE(?::jsonb,output_locator),error_code=?,error_detail=?,finished_at=now(),heartbeat_at=now() WHERE process_id=?",
                status, input == null ? null : input.toString(), output == null ? null : output.toString(),
                errorCode, errorDetail, processId);
    }

    public Map<String, JsonNode> succeededOutputs(long taskId) {
        Map<String, JsonNode> outputs = new LinkedHashMap<>();
        jdbc.query("SELECT DISTINCT ON(node_id) node_id,output_locator::text FROM delivery_node " +
                        "WHERE task_id=? AND status='SUCCEEDED' ORDER BY node_id,attempt DESC", rs -> {
            outputs.put(rs.getString(1), JdbcJson.read(mapper, rs.getString(2)));
        }, taskId);
        return Map.copyOf(outputs);
    }

    public List<DeliveryNode> timeline(long taskId) {
        return jdbc.query("SELECT * FROM delivery_node WHERE task_id=? ORDER BY started_at,process_id", (rs, n) ->
                new DeliveryNode(rs.getLong("process_id"), rs.getLong("delivery_id"), rs.getLong("task_id"),
                        rs.getString("original_file_name"), rs.getString("new_file_name"), rs.getString("node_id"),
                        rs.getString("node_type"), rs.getString("stage_code"), rs.getInt("attempt"), rs.getLong("fence"),
                        rs.getString("status"), rs.getString("worker_instance_id"),
                        JdbcJson.read(mapper, rs.getString("input_locator")), JdbcJson.read(mapper, rs.getString("output_locator")),
                        rs.getString("error_code"), rs.getString("error_detail"),
                        rs.getObject("started_at", java.time.OffsetDateTime.class),
                        rs.getObject("finished_at", java.time.OffsetDateTime.class)), taskId);
    }
}
