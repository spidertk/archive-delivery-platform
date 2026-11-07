package com.powerjob.archive.core.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.domain.DeliveryEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class DeliveryEventRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DeliveryEventRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 事件只追加且按提交内 dedupKey 幂等；实体关系直接记录，调用方不得把 Secret 放入 payload。 */
    public void append(long deliveryId, Long taskId, Long processId, Long deliveryEntityId, Long dataId,
                       String type, String severity, String reasonCode, String message,
                       JsonNode payload, String operatorId, String dedupKey) {
        jdbc.update("INSERT INTO delivery_event(delivery_id,task_id,process_id,delivery_entity_id,data_id,event_type,severity," +
                        "reason_code,message,payload,operator_id,dedup_key) VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,?,?) ON CONFLICT DO NOTHING",
                deliveryId, taskId, processId, deliveryEntityId, dataId, type, severity, reasonCode, message,
                payload == null ? "{}" : payload.toString(), operatorId, dedupKey);
    }

    public List<DeliveryEvent> timeline(long deliveryId, long taskId) {
        return jdbc.query("SELECT * FROM delivery_event WHERE delivery_id=? AND (task_id=? OR task_id IS NULL) ORDER BY occurred_at,event_id",
                (rs, n) -> new DeliveryEvent(rs.getLong("event_id"), rs.getLong("delivery_id"),
                        (Long) rs.getObject("task_id"), (Long) rs.getObject("process_id"), (Long) rs.getObject("delivery_entity_id"),
                        (Long) rs.getObject("data_id"), rs.getString("event_type"), rs.getString("severity"),
                        rs.getString("reason_code"), rs.getString("message"), JdbcJson.read(mapper, rs.getString("payload")),
                        rs.getString("operator_id"), rs.getObject("occurred_at", java.time.OffsetDateTime.class)), deliveryId, taskId);
    }
}
