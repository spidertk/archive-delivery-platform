package com.powerjob.archive.core.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.util.Map;

@Service
public class DeliveryProjectionService {
    private final JdbcTemplate jdbc;

    public DeliveryProjectionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 调用方必须已开启短事务，并在改任务/包/提交之前统一锁父批次，避免反向等待。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockBatch(long deliveryId) {
        jdbc.queryForObject("SELECT b.delivery_id FROM delivery_submit s JOIN delivery_submit b " +
                "ON b.delivery_id=COALESCE(s.batch_delivery_id,s.delivery_id) WHERE s.delivery_id=? FOR UPDATE OF b",
                Long.class, deliveryId);
    }

    /** 同批次的短事务投影共用父单行锁；禁止并发重写包状态产生锁顺序反转。 */
    @Transactional
    public void refresh(long deliveryId) {
        // 独立刷新也按同一顺序加锁；节点推进调用方已在写任务前持有此锁。
        lockBatch(deliveryId);
        Map<String, Object> identity = jdbc.queryForMap("SELECT submission_type,batch_delivery_id FROM delivery_submit WHERE delivery_id=?", deliveryId);
        Object batchId = identity.get("batch_delivery_id");
        if ("BATCH_CREATE".equals(identity.get("submission_type"))) {
            refreshBatch(deliveryId);
            return;
        }
        synchronizeBatchItems(deliveryId);
        refreshSubmission(deliveryId);
        if (batchId != null) refreshBatch(((Number) batchId).longValue());
    }

    private void refreshSubmission(long deliveryId) {
        Map<String, Object> facts = jdbc.queryForMap("SELECT s.status,s.discovery_completed_at," +
                "(SELECT COALESCE(sum((target->>'targetCount')::int),0) FROM jsonb_array_elements(s.target_strategy->'targets') target) AS target_total," +
                "(SELECT count(*) FROM delivery_task t WHERE t.delivery_id=s.delivery_id AND t.execution_status NOT IN('EXPIRED','CANCELLED')) AS active," +
                "(SELECT count(*) FROM delivery_task t WHERE t.delivery_id=s.delivery_id AND t.execution_status='SUCCEEDED') AS succeeded," +
                "(SELECT count(*) FROM delivery_task t WHERE t.delivery_id=s.delivery_id AND t.execution_status='EXPIRED') AS expired," +
                "(SELECT count(*) FROM delivery_task t WHERE t.delivery_id=s.delivery_id AND t.execution_status IN('READY','RUNNING')) AS processing," +
                "(SELECT count(*) FROM delivery_task t WHERE t.delivery_id=s.delivery_id AND t.execution_status='RETRY_WAIT') AS retrying," +
                "(SELECT count(*) FROM delivery_task t WHERE t.delivery_id=s.delivery_id AND t.execution_status='WAITING') AS waiting," +
                "(SELECT count(*) FROM delivery_task t WHERE t.delivery_id=s.delivery_id AND t.execution_status='BLOCKED') AS blocked," +
                "(SELECT count(*) FROM delivery_task t WHERE t.delivery_id=s.delivery_id AND t.execution_status='PAUSED') AS paused," +
                "(SELECT count(*) FROM delivery_task t WHERE t.delivery_id=s.delivery_id AND t.execution_status='CANCELLED') AS cancelled " +
                "FROM delivery_submit s WHERE s.delivery_id=?", deliveryId);
        String current = facts.get("status").toString();
        if ("CANCELLED".equals(current)) return;
        String next;
        if (facts.get("discovery_completed_at") == null) next = "DISCOVERING".equals(current) ? "DISCOVERING" : "SUBMITTED";
        else if (number(facts, "target_total") > 0 && number(facts, "active") == number(facts, "target_total")
                && number(facts, "active") == number(facts, "succeeded")) next = "DELIVERED";
        else if (number(facts, "active") == 0 && number(facts, "cancelled") > 0) next = "COMPLETED_WITH_CANCELLATIONS";
        else if (number(facts, "paused") > 0) next = "PAUSED";
        else if (number(facts, "expired") > 0) next = "PARTIALLY_DELIVERED";
        else if (number(facts, "blocked") > 0) next = "BLOCKED";
        else if (number(facts, "processing") > 0) next = "PROCESSING";
        else if (number(facts, "retrying") > 0) next = "RETRYING";
        else if (number(facts, "waiting") > 0) next = "WAITING";
        else next = "PLANNING";
        jdbc.update("UPDATE delivery_submit SET status=?,completed_at=CASE WHEN ? IN('DELIVERED','COMPLETED_WITH_CANCELLATIONS') " +
                "THEN COALESCE(completed_at,now()) ELSE NULL END,updated_at=now() WHERE delivery_id=?", next, next, deliveryId);
    }

    private void synchronizeBatchItems(long deliveryId) {
        jdbc.update("WITH package_state AS (" +
                "SELECT delivery_id,new_file_name," +
                "CASE WHEN bool_and(execution_status='SUCCEEDED') THEN 'DELIVERED' " +
                "WHEN bool_or(execution_status='BLOCKED') THEN 'BLOCKED' " +
                "WHEN bool_or(execution_status='PAUSED') THEN 'PAUSED' " +
                "WHEN bool_or(execution_status='RETRY_WAIT') THEN 'RETRYING' ELSE 'PROCESSING' END AS batch_status " +
                "FROM delivery_task WHERE delivery_id=? AND execution_status NOT IN('EXPIRED','CANCELLED') GROUP BY delivery_id,new_file_name) " +
                "UPDATE delivery_batch b SET status=p.batch_status," +
                "completed_at=CASE WHEN p.batch_status='DELIVERED' THEN COALESCE(b.completed_at,now()) ELSE NULL END " +
                // 已一致的包不重复写入，降低并发节点推进时的锁、WAL 和无效行版本。
                "FROM package_state p WHERE b.delivery_id=p.delivery_id AND b.new_file_name=p.new_file_name " +
                "AND b.status IS DISTINCT FROM p.batch_status", deliveryId);
    }

    private void refreshBatch(long batchDeliveryId) {
        Map<String, Object> facts = jdbc.queryForMap("SELECT s.status,s.discovery_completed_at," +
                "count(DISTINCT b.new_file_name) AS package_total," +
                "count(DISTINCT b.new_file_name) FILTER(WHERE b.status='DELIVERED') AS delivered," +
                "count(DISTINCT b.new_file_name) FILTER(WHERE b.status='BLOCKED') AS blocked," +
                "count(DISTINCT b.new_file_name) FILTER(WHERE b.status='PAUSED') AS paused " +
                "FROM delivery_submit s LEFT JOIN delivery_batch b ON b.batch_delivery_id=s.delivery_id " +
                "WHERE s.delivery_id=? AND s.submission_type='BATCH_CREATE' GROUP BY s.delivery_id", batchDeliveryId);
        if (facts.get("discovery_completed_at") == null) return;
        long total = number(facts, "package_total");
        jdbc.update("UPDATE delivery_submit SET batch_extension=jsonb_set(batch_extension,'{packageCount}'," +
                "to_jsonb((SELECT count(DISTINCT new_file_name) FROM delivery_batch WHERE batch_delivery_id=? AND status='DELIVERED')),true) " +
                "WHERE delivery_id=?", batchDeliveryId, batchDeliveryId);
        String next;
        if (total > 0 && number(facts, "delivered") == total) next = "DELIVERED";
        else if (number(facts, "blocked") > 0) next = "BLOCKED";
        else if (number(facts, "paused") > 0) next = "PAUSED";
        else next = "PROCESSING";
        jdbc.update("UPDATE delivery_submit SET status=?,completed_at=CASE WHEN ?='DELIVERED' THEN COALESCE(completed_at,now()) ELSE NULL END," +
                "updated_at=now() WHERE delivery_id=?", next, next, batchDeliveryId);
    }

    private static long number(Map<String, Object> facts, String key) { return ((Number) facts.get(key)).longValue(); }
}
