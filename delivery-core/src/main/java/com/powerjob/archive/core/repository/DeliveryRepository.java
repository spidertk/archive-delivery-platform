package com.powerjob.archive.core.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.domain.DeliveryEntity;
import com.powerjob.archive.core.domain.DeliverySubmit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class DeliveryRepository {
    private final JdbcTemplate jdbc;
    private final RowMapper<DeliverySubmit> submitMapper;
    private final RowMapper<DeliveryEntity> entityMapper;

    public DeliveryRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.submitMapper = (rs, n) -> new DeliverySubmit(
                rs.getLong("delivery_id"), rs.getString("delivery_batch"), rs.getLong("source_id"),
                rs.getLong("pipeline_version_id"), rs.getObject("delivery_date", java.time.LocalDate.class),
                rs.getObject("cutoff_at", java.time.OffsetDateTime.class),
                rs.getObject("submitted_at", java.time.OffsetDateTime.class),
                JdbcJson.read(mapper, rs.getString("target_strategy")), rs.getString("status"),
                rs.getObject("discovery_completed_at", java.time.OffsetDateTime.class),
                rs.getObject("completed_at", java.time.OffsetDateTime.class));
        this.entityMapper = (rs, n) -> new DeliveryEntity(
                rs.getLong("delivery_entity_id"), rs.getString("vendor_code"), rs.getString("entity_code"),
                rs.getString("entity_name"), rs.getString("protocol"), rs.getString("account_name"), rs.getString("worker_group"),
                JdbcJson.read(mapper, rs.getString("endpoint_locator")), rs.getBoolean("enabled"));
    }

    public Optional<DeliverySubmit> find(long deliveryId) {
        return jdbc.query("SELECT * FROM delivery_submit WHERE delivery_id=?", submitMapper, deliveryId).stream().findFirst();
    }

    public Optional<DeliverySubmit> findByDeliveryBatch(String deliveryBatch) {
        return jdbc.query("SELECT * FROM delivery_submit WHERE delivery_batch=?", submitMapper, deliveryBatch).stream().findFirst();
    }

    public DeliveryEntity entity(long entityId) {
        return jdbc.query("SELECT * FROM delivery_entity WHERE delivery_entity_id=?", entityMapper, entityId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown delivery entity " + entityId));
    }

    public DeliveryEntity entityByBusinessCode(String vendorCode, String entityCode) {
        return jdbc.query("SELECT * FROM delivery_entity WHERE vendor_code=? AND entity_code=?", entityMapper, vendorCode, entityCode)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "Unknown delivery entity " + vendorCode + "/" + entityCode));
    }

    public void markDiscovering(long deliveryId) {
        jdbc.update("UPDATE delivery_submit SET status='DISCOVERING',updated_at=now() " +
                "WHERE delivery_id=? AND status='SUBMITTED'", deliveryId);
    }

    public void markDiscoveryCompleted(long deliveryId) {
        jdbc.update("UPDATE delivery_submit SET discovery_completed_at=COALESCE(discovery_completed_at,now()),updated_at=now() " +
                "WHERE delivery_id=?", deliveryId);
    }
}
