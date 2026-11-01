package com.powerjob.archive.core.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DeliveryRolloverService {
    private final JdbcTemplate jdbc;

    public DeliveryRolloverService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int rollover(long lineId) {
        return jdbc.update("WITH due AS (" +
                " SELECT p.id,((p.delivery_date+2+l.delivery_cutoff_time) AT TIME ZONE l.business_timezone) AS next_cutoff " +
                " FROM output_package p JOIN production_line l ON l.id=p.production_line_id " +
                " WHERE p.production_line_id=? AND p.cutoff_at<=now() AND p.status<>'DELIVERED' " +
                " AND p.run_status<>'CANCELLED' ORDER BY p.delivery_date,p.id FOR UPDATE OF p SKIP LOCKED LIMIT 500) " +
                "UPDATE output_package p SET delivery_date=p.delivery_date+1,cutoff_at=due.next_cutoff," +
                "rollover_count=p.rollover_count+1,last_rollover_at=now(),updated_at=now() FROM due WHERE p.id=due.id",
                lineId);
    }
}
