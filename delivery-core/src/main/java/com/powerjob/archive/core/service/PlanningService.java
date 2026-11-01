package com.powerjob.archive.core.service;

import com.powerjob.archive.core.domain.DeliveryRun;
import com.powerjob.archive.core.repository.DeliveryRunRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PlanningService {
    private static final long PLANNING_LOCK = 8_261_030_001L;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DeliveryRunRepository runs;

    public PlanningService(JdbcTemplate jdbc, TransactionTemplate transactions, DeliveryRunRepository runs) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.runs = runs;
    }

    public Map<String, Integer> plan(long runId, LocalDate businessDate) {
        return transactions.execute(status -> {
            jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> null, PLANNING_LOCK);
            DeliveryRun run = runs.findById(runId).orElseThrow(() -> new IllegalArgumentException("Unknown run " + runId));
            LocalDate date = businessDate == null ? run.jobDate() : businessDate;
            Map<String, Integer> planned = new LinkedHashMap<>();
            for (long lineId = 1; lineId <= 3; lineId++) {
                int quota = lineId == 1 ? 4 : 3;
                int reserved = jdbc.queryForObject("SELECT " +
                        "(SELECT count(*) FROM output_package p WHERE p.production_line_id=? " +
                        " AND p.run_status<>'CANCELLED' AND (p.delivery_date=? " +
                        " OR (p.delivery_date<? AND p.status<>'DELIVERED'))) + " +
                        "(SELECT count(*) FROM source_package s WHERE s.production_line_id=? AND s.process_date<=? " +
                        " AND s.status IN ('PLANNED','DOWNLOADED','REPAIRED') AND s.run_status<>'CANCELLED' " +
                        " AND NOT EXISTS (SELECT 1 FROM output_package o WHERE o.source_package_id=s.id))",
                        Integer.class, lineId, date, date, lineId, date);
                int count = planLine(runId, lineId, date, Math.max(0, quota - reserved));
                planned.put("LINE-" + lineId, count);
            }
            return planned;
        });
    }

    private int planLine(long runId, long lineId, LocalDate date, int available) {
        if (available == 0) return 0;
        List<Long> ids = jdbc.query("SELECT id FROM source_package WHERE run_id=? AND status='DISCOVERED' " +
                        "AND production_line_id IS NULL AND run_status='READY' " +
                        "ORDER BY discovered_at,id FOR UPDATE SKIP LOCKED LIMIT ?",
                (rs, rowNum) -> rs.getLong(1), runId, available);
        for (long id : ids) {
            jdbc.update("UPDATE source_package SET status='PLANNED',production_line_id=?,process_date=?," +
                    "planned_at=now(),updated_at=now() WHERE id=? AND status='DISCOVERED'", lineId, date, id);
        }
        return ids.size();
    }
}
