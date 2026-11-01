package com.powerjob.archive.core.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NodeRunRepository {
    private final JdbcTemplate jdbc;

    public NodeRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long start(Long sourceId, Long outputId, String nodeId, String nodeType, long fence, String workerId) {
        Integer attempt = jdbc.queryForObject("SELECT COALESCE(max(attempt),0)+1 FROM package_node_run " +
                "WHERE ((source_package_id=? AND output_package_id IS NULL) OR " +
                "(output_package_id=? AND source_package_id IS NULL)) AND node_id=?",
                Integer.class, sourceId, outputId, nodeId);
        return jdbc.queryForObject("INSERT INTO package_node_run " +
                "(source_package_id,output_package_id,node_id,node_type,implementation,attempt,fence,status,worker_instance_id) " +
                "VALUES (?,?,?,?,?,?,?,'RUNNING',?) RETURNING id", Long.class,
                sourceId, outputId, nodeId, nodeType, nodeType, attempt, fence, workerId);
    }

    public void finish(long runId, String status, String manifest, String error) {
        jdbc.update("UPDATE package_node_run SET status=?,finished_at=now(),output_manifest=?::jsonb," +
                "error_detail=? WHERE id=?", status, manifest, error, runId);
        jdbc.update("INSERT INTO package_event(run_id,event_seq,event_type,message) VALUES (?,1,?,?)",
                runId, status, error == null ? status : error);
    }
}
