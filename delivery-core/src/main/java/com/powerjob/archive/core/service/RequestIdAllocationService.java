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

    /** 行锁分配不重叠号段；repair 重试按 dataId/requestKey 复用，失败空洞不回收。 */
    public NumberRange reserve(long dataId, int count, String namespace) {
        if (count <= 0) throw new IllegalArgumentException("Allocation count must be positive");
        return transactions.execute(status -> {
            List<NumberRange> existing = jdbc.query("SELECT allocation_id,start_value,count_value FROM request_id_allocation " +
                            "WHERE request_namespace=? AND data_id=? AND request_key='repair'",
                    (rs, n) -> new NumberRange(rs.getLong(1), rs.getLong(2), rs.getInt(3)), namespace, dataId);
            if (!existing.isEmpty()) return existing.get(0);
            jdbc.update("INSERT INTO request_id_sequence(request_namespace,next_value) VALUES(?,1) ON CONFLICT DO NOTHING", namespace);
            Long start = jdbc.queryForObject("SELECT next_value FROM request_id_sequence WHERE request_namespace=? FOR UPDATE", Long.class, namespace);
            if (start + count - 1 > 999999999L) throw new IllegalStateException("Nine-digit request IDs exhausted");
            jdbc.update("UPDATE request_id_sequence SET next_value=?,updated_at=now() WHERE request_namespace=?", start + count, namespace);
            long id = jdbc.queryForObject("INSERT INTO request_id_allocation(request_namespace,data_id,request_key,start_value,count_value) " +
                    "VALUES(?,?,'repair',?,?) RETURNING allocation_id", Long.class, namespace, dataId, start, count);
            return new NumberRange(id, start, count);
        });
    }
}
