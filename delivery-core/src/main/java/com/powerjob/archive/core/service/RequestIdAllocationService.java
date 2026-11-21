package com.powerjob.archive.core.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
public class RequestIdAllocationService {
    public record NumberRange(long allocationId, long start, int count) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public RequestIdAllocationService(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public NumberRange reserve(long dataId, int count, String namespace, String requestKey) {
        if (count <= 0 || requestKey == null || requestKey.isBlank() || requestKey.length() > 64) {
            throw new IllegalArgumentException("Allocation count and a bounded package requestKey are required");
        }
        return transactions.execute(status -> {
            List<NumberRange> existing = jdbc.query("SELECT allocation_id,start_value,count_value FROM request_id_allocation " +
                            "WHERE request_namespace=? AND data_id=? AND request_key=?",
                    (rs, n) -> new NumberRange(rs.getLong(1), rs.getLong(2), rs.getInt(3)), namespace, dataId, requestKey);
            NumberRange previous = existing.isEmpty() ? null : existing.get(0);
            if (previous != null && previous.count() >= count) return previous;
            jdbc.update("INSERT INTO request_id_sequence(request_namespace,next_value) VALUES(?,1) ON CONFLICT DO NOTHING", namespace);
            Long start = jdbc.queryForObject("SELECT next_value FROM request_id_sequence WHERE request_namespace=? FOR UPDATE", Long.class, namespace);
            if (start + count - 1 > 999999999L) throw new IllegalStateException("Nine-digit request IDs exhausted");
            jdbc.update("UPDATE request_id_sequence SET next_value=?,updated_at=now() WHERE request_namespace=?", start + count, namespace);
            if (previous == null) {
                long id = jdbc.queryForObject("INSERT INTO request_id_allocation(request_namespace,data_id,request_key,start_value,count_value) " +
                        "VALUES(?,?,?,?,?) RETURNING allocation_id", Long.class, namespace, dataId, requestKey, start, count);
                return new NumberRange(id, start, count);
            }
            jdbc.update("UPDATE request_id_allocation SET start_value=?,count_value=? WHERE allocation_id=?", start, count, previous.allocationId());
            return new NumberRange(previous.allocationId(), start, count);
        });
    }
}
