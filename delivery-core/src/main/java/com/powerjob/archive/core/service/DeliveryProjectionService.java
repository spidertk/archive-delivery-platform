package com.powerjob.archive.core.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class DeliveryProjectionService {
    private final JdbcTemplate jdbc;

    public DeliveryProjectionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 跨日、暂停和最终状态均只由任务事实投影，PowerJob 实例状态不参与交付结论。 */
    public void refresh(long deliveryId) {
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

    private static long number(Map<String, Object> facts, String key) { return ((Number) facts.get(key)).longValue(); }
}
