package com.powerjob.archive.core.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.powerjob.archive.core.domain.DeliveryEntity;
import com.powerjob.archive.core.domain.DeliverySubmit;
import com.powerjob.archive.core.domain.DeliveryTask;
import com.powerjob.archive.core.domain.SourceData;
import com.powerjob.archive.core.domain.WorkerGroups;
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
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RowMapper<DeliveryTask> mapper = (rs, n) -> new DeliveryTask(
            rs.getLong("task_id"), rs.getLong("delivery_id"), rs.getLong("delivery_entity_id"),
            rs.getString("entity_code"), rs.getString("worker_group"),
            rs.getObject("delivery_date", java.time.LocalDate.class),
            rs.getObject("cutoff_at", java.time.OffsetDateTime.class), rs.getString("new_file_name"),
            rs.getLong("size_bytes"), rs.getLong("token_count"), JdbcJson.read(JSON, rs.getString("artifacts")),
            JdbcJson.read(JSON, rs.getString("metadata_ids")), rs.getString("current_node_id"),
            rs.getString("execution_status"), rs.getString("lease_owner"),
            rs.getObject("lease_until", java.time.OffsetDateTime.class), rs.getLong("fence"), rs.getInt("retry_count"),
            rs.getObject("next_retry_at", java.time.OffsetDateTime.class), rs.getString("last_error_code"),
            rs.getObject("completed_at", java.time.OffsetDateTime.class));

    public DeliveryTaskRepository(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** 规划时只固化 dataId/sourceArtifact；packageSeq 在整个 delivery_id 内连续，最终 size/token 由 repair 补丁更新。 */
    public long createPlanned(DeliverySubmit submit, List<SourceData> sourceList, DeliveryEntity entity, int packageSeq, String firstNode) {
        if (sourceList == null || sourceList.isEmpty() || entity.deliveryEntityId() <= 0 || packageSeq <= 0) {
            throw new IllegalArgumentException("Task sources and package sequence are required");
        }
        ArrayNode artifactPairs = JSON.createArrayNode();
        for (SourceData source : sourceList) {
            if (source.deliveryId() != submit.deliveryId()
                    || !"QUALIFIED".equals(source.qualification()) && !"PENDING".equals(source.qualification())) {
                throw new IllegalArgumentException("Source/target delivery lineage or qualification is invalid");
            }
            var pair = artifactPairs.addObject();
            pair.put("dataId", source.dataId());
            pair.set("sourceArtifact", source.artifact().deepCopy());
        }
        String workerGroup = WorkerGroups.requireValid(entity.workerGroup());
        String newFileName = String.format("batch_%05d.tar.gz", packageSeq);
        long sizeBytes = sourceList.stream().mapToLong(SourceData::sizeBytes).sum();
        long tokenCount = sourceList.stream().mapToLong(SourceData::tokenCount).sum();
        return transactions.execute(status -> {
            long taskId = jdbc.queryForObject("INSERT INTO delivery_task(delivery_id,delivery_entity_id,entity_code,worker_group," +
                            "delivery_date,cutoff_at,new_file_name,size_bytes,token_count,artifacts,current_node_id) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,?) RETURNING task_id", Long.class, submit.deliveryId(),
                    entity.deliveryEntityId(), entity.entityCode(), workerGroup, submit.deliveryDate(), submit.cutoffAt(), newFileName,
                    sizeBytes, tokenCount, artifactPairs.toString(), firstNode);
            for (int index = 0; index < sourceList.size(); index++) {
                jdbc.update("INSERT INTO delivery_mapping(task_id,delivery_id,data_id,source_seq) VALUES(?,?,?,?)",
                        taskId, submit.deliveryId(), sourceList.get(index).dataId(), index + 1);
            }
            return taskId;
        });
    }

    /** 每台服务器只认领本组任务；PAUSED 不在可认领状态内，组内并发独立计数。 */
    public Optional<DeliveryTask> claimNext(String workerId, String workerGroup, int maxRunning) {
        WorkerGroups.requireValid(workerGroup);
        return transactions.execute(status -> {
            Integer running = jdbc.queryForObject("SELECT count(*) FROM delivery_task WHERE worker_group=? " +
                    "AND execution_status='RUNNING' AND lease_until>=now()", Integer.class, workerGroup);
            if (running >= maxRunning) return Optional.empty();
            return jdbc.query("WITH candidate AS (SELECT task_id FROM delivery_task WHERE worker_group=? AND (" +
                            "execution_status='READY' OR (execution_status IN('RETRY_WAIT','WAITING') AND next_retry_at<=now()) " +
                            "OR (execution_status='RUNNING' AND lease_until<now())) ORDER BY delivery_date,task_id FOR UPDATE SKIP LOCKED LIMIT 1)," +
                            " claimed AS (UPDATE delivery_task t SET execution_status='RUNNING',lease_owner=?,lease_until=now()+interval '120 seconds'," +
                            "fence=t.fence+1,updated_at=now() FROM candidate c WHERE t.task_id=c.task_id RETURNING t.*) SELECT * FROM claimed",
                    mapper, workerGroup, workerId).stream().findFirst();
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
        // repair 补丁显式携带最终 size/token，任务 artifacts 不再保存交付产物。
        // fence、租约和 RUNNING 条件保证只有当前持有者可提交节点结果。
        int changed = jdbc.update("UPDATE delivery_task SET current_node_id=?,execution_status=?," +
                "size_bytes=COALESCE(?,size_bytes),token_count=COALESCE(?,token_count)," +
                "artifacts=COALESCE(?::jsonb,artifacts),metadata_ids=COALESCE(?::jsonb,metadata_ids)," +
                "lease_owner=NULL,lease_until=NULL,retry_count=0,next_retry_at=NULL,last_error_code=NULL," +
                "completed_at=CASE WHEN ? THEN COALESCE(completed_at,now()) ELSE completed_at END,updated_at=now() " +
                "WHERE task_id=? AND fence=? AND lease_owner=? AND execution_status='RUNNING'",
                nextNode, terminal ? "SUCCEEDED" : "READY",
                patch == null ? null : patch.sizeBytes(), patch == null ? null : patch.tokenCount(),
                patch == null || patch.artifacts() == null ? null : patch.artifacts().toString(),
                patch == null || patch.metadataIds() == null ? null : patch.metadataIds().toString(),
                terminal, taskId, fence, workerId);
        // 晚到提交或租约丢失必须失败，不能推进其他执行纪元。
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
