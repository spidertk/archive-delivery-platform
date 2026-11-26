package com.powerjob.archive.core.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.powerjob.archive.core.domain.BatchPackage;
import com.powerjob.archive.core.domain.DeliveryEntity;
import com.powerjob.archive.core.domain.DeliverySubmit;
import com.powerjob.archive.core.domain.DeliveryTask;
import com.powerjob.archive.core.domain.SourceData;
import com.powerjob.archive.core.domain.WorkerGroups;
import com.powerjob.archive.core.pipeline.SourceDataPatch;
import com.powerjob.archive.core.service.RetryPolicy;
import com.powerjob.archive.core.service.FtpFileNameAllocator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

@Repository
public class DeliveryTaskRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DeliveryBatchRepository batches;
    private final SourceDataRepository sources;
    private final FtpFileNameAllocator ftpNames;
    private final RowMapper<DeliveryTask> mapper = (rs, n) -> new DeliveryTask(
            rs.getLong("task_id"), rs.getLong("delivery_id"), rs.getLong("delivery_entity_id"),
            rs.getString("entity_code"), rs.getString("worker_group"),
            rs.getObject("delivery_date", java.time.LocalDate.class),
            rs.getObject("cutoff_at", java.time.OffsetDateTime.class), rs.getString("new_file_name"),
            rs.getLong("size_bytes"), rs.getLong("token_count"), JdbcJson.read(JSON, rs.getString("artifacts")),
            rs.getString("current_node_id"), rs.getString("execution_status"), rs.getString("lease_owner"),
            rs.getObject("lease_until", java.time.OffsetDateTime.class), rs.getLong("fence"), rs.getInt("retry_count"),
            rs.getObject("next_retry_at", java.time.OffsetDateTime.class), rs.getString("last_error_code"),
            rs.getObject("completed_at", java.time.OffsetDateTime.class), rs.getString("ftp_file_name"), (Long) rs.getObject("ftp_sequence"));

    public DeliveryTaskRepository(JdbcTemplate jdbc, TransactionTemplate transactions,
                                  DeliveryBatchRepository batches, SourceDataRepository sources, FtpFileNameAllocator ftpNames) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.batches = batches;
        this.sources = sources;
        this.ftpNames = ftpNames;
    }

    public long createPlanned(DeliverySubmit submit, BatchPackage planned, DeliveryEntity entity, String firstNode) {
        if (submit.batchDeliveryId() == null || planned == null || entity.deliveryEntityId() <= 0) {
            throw new IllegalArgumentException("Batch package and target entity are required");
        }
        return transactions.execute(status -> {
            // 重分配和领取使用相同父单锁；读取来源事实必须在锁内，不能沿用锁外旧归属。
            jdbc.queryForObject("SELECT delivery_id FROM delivery_submit WHERE delivery_id=? FOR UPDATE", Long.class, submit.batchDeliveryId());
            List<SourceData> sourceList = sources.byBatchPackage(submit.batchDeliveryId(), planned.newFileName());
            if (sourceList.size() != planned.sourceCount() || sourceList.isEmpty()) {
                throw new IllegalStateException("Batch package source facts are incomplete");
            }
            ArrayNode artifacts = JSON.createArrayNode();
            for (SourceData source : sourceList) {
                var pair = artifacts.addObject();
                pair.put("dataId", source.dataId());
                pair.set("sourceArtifact", source.artifact().deepCopy());
            }
            String workerGroup = WorkerGroups.requireValid(planned.workerGroup());
            int assigned = batches.assign(submit.batchDeliveryId(), planned.newFileName(), submit.deliveryId());
            if (assigned != planned.sourceCount()) throw new IllegalStateException("Batch package was consumed concurrently");
            // 新 FTP 名称按实际交付日统一预留，不能重用批次内序号作为渠道序号。
            var ftpName = ftpNames.allocate(submit.deliveryDate(), entity.deliveryEntityId());
            return jdbc.queryForObject("INSERT INTO delivery_task(delivery_id,delivery_entity_id,entity_code,worker_group," +
                            "delivery_date,cutoff_at,new_file_name,size_bytes,token_count,artifacts,current_node_id,ftp_file_name,ftp_sequence) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?) RETURNING task_id", Long.class, submit.deliveryId(),
                    entity.deliveryEntityId(), entity.entityCode(), workerGroup, submit.deliveryDate(), submit.cutoffAt(),
                    planned.newFileName(), planned.sourceBytes(), sourceList.stream().mapToLong(SourceData::tokenCount).sum(),
                    artifacts.toString(), firstNode, ftpName.fileName(), ftpName.sequence());
        });
    }

    public Optional<DeliveryTask> claimNext(String workerId, String workerGroup, int maxRunning, int leaseSeconds) {
        WorkerGroups.requireValid(workerGroup);
        if (leaseSeconds < 120) throw new IllegalArgumentException("Task lease must be at least 120 seconds");
        return transactions.execute(status -> {
            // 同组 Worker 先串行检查容量再 claim，避免并发读到旧计数而突破机器上限。
            jdbc.queryForList("SELECT pg_advisory_xact_lock(730011,hashtext(?))", workerGroup);
            Integer running = jdbc.queryForObject("SELECT count(*) FROM delivery_task WHERE worker_group=? " +
                    "AND execution_status='RUNNING' AND lease_until>=now()", Integer.class, workerGroup);
            if (running >= maxRunning) return Optional.empty();
            return jdbc.query("WITH candidate AS (SELECT task_id FROM delivery_task WHERE worker_group=? AND (" +
                            "execution_status='READY' OR (execution_status IN('RETRY_WAIT','WAITING') AND next_retry_at<=now()) " +
                            "OR (execution_status='RUNNING' AND lease_until<now())) ORDER BY delivery_date,task_id FOR UPDATE SKIP LOCKED LIMIT 1)," +
                            " claimed AS (UPDATE delivery_task t SET execution_status='RUNNING',lease_owner=?,lease_until=now()+?*interval '1 second'," +
                            "fence=t.fence+1,updated_at=now() FROM candidate c WHERE t.task_id=c.task_id RETURNING t.*) SELECT * FROM claimed",
                    mapper, workerGroup, workerId, leaseSeconds).stream().findFirst();
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
        int changed = jdbc.update("UPDATE delivery_task SET current_node_id=?,execution_status=?," +
                "size_bytes=COALESCE(?,size_bytes),token_count=COALESCE(?,token_count)," +
                "artifacts=COALESCE(?::jsonb,artifacts)," +
                "lease_owner=NULL,lease_until=NULL,retry_count=0,next_retry_at=NULL,last_error_code=NULL," +
                "completed_at=CASE WHEN ? THEN COALESCE(completed_at,now()) ELSE completed_at END,updated_at=now() " +
                "WHERE task_id=? AND fence=? AND lease_owner=? AND execution_status='RUNNING'",
                nextNode, terminal ? "SUCCEEDED" : "READY",
                patch == null ? null : patch.sizeBytes(), patch == null ? null : patch.tokenCount(),
                patch == null || patch.artifacts() == null ? null : patch.artifacts().toString(),
                terminal, taskId, fence, workerId);
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
                "lease_owner=NULL,lease_until=NULL,updated_at=now() WHERE task_id=? AND fence=? AND execution_status IN('READY','RETRY_WAIT','WAITING','BLOCKED','PAUSED')",
                fromNode, taskId, expectedFence);
    }

    public int pause(long deliveryId) {
        return jdbc.update("UPDATE delivery_task SET execution_status='PAUSED',next_retry_at=NULL,updated_at=now() " +
                "WHERE delivery_id=? AND execution_status IN('READY','WAITING','RETRY_WAIT','BLOCKED')", deliveryId);
    }

    public int resumePaused(long deliveryId) {
        return jdbc.update("UPDATE delivery_task SET execution_status='READY',next_retry_at=NULL,last_error_code=NULL,updated_at=now() " +
                "WHERE delivery_id=? AND execution_status='PAUSED'", deliveryId);
    }
}
