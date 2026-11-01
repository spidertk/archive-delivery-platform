package com.powerjob.archive.core.repository;

import com.powerjob.archive.core.domain.DeliveryRun;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DeliveryRunRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RowMapper<DeliveryRun> mapper = (rs, rowNum) -> new DeliveryRun(
            rs.getLong("id"), rs.getString("run_code"), rs.getString("oss_uri"),
            rs.getString("bucket_name"), rs.getString("object_prefix"), rs.getString("idempotency_key"),
            rs.getObject("job_date", LocalDate.class), rs.getLong("pipeline_version_id"), rs.getString("status"));

    public DeliveryRunRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public long create(String ossUri, String idempotencyKey, LocalDate jobDate, long pipelineVersionId) {
        URI uri = URI.create(ossUri);
        String bucket = uri.getHost();
        String prefix = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        return transactions.execute(status -> {
            String pendingCode = "RUN-PENDING-" + UUID.randomUUID();
            List<Long> inserted = jdbc.query("INSERT INTO delivery_run " +
                            "(run_code,oss_uri,bucket_name,object_prefix,idempotency_key,job_date,pipeline_version_id) " +
                            "VALUES (?,?,?,?,?,?,?) ON CONFLICT(idempotency_key) DO NOTHING RETURNING id",
                    (rs, rowNum) -> rs.getLong(1), pendingCode, ossUri, bucket, prefix,
                    idempotencyKey, jobDate, pipelineVersionId);
            long id = inserted.isEmpty() ? findByIdempotencyKey(idempotencyKey).orElseThrow().id() : inserted.get(0);
            if (!inserted.isEmpty()) {
                jdbc.update("UPDATE delivery_run SET run_code=?,updated_at=now() WHERE id=?",
                        "RUN-" + jobDate.toString().replace("-", "") + "-" + id, id);
            }
            return id;
        });
    }

    public Optional<DeliveryRun> findById(long id) {
        return jdbc.query("SELECT * FROM delivery_run WHERE id=?", mapper, id).stream().findFirst();
    }

    public Optional<DeliveryRun> findByIdempotencyKey(String key) {
        return jdbc.query("SELECT * FROM delivery_run WHERE idempotency_key=?", mapper, key).stream().findFirst();
    }
}
