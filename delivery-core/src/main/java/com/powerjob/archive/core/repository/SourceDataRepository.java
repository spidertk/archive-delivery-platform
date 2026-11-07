package com.powerjob.archive.core.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.domain.DiscoveredObject;
import com.powerjob.archive.core.domain.SourceData;
import com.powerjob.archive.core.pipeline.SourceDataPatch;
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
                rs.getLong("data_id"), rs.getLong("delivery_id"), rs.getString("original_request_id"),
                rs.getString("new_request_id"), rs.getString("request_namespace"),
                JdbcJson.read(mapper, rs.getString("original_locator")), JdbcJson.read(mapper, rs.getString("output_locator")),
                rs.getString("qualification"), rs.getString("filter_rule_code"), rs.getString("filter_reason"),
                rs.getLong("affected_count"), rs.getString("rule_version"), rs.getLong("size_bytes"),
                rs.getLong("token_count"), rs.getString("sha256"), JdbcJson.read(mapper, rs.getString("artifacts")));
    }

    /** 首次发现保存真实下载 SHA；重复唯一身份只留 FILTERED 事实，不生成第二个有效任务。 */
    public SourceInsert insertDiscovered(long deliveryId, DiscoveredObject object) {
        ObjectNode locator = mapper.createObjectNode().put("schemaVersion", 1)
                .put("sourceUri", object.sourceUri()).put("sourceVersion", object.objectVersion());
        locator.putArray("responsePaths");
        ArrayNode artifacts = mapper.createArrayNode();
        ObjectNode artifact = artifacts.addObject();
        artifact.put("artifactSeq", 1);
        artifact.put("originalFileName", fileName(object.objectKey()));
        artifact.put("newFileName", fileName(object.objectKey()));
        artifact.put("sizeBytes", object.size());
        artifact.put("sha256", object.sha256());
        artifact.put("token", object.size());
        artifact.putObject("file_mapping");
        List<Long> inserted = jdbc.query("INSERT INTO source_data(delivery_id,original_locator,size_bytes,token_count,sha256,artifacts) " +
                        "VALUES(?,?::jsonb,?,?,?,?::jsonb) ON CONFLICT DO NOTHING RETURNING data_id", (rs, n) -> rs.getLong(1),
                deliveryId, locator.toString(), object.size(), object.size(), object.sha256(), artifacts.toString());
        if (!inserted.isEmpty()) return new SourceInsert(inserted.get(0), true);
        Long existing = jdbc.queryForObject("SELECT data_id FROM source_data WHERE delivery_id=? " +
                        "AND original_locator->>'sourceUri'=? AND original_locator->>'sourceVersion'=? AND qualification<>'FILTERED'",
                Long.class, deliveryId, object.sourceUri(), object.objectVersion());
        long duplicate = jdbc.queryForObject("INSERT INTO source_data(delivery_id,original_locator,qualification,filter_rule_code," +
                        "filter_reason,affected_count,size_bytes,token_count,sha256,artifacts) " +
                        "VALUES(?,?::jsonb,'FILTERED','DUPLICATE_ASSET','Duplicate sourceUri and sourceVersion',1,?,?,?,?::jsonb) RETURNING data_id",
                Long.class, deliveryId, locator.toString(), object.size(), object.size(), object.sha256(), artifacts.toString());
        return new SourceInsert(existing, false, duplicate);
    }

    public Optional<SourceData> findById(long dataId) {
        return jdbc.query("SELECT * FROM source_data WHERE data_id=?", rowMapper, dataId).stream().findFirst();
    }

    public void applyPatch(long dataId, SourceDataPatch patch) {
        if (patch == null) return;
        jdbc.update("UPDATE source_data SET original_request_id=COALESCE(?,original_request_id)," +
                "new_request_id=COALESCE(?,new_request_id),request_namespace=COALESCE(?,request_namespace)," +
                "output_locator=COALESCE(?::jsonb,output_locator),qualification=COALESCE(?,qualification)," +
                "rule_version=COALESCE(?,rule_version),size_bytes=COALESCE(?,size_bytes),token_count=COALESCE(?,token_count)," +
                "sha256=COALESCE(?,sha256),artifacts=COALESCE(?::jsonb,artifacts) WHERE data_id=?",
                patch.originalRequestId(), patch.newRequestId(), patch.requestNamespace(), json(patch.outputLocator()),
                patch.qualification(), patch.ruleVersion(), patch.sizeBytes(), patch.tokenCount(), patch.sha256(),
                json(patch.artifacts()), dataId);
    }

    private static String fileName(String key) {
        String name = java.nio.file.Path.of(key).getFileName().toString();
        if (name.contains("..") || name.isBlank()) throw new IllegalArgumentException("Unsafe source file name");
        return name;
    }

    private static String json(com.fasterxml.jackson.databind.JsonNode value) {
        return value == null ? null : value.toString();
    }

    public record SourceInsert(long dataId, boolean deliverable, Long duplicateDataId) {
        public SourceInsert(long dataId, boolean deliverable) {
            this(dataId, deliverable, null);
        }
    }
}
