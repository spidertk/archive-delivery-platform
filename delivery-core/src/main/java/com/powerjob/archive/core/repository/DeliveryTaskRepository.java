package com.powerjob.archive.core.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.powerjob.archive.core.domain.DeliverySubmit;
import com.powerjob.archive.core.domain.DeliveryTask;
import com.powerjob.archive.core.domain.SourceData;
import com.powerjob.archive.core.pipeline.SourceDataPatch;
import com.powerjob.archive.core.service.RetryPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

@Repository
public class DeliveryTaskRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RowMapper<DeliveryTask> mapper = (rs, n) -> new DeliveryTask(
            rs.getLong("task_id"), rs.getLong("delivery_id"), rs.getLong("data_id"), rs.getLong("delivery_entity_id"),
            rs.getInt("target_seq"), rs.getInt("artifact_seq"), rs.getObject("delivery_date", java.time.LocalDate.class),
            rs.getObject("cutoff_at", java.time.OffsetDateTime.class), rs.getString("original_file_name"),
            rs.getString("new_file_name"), rs.getLong("size_bytes"), rs.getLong("token_count"), rs.getString("sha256"),
            rs.getString("current_node_id"), rs.getString("execution_status"), rs.getString("lease_owner"),
            rs.getObject("lease_until", java.time.OffsetDateTime.class), rs.getLong("fence"), rs.getInt("retry_count"),
            rs.getObject("next_retry_at", java.time.OffsetDateTime.class), rs.getString("last_error_code"),
            (Long) rs.getObject("superseded_by_task_id"), rs.getObject("invalidated_at", java.time.OffsetDateTime.class),
            rs.getString("invalidation_reason"), rs.getObject("completed_at", java.time.OffsetDateTime.class));

    public DeliveryTaskRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** 任务直接携带实体和目标内序号；唯一约束防止并发规划重复占用同一目标配额。 */
    public long createPlanned(DeliverySubmit submit, SourceData source, long deliveryEntityId, int targetSeq, String firstNode) {
        if (source.deliveryId() != submit.deliveryId() || deliveryEntityId <= 0 || targetSeq <= 0
                || !"QUALIFIED".equals(source.qualification()) && !"PENDING".equals(source.qualification())) {
            throw new IllegalArgumentException("Source/target delivery lineage or qualification is invalid");
        }
        JsonNode artifact = source.artifacts().required(0);
        int artifactSeq = artifact.required("artifactSeq").asInt();
        return transactions.execute(status -> jdbc.queryForObject("INSERT INTO delivery_task(delivery_id,data_id,delivery_entity_id,target_seq,artifact_seq," +
                        "delivery_date,cutoff_at,original_file_name,new_file_name,size_bytes,token_count,sha256,current_node_id) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING task_id", Long.class, submit.deliveryId(), source.dataId(),
                deliveryEntityId, targetSeq, artifactSeq, submit.deliveryDate(), submit.cutoffAt(), artifact.required("originalFileName").asText(),
                artifact.required("newFileName").asText(), artifact.required("sizeBytes").asLong(),
                artifact.required("token").asLong(), artifact.required("sha256").asText(), firstNode));
    }

    /** 有界全局并发配合 SKIP LOCKED；过期租约可重新 claim，fence 每次递增。 */
    public Optional<DeliveryTask> claimNext(String workerId, int maxRunning) {
        return transactions.execute(status -> {
            Integer running = jdbc.queryForObject("SELECT count(*) FROM delivery_task WHERE execution_status='RUNNING' AND lease_until>=now()", Integer.class);
            if (running >= maxRunning) return Optional.empty();
            return jdbc.query("WITH candidate AS (SELECT task_id FROM delivery_task WHERE " +
                            "execution_status='READY' OR (execution_status IN('RETRY_WAIT','WAITING') AND next_retry_at<=now()) " +
                            "OR (execution_status='RUNNING' AND lease_until<now()) ORDER BY delivery_date,task_id FOR UPDATE SKIP LOCKED LIMIT 1)," +
                            " claimed AS (UPDATE delivery_task t SET execution_status='RUNNING',lease_owner=?,lease_until=now()+interval '120 seconds'," +
                            "fence=t.fence+1,updated_at=now() FROM candidate c WHERE t.task_id=c.task_id RETURNING t.*) SELECT * FROM claimed",
                    mapper, workerId).stream().findFirst();
        });
    }

    public Optional<DeliveryTask> findById(long taskId) {
        return jdbc.query("SELECT * FROM delivery_task WHERE task_id=?", mapper, taskId).stream().findFirst();
    }

    public List<DeliveryTask> byDelivery(long deliveryId) {
        return jdbc.query("SELECT * FROM delivery_task WHERE delivery_id=? ORDER BY delivery_date,task_id", mapper, deliveryId);
    }

    public void complete(long taskId, long fence, String workerId, String nextNode,
                         SourceDataPatch patch, boolean terminal) {
        JsonNode artifact = patch == null || patch.artifacts() == null ? null : patch.artifacts().path(0);
        int changed = jdbc.update("UPDATE delivery_task SET current_node_id=?,execution_status=?," +
                "new_file_name=COALESCE(?,new_file_name),size_bytes=COALESCE(?,size_bytes),token_count=COALESCE(?,token_count)," +
                "sha256=COALESCE(?,sha256),lease_owner=NULL,lease_until=NULL,retry_count=0,next_retry_at=NULL,last_error_code=NULL," +
                "completed_at=CASE WHEN ? THEN COALESCE(completed_at,now()) ELSE completed_at END,updated_at=now() " +
                "WHERE task_id=? AND fence=? AND lease_owner=? AND execution_status='RUNNING'",
                nextNode, terminal ? "SUCCEEDED" : "READY", artifact == null ? null : artifact.path("newFileName").asText(null),
                artifact == null ? null : artifact.path("sizeBytes").asLong(), artifact == null ? null : artifact.path("token").asLong(),
                artifact == null ? null : artifact.path("sha256").asText(null), terminal, taskId, fence, workerId);
        if (changed != 1) throw new IllegalStateException("Stale task fence for task " + taskId);
    }

    public void markWaiting(long taskId, long fence, String workerId, long delaySeconds) {
        int changed = jdbc.update("UPDATE delivery_task SET execution_status='WAITING',next_retry_at=now()+?*interval '1 second'," +
                "lease_owner=NULL,lease_until=NULL,updated_at=now() WHERE task_id=? AND fence=? AND lease_owner=? AND execution_status='RUNNING'",
                Math.max(1, delaySeconds), taskId, fence, workerId);
        if (changed != 1) throw new IllegalStateException("Stale task fence for task " + taskId);
    }

    public void markFailure(long taskId, long fence, String workerId, RetryPolicy.Decision decision) {
        int changed = jdbc.update("UPDATE delivery_task SET execution_status=?,retry_count=?," +
                "next_retry_at=CASE WHEN ?=0 THEN NULL ELSE now()+?*interval '1 second' END,last_error_code=?," +
                "lease_owner=NULL,lease_until=NULL,updated_at=now() WHERE task_id=? AND fence=? AND lease_owner=? AND execution_status='RUNNING'",
                decision.taskStatus(), decision.retryCount(), decision.nextRetrySeconds(), decision.nextRetrySeconds(),
                decision.errorCode(), taskId, fence, workerId);
        if (changed != 1) throw new IllegalStateException("Stale task fence for task " + taskId);
    }

    public int retry(long taskId, long expectedFence) {
        return jdbc.update("UPDATE delivery_task SET execution_status='READY',next_retry_at=NULL,last_error_code=NULL," +
                "lease_owner=NULL,lease_until=NULL,updated_at=now() WHERE task_id=? AND fence=? AND execution_status IN('RETRY_WAIT','BLOCKED')",
                taskId, expectedFence);
    }

    public int resume(long taskId, long expectedFence, String fromNode) {
        return jdbc.update("UPDATE delivery_task SET execution_status='READY',current_node_id=?,next_retry_at=NULL,last_error_code=NULL," +
                "lease_owner=NULL,lease_until=NULL,updated_at=now() WHERE task_id=? AND fence=? AND execution_status IN('READY','RETRY_WAIT','WAITING','BLOCKED')",
                fromNode, taskId, expectedFence);
    }
}
