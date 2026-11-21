package com.powerjob.archive.core.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.domain.BatchPackage;
import com.powerjob.archive.core.domain.DeliveryBatchItem;
import com.powerjob.archive.core.domain.SourceData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class DeliveryBatchRepository {
    private final JdbcTemplate jdbc;
    private final RowMapper<DeliveryBatchItem> itemMapper = (rs, n) -> new DeliveryBatchItem(
            rs.getLong("batch_item_id"), rs.getLong("batch_delivery_id"), rs.getLong("data_id"),
            rs.getString("delivery_batch"), rs.getString("original_file_name"), rs.getString("new_file_name"),
            rs.getInt("source_seq"), rs.getString("status"), rs.getLong("delivery_entity_id"),
            rs.getString("entity_code"), rs.getString("worker_group"), (Long) rs.getObject("delivery_id"),
            rs.getObject("created_at", java.time.OffsetDateTime.class),
            rs.getObject("started_at", java.time.OffsetDateTime.class),
            rs.getObject("completed_at", java.time.OffsetDateTime.class));

    public DeliveryBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPlanned(long batchDeliveryId, SourceData source, String deliveryBatch, String originalFileName,
                              String newFileName, int sourceSeq, long entityId, String entityCode, String workerGroup) {
        jdbc.update("INSERT INTO delivery_batch(batch_delivery_id,data_id,delivery_batch,original_file_name,new_file_name," +
                        "source_seq,delivery_entity_id,entity_code,worker_group) VALUES(?,?,?,?,?,?,?,?,?)",
                batchDeliveryId, source.dataId(), deliveryBatch, originalFileName, newFileName, sourceSeq,
                entityId, entityCode, workerGroup);
    }

    public List<BatchPackage> availablePackages(long batchDeliveryId, long entityId, int limit) {
        return jdbc.query("SELECT new_file_name,worker_group,count(*) AS source_count,sum(sd.size_bytes) AS source_bytes " +
                        "FROM delivery_batch b JOIN source_data sd ON sd.data_id=b.data_id " +
                        "WHERE b.batch_delivery_id=? AND b.delivery_entity_id=? AND b.delivery_id IS NULL AND b.status='PLANNED' " +
                        "GROUP BY b.new_file_name,b.worker_group ORDER BY b.new_file_name LIMIT ?",
                (rs, n) -> new BatchPackage(rs.getString("new_file_name"), rs.getString("worker_group"),
                        rs.getInt("source_count"), rs.getLong("source_bytes")), batchDeliveryId, entityId, limit);
    }

    public List<SourceData> sources(long batchDeliveryId, String newFileName, RowMapper<SourceData> sourceMapper) {
        return jdbc.query("SELECT sd.* FROM delivery_batch b JOIN source_data sd ON sd.data_id=b.data_id " +
                "WHERE b.batch_delivery_id=? AND b.new_file_name=? ORDER BY b.source_seq,b.batch_item_id",
                sourceMapper, batchDeliveryId, newFileName);
    }

    public int assign(long batchDeliveryId, String newFileName, long deliveryId) {
        return jdbc.update("UPDATE delivery_batch SET delivery_id=?,status='PROCESSING',started_at=COALESCE(started_at,now()) " +
                "WHERE batch_delivery_id=? AND new_file_name=? AND delivery_id IS NULL AND status='PLANNED'",
                deliveryId, batchDeliveryId, newFileName);
    }

    public List<DeliveryBatchItem> byBatch(long batchDeliveryId) {
        return jdbc.query("SELECT * FROM delivery_batch WHERE batch_delivery_id=? ORDER BY new_file_name,source_seq,batch_item_id",
                itemMapper, batchDeliveryId);
    }

    public List<DeliveryBatchItem> byTask(long deliveryId, String newFileName) {
        return jdbc.query("SELECT * FROM delivery_batch WHERE delivery_id=? AND new_file_name=? ORDER BY source_seq,batch_item_id",
                itemMapper, deliveryId, newFileName);
    }
}
