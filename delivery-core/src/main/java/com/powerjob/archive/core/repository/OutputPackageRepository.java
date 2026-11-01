package com.powerjob.archive.core.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.domain.OutputPackage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

@Repository
public class OutputPackageRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RowMapper<OutputPackage> mapper;

    public OutputPackageRepository(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = (rs, rowNum) -> new OutputPackage(
                rs.getLong("id"), rs.getLong("source_package_id"), rs.getLong("pipeline_version_id"),
                JdbcJson.read(objectMapper, rs.getString("definition_snapshot")),
                rs.getLong("production_group_id"), rs.getLong("production_line_id"),
                rs.getString("archive_date"), rs.getString("delivery_date"), rs.getInt("group_seq"),
                rs.getString("new_package_name"), rs.getString("status"), rs.getString("run_status"),
                rs.getString("current_node"), rs.getString("lease_owner"), rs.getLong("fence"),
                JdbcJson.read(objectMapper, rs.getString("manifest")));
    }

    public Optional<OutputPackage> claimNext(String workerId) {
        return transactions.execute(status -> {
            List<OutputPackage> result = jdbc.query("WITH candidate AS (" +
                    " SELECT id FROM output_package WHERE " +
                    " (run_status='READY' OR (run_status IN ('RETRY_WAIT','WAITING') AND next_retry_at<=now())) " +
                    " AND status<>'DELIVERED' ORDER BY (rollover_count>0) DESC,delivery_date,id " +
                    " FOR UPDATE SKIP LOCKED LIMIT 1), claimed AS (" +
                    " UPDATE output_package p SET run_status='RUNNING',lease_owner=?,lease_until=now()+interval '90 seconds'," +
                    " fence=p.fence+1,updated_at=now() FROM candidate c WHERE p.id=c.id RETURNING p.*) " +
                    "SELECT c.*,sp.definition_snapshot FROM claimed c " +
                    "JOIN source_package sp ON sp.id=c.source_package_id", mapper, workerId);
            return result.stream().findFirst();
        });
    }

    public Optional<OutputPackage> findById(long id) {
        return jdbc.query("SELECT op.*,sp.definition_snapshot FROM output_package op " +
                "JOIN source_package sp ON sp.id=op.source_package_id WHERE op.id=?", mapper, id).stream().findFirst();
    }

    public void complete(long id, long fence, String workerId, String businessStatus,
                         String nextNode, String manifest, boolean terminal) {
        int changed = jdbc.update("UPDATE output_package SET status=?,current_node=?,run_status=?," +
                "manifest=COALESCE(?::jsonb,manifest),lease_owner=NULL,lease_until=NULL," +
                "retry_count=0,next_retry_at=NULL,last_error_code=NULL," +
                "delivered_at=CASE WHEN ? THEN now() ELSE delivered_at END,updated_at=now() " +
                "WHERE id=? AND fence=? AND lease_owner=? AND run_status='RUNNING'",
                businessStatus, nextNode, terminal ? "SUCCEEDED" : "READY", manifest, terminal,
                id, fence, workerId);
        if (changed != 1) throw new IllegalStateException("Stale output fence for package " + id);
    }

    public void updatePath(long id, String column, String path) {
        if (!List.of("hz_path", "sg_source_path", "sg_download_path", "published_path", "receipt_id").contains(column)) {
            throw new IllegalArgumentException("Unsupported output path column");
        }
        jdbc.update("UPDATE output_package SET " + column + "=?,updated_at=now() WHERE id=?", path, id);
    }

    public void updateArtifactPath(long outputId, String column, String path) {
        if (!List.of("hz_path", "sg_path", "published_path").contains(column)) {
            throw new IllegalArgumentException("Unsupported artifact path column");
        }
        jdbc.update("UPDATE output_artifact SET " + column + "=? WHERE output_package_id=? AND artifact_seq=1",
                path, outputId);
    }

    public void markWaiting(long id, long fence, String workerId, String manifest, java.time.Duration nextPoll) {
        long seconds = Math.max(1, nextPoll.toSeconds());
        int changed = jdbc.update("UPDATE output_package SET run_status='WAITING',manifest=COALESCE(?::jsonb,manifest)," +
                "next_retry_at=now()+?*interval '1 second',lease_owner=NULL,lease_until=NULL,updated_at=now() " +
                "WHERE id=? AND fence=? AND lease_owner=? AND run_status='RUNNING'",
                manifest, seconds, id, fence, workerId);
        if (changed != 1) throw new IllegalStateException("Stale output fence for package " + id);
    }

    public void markRetry(long id, long fence, String workerId, String errorCode) {
        jdbc.update("UPDATE output_package SET run_status='RETRY_WAIT',retry_count=retry_count+1," +
                "next_retry_at=now()+interval '5 seconds',last_error_code=?,lease_owner=NULL," +
                "lease_until=NULL,updated_at=now() WHERE id=? AND fence=? AND lease_owner=?",
                errorCode, id, fence, workerId);
    }

    public long countDelivered() {
        return jdbc.queryForObject("SELECT count(*) FROM output_package WHERE status='DELIVERED'", Long.class);
    }
}
