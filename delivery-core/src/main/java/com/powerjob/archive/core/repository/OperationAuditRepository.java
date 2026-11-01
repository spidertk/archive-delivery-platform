package com.powerjob.archive.core.repository;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Set;

@Repository
public class OperationAuditRepository {
    private static final Set<String> ACTIONS = Set.of("RESUME", "RETRY", "ROLLOVER");
    private final JdbcTemplate jdbc;

    public OperationAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long append(long runId, Long outputPackageId, String action, String reason,
                       JsonNode before, JsonNode after) {
        if (!ACTIONS.contains(action)) {
            throw new IllegalArgumentException("Unsupported audit action: " + action);
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Audit reason is required");
        }
        return jdbc.queryForObject("INSERT INTO operation_audit " +
                        "(run_id,output_package_id,action,reason,before_content,after_content) " +
                        "VALUES (?,?,?,?,?::jsonb,?::jsonb) RETURNING id", Long.class,
                runId, outputPackageId, action, reason, before == null ? null : before.toString(),
                after == null ? null : after.toString());
    }
}
