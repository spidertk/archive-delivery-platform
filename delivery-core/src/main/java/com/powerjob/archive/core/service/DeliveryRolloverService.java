package com.powerjob.archive.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.repository.OperationAuditRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class DeliveryRolloverService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final OperationAuditRepository audits;
    private final ObjectMapper mapper;
    private final Clock clock;

    public DeliveryRolloverService(JdbcTemplate jdbc, TransactionTemplate transactions,
                                   OperationAuditRepository audits, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.audits = audits;
        this.mapper = mapper;
        this.clock = clock;
    }

    public int rollover(long lineId) {
        return transactions.execute(status -> rolloverDue("p.production_line_id=? AND p.cutoff_at<=now()",
                new Object[]{lineId}, null, "legacy"));
    }

    public int rolloverRun(long runId, LocalDate expectedDeliveryDate, String reason) {
        if (expectedDeliveryDate == null) throw new IllegalArgumentException("expectedDeliveryDate is required");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason is required");
        return transactions.execute(status -> rolloverDue("sp.run_id=? AND p.delivery_date=?",
                new Object[]{runId, expectedDeliveryDate}, runId, reason));
    }

    public int autoRollover() {
        return transactions.execute(status -> rolloverDue("p.cutoff_at<=now() AND " +
                "pv.definition->'config'->>'autoRollover'='true'", new Object[]{}, null, "automatic"));
    }

    private int rolloverDue(String selector, Object[] args, Long auditRunId, String actor) {
        String sql = "SELECT p.id,p.delivery_date,p.rollover_count,p.run_status,p.current_node,p.production_group_id," +
                "p.group_seq,p.new_package_name,((p.delivery_date+2+l.delivery_cutoff_time) " +
                "AT TIME ZONE l.business_timezone) AS next_cutoff FROM output_package p " +
                "JOIN production_line l ON l.id=p.production_line_id JOIN source_package sp ON sp.id=p.source_package_id " +
                "JOIN pipeline_version pv ON pv.id=p.pipeline_version_id " +
                "WHERE " + selector + " AND p.status<>'DELIVERED' AND p.run_status<>'CANCELLED' " +
                "ORDER BY p.delivery_date,p.id FOR UPDATE OF p SKIP LOCKED LIMIT 500";
        List<Map<String, Object>> due = jdbc.queryForList(sql, args);
        int changed = 0;
        for (var output : due) {
            long id = ((Number) output.get("id")).longValue();
            int updated = jdbc.update("UPDATE output_package SET delivery_date=delivery_date+1,cutoff_at=?," +
                    "rollover_count=rollover_count+1,last_rollover_at=now(),updated_at=now() " +
                    "WHERE id=? AND delivery_date=? AND status<>'DELIVERED'",
                    output.get("next_cutoff"), id, output.get("delivery_date"));
            if (updated != 1) continue;
            long nodeRunId = jdbc.queryForObject("INSERT INTO package_node_run(output_package_id,node_id,node_type," +
                    "implementation,attempt,fence,status,worker_instance_id,finished_at) " +
                    "SELECT id,'rollover','delivery.rollover','delivery.rollover',rollover_count,fence,'SUCCEEDED',?,now() " +
                    "FROM output_package WHERE id=? RETURNING id", Long.class, actor, id);
            jdbc.update("INSERT INTO package_event(run_id,event_seq,event_type,message) VALUES (?,1,'DELIVERY_ROLLED_OVER',?)",
                    nodeRunId, actor);
            if (auditRunId != null) {
                audits.append(auditRunId, id, "ROLLOVER", actor, mapper.valueToTree(output),
                        mapper.createObjectNode().put("deliveryDate", java.time.LocalDate.parse(output.get("delivery_date").toString())
                                .plusDays(1).toString())
                                .put("rolledOver", true));
            }
            changed++;
        }
        return changed;
    }
}
