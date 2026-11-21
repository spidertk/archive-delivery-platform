package com.powerjob.archive.core.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.domain.DiscoveredObject;
import com.powerjob.archive.core.domain.SourceData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class SourceDataRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RowMapper<SourceData> rowMapper;

    public SourceDataRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.rowMapper = (rs, n) -> new SourceData(
                rs.getLong("data_id"), rs.getLong("delivery_id"), rs.getString("original_file_name"),
                rs.getString("request_namespace"),
                JdbcJson.read(mapper, rs.getString("original_locator")), JdbcJson.read(mapper, rs.getString("output_locator")),
                rs.getString("qualification"), rs.getString("filter_rule_code"), rs.getString("filter_reason"),
                rs.getLong("affected_count"), rs.getLong("size_bytes"), rs.getLong("token_count"),
                rs.getString("sha256"), rs.getString("unique_key"), JdbcJson.read(mapper, rs.getString("artifact")));
    }

    public SourceInsert insertDiscovered(long batchDeliveryId, DiscoveredObject object) {
        String originalFileName = fileName(object.objectKey());
        ObjectNode locator = mapper.createObjectNode().put("schemaVersion", 1)
                .put("sourceUri", object.sourceUri()).put("sourceVersion", object.objectVersion());
        locator.putArray("responsePaths");
        if (object.cachePath() != null && !object.cachePath().isBlank()) locator.put("cachePath", object.cachePath());
        ObjectNode artifact = mapper.createObjectNode();
        artifact.put("artifactSeq", 1);
        artifact.put("originalFileName", originalFileName);
        artifact.put("newFileName", originalFileName);
        artifact.put("sizeBytes", object.size());
        artifact.put("sha256", object.sha256());
        artifact.put("token", object.size());
        artifact.putObject("file_mapping");
        List<Long> inserted = jdbc.query("INSERT INTO source_data(delivery_id,original_file_name,original_locator," +
                        "size_bytes,token_count,sha256,unique_key,artifact) VALUES(?,?,?::jsonb,?,?,?,?,?::jsonb) " +
                        "ON CONFLICT(unique_key) DO NOTHING RETURNING data_id", (rs, n) -> rs.getLong(1),
                batchDeliveryId, originalFileName, locator.toString(), object.size(), object.size(), object.sha256(),
                object.sha256(), artifact.toString());
        long dataId = !inserted.isEmpty() ? inserted.get(0)
                : jdbc.queryForObject("SELECT data_id FROM source_data WHERE unique_key=?", Long.class, object.sha256());
        return new SourceInsert(dataId, true);
    }

    public Optional<SourceData> findById(long dataId) {
        return jdbc.query("SELECT * FROM source_data WHERE data_id=?", rowMapper, dataId).stream().findFirst();
    }

    public List<SourceData> byTaskId(long taskId) {
        return jdbc.query("SELECT sd.* FROM delivery_task t JOIN delivery_batch b ON b.delivery_id=t.delivery_id " +
                "AND b.new_file_name=t.new_file_name JOIN source_data sd ON sd.data_id=b.data_id " +
                "WHERE t.task_id=? ORDER BY b.source_seq,b.batch_item_id", rowMapper, taskId);
    }

    public List<SourceData> byBatchPackage(long batchDeliveryId, String newFileName) {
        return jdbc.query("SELECT sd.* FROM delivery_batch b JOIN source_data sd ON sd.data_id=b.data_id " +
                "WHERE b.batch_delivery_id=? AND b.new_file_name=? ORDER BY b.source_seq,b.batch_item_id",
                rowMapper, batchDeliveryId, newFileName);
    }

    public void markSourcesQualified(long taskId, String requestNamespace) {
        jdbc.update("UPDATE source_data SET qualification='QUALIFIED',request_namespace=? WHERE data_id IN " +
                "(SELECT b.data_id FROM delivery_task t JOIN delivery_batch b ON b.delivery_id=t.delivery_id " +
                "AND b.new_file_name=t.new_file_name WHERE t.task_id=?)", requestNamespace, taskId);
    }

    private static String fileName(String key) {
        String name = java.nio.file.Path.of(key).getFileName().toString();
        if (name.contains("..") || name.isBlank()) throw new IllegalArgumentException("Unsafe source file name");
        return name;
    }

    public record SourceInsert(long dataId, boolean deliverable, Long duplicateDataId) {
        public SourceInsert(long dataId, boolean deliverable) {
            this(dataId, deliverable, null);
        }
    }
}
