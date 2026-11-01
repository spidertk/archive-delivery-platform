package com.powerjob.archive.core.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.domain.DiscoveredObject;
import com.powerjob.archive.core.domain.SourcePackage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

@Repository
public class SourcePackageRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RowMapper<SourcePackage> mapper;

    public SourcePackageRepository(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = (rs, rowNum) -> new SourcePackage(
                rs.getLong("id"), rs.getLong("pipeline_version_id"),
                JdbcJson.read(objectMapper, rs.getString("definition_snapshot")),
                rs.getString("bucket_name"), rs.getString("object_key"), rs.getString("object_version"),
                rs.getLong("source_size"), rs.getString("source_sha256"), rs.getString("status"), rs.getString("run_status"),
                rs.getLong("production_line_id"), rs.getString("current_node"),
                rs.getString("lease_owner"), rs.getLong("fence"),
                JdbcJson.read(objectMapper, rs.getString("manifest")));
    }

    public long upsertPlanned(DiscoveredObject object, String snapshot, String processDate) {
        List<Long> inserted = jdbc.query("INSERT INTO source_package " +
                "(source_id,pipeline_version_id,definition_snapshot,bucket_name,object_key,object_version," +
                "object_etag,source_size,status,run_status,production_line_id,process_date,planned_at) " +
                "VALUES (1,1,?::jsonb,?,?,?,?,?,'PLANNED','READY',?,?::date,now()) " +
                "ON CONFLICT(bucket_name,object_key,object_version) DO NOTHING RETURNING id",
                (rs, n) -> rs.getLong(1), snapshot, object.bucketName(), object.objectKey(),
                object.objectVersion(), object.etag(), object.size(), object.productionLineId(), processDate);
        if (!inserted.isEmpty()) return inserted.get(0);
        return jdbc.queryForObject("SELECT id FROM source_package WHERE bucket_name=? AND object_key=? AND object_version=?",
                Long.class, object.bucketName(), object.objectKey(), object.objectVersion());
    }

    public long upsertDiscovered(long runId, DiscoveredObject object) {
        List<Long> inserted = jdbc.query("INSERT INTO source_package " +
                        "(source_id,pipeline_version_id,definition_snapshot,bucket_name,object_key,object_version," +
                        "object_etag,source_size,status,run_status,run_id) " +
                        "SELECT s.id,r.pipeline_version_id,pv.definition,?,?,?,?,?,'DISCOVERED','READY',r.id " +
                        "FROM delivery_run r JOIN pipeline_version pv ON pv.id=r.pipeline_version_id " +
                        "JOIN ingest_source s ON s.bucket_ref=r.bucket_name AND s.object_prefix=r.object_prefix " +
                        "WHERE r.id=? ON CONFLICT(bucket_name,object_key,object_version) DO NOTHING RETURNING id",
                (rs, n) -> rs.getLong(1), object.bucketName(), object.objectKey(), object.objectVersion(),
                object.etag(), object.size(), runId);
        if (!inserted.isEmpty()) return inserted.get(0);
        return jdbc.queryForObject("SELECT id FROM source_package WHERE bucket_name=? AND object_key=? AND object_version=?",
                Long.class, object.bucketName(), object.objectKey(), object.objectVersion());
    }

    public Optional<SourcePackage> claimNext(long lineId, String workerId) {
        return transactions.execute(status -> {
            jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> null, 8_261_031_000L + lineId);
            List<SourcePackage> result = jdbc.query("WITH candidate AS (" +
                    " SELECT id FROM source_package p WHERE production_line_id=? " +
                    " AND (run_status='READY' OR (run_status='RETRY_WAIT' AND next_retry_at<=now())) " +
                    " AND NOT EXISTS (SELECT 1 FROM source_package r WHERE r.production_line_id=p.production_line_id " +
                    " AND r.run_status='RUNNING' AND r.lease_until>=now()) " +
                    " ORDER BY process_date,id FOR UPDATE SKIP LOCKED LIMIT 1), claimed AS (" +
                    " UPDATE source_package p SET run_status='RUNNING',lease_owner=?,lease_until=now()+interval '90 seconds'," +
                    " fence=p.fence+1,updated_at=now() FROM candidate c WHERE p.id=c.id RETURNING p.*) " +
                    "SELECT * FROM claimed", mapper, lineId, workerId);
            return result.stream().findFirst();
        });
    }

    public Optional<SourcePackage> findById(long id) {
        return jdbc.query("SELECT * FROM source_package WHERE id=?", mapper, id).stream().findFirst();
    }

    public void complete(long id, long fence, String workerId, String businessStatus,
                         String nextNode, String manifest, boolean terminal) {
        int changed = jdbc.update("UPDATE source_package SET status=?,current_node=?,run_status=?," +
                "manifest=COALESCE(?::jsonb,manifest),lease_owner=NULL,lease_until=NULL," +
                "retry_count=0,next_retry_at=NULL,last_error_code=NULL,updated_at=now() " +
                "WHERE id=? AND fence=? AND lease_owner=? AND run_status='RUNNING'",
                businessStatus, nextNode, terminal ? "SUCCEEDED" : "READY", manifest, id, fence, workerId);
        if (changed != 1) throw new IllegalStateException("Stale source fence for package " + id);
    }

    public void markRetry(long id, long fence, String workerId, String errorCode) {
        jdbc.update("UPDATE source_package SET run_status='RETRY_WAIT',retry_count=retry_count+1," +
                "next_retry_at=now()+interval '1 second',last_error_code=?,lease_owner=NULL," +
                "lease_until=NULL,updated_at=now() WHERE id=? AND fence=? AND lease_owner=?",
                errorCode, id, fence, workerId);
    }
}
