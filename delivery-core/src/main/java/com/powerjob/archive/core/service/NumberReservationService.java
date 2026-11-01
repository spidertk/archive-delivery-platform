package com.powerjob.archive.core.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
public class NumberReservationService {
    public record NumberRange(long reservationId, long start, int count) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public NumberReservationService(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public NumberRange reserve(long sourcePackageId, int count, String namespace) {
        if (count <= 0) throw new IllegalArgumentException("Reservation count must be positive");
        return transactions.execute(status -> {
            List<NumberRange> existing = jdbc.query("SELECT id,start_value,count_value FROM number_reservation " +
                            "WHERE namespace=? AND source_package_id=? AND request_key='repair'",
                    (rs, row) -> new NumberRange(rs.getLong(1), rs.getLong(2), rs.getInt(3)),
                    namespace, sourcePackageId);
            if (!existing.isEmpty()) return existing.get(0);
            Long start = jdbc.queryForObject("SELECT next_value FROM number_counter WHERE namespace=? FOR UPDATE",
                    Long.class, namespace);
            if (start == null) throw new IllegalStateException("Number counter is not initialized");
            if (start + count - 1 > 999999999L) throw new IllegalStateException("Nine-digit request IDs exhausted");
            jdbc.update("UPDATE number_counter SET next_value=? WHERE namespace=?", start + count, namespace);
            long reservationId = jdbc.queryForObject("INSERT INTO number_reservation " +
                    "(namespace,source_package_id,request_key,start_value,count_value) " +
                    "VALUES (?,?,'repair',?,?) RETURNING id", Long.class,
                    namespace, sourcePackageId, start, count);
            return new NumberRange(reservationId, start, count);
        });
    }
}
