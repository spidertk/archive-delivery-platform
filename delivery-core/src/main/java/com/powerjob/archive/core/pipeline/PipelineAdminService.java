package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Service
public class PipelineAdminService {
    private static final String MASK = "********";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final PipelineRepository pipelines;

    public PipelineAdminService(JdbcTemplate jdbc, TransactionTemplate transactions,
                                ObjectMapper mapper, PipelineRepository pipelines) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
        this.pipelines = pipelines;
    }

    public List<VersionView> list(String pipelineCode) {
        return jdbc.query("SELECT pv.*,p.pipeline_code FROM pipeline_version pv " +
                        "JOIN pipeline p ON p.id=pv.pipeline_id WHERE p.pipeline_code=? ORDER BY pv.id",
                (rs, rowNum) -> view(rs.getLong("id"), rs.getString("pipeline_code"), rs.getInt("version_no"),
                        rs.getString("status"), rs.getString("definition_sha256"), read(rs.getString("definition"))),
                pipelineCode);
    }

    public VersionView get(long id) {
        return view(row(id, false));
    }

    public JsonNode rawDefinition(long id) {
        return row(id, false).definition();
    }

    public VersionView createDraft(String pipelineCode, long baseVersionId) {
        return transactions.execute(status -> {
            VersionRow base = row(baseVersionId, true);
            if (!base.pipelineCode().equals(pipelineCode)) {
                throw new IllegalArgumentException("Pipeline version does not belong to " + pipelineCode);
            }
            Integer next = jdbc.queryForObject("SELECT COALESCE(max(version_no),0)+1 FROM pipeline_version " +
                    "WHERE pipeline_id=(SELECT id FROM pipeline WHERE pipeline_code=?)", Integer.class, pipelineCode);
            ObjectNode definition = base.definition().deepCopy();
            definition.put("version", next);
            long id = jdbc.queryForObject("INSERT INTO pipeline_version " +
                            "(pipeline_id,version_no,status,definition,definition_sha256) " +
                            "SELECT id,?,'DRAFT',?::jsonb,? FROM pipeline WHERE pipeline_code=? RETURNING id",
                    Long.class, next, definition.toString(), sha256(definition), pipelineCode);
            return view(row(id, false));
        });
    }

    public VersionView updateDraft(long id, JsonNode definition) {
        return transactions.execute(status -> {
            VersionRow current = row(id, true);
            requireDraft(current);
            ObjectNode updated = definition.deepCopy();
            restoreSecrets(updated, current.definition());
            jdbc.update("UPDATE pipeline_version SET definition=?::jsonb,definition_sha256=? WHERE id=?",
                    updated.toString(), sha256(updated), id);
            return view(row(id, false));
        });
    }

    public VersionView publish(long id) {
        return transactions.execute(status -> {
            VersionRow current = row(id, true);
            requireDraft(current);
            pipelines.read(current.definition()).validateProduction();
            jdbc.update("UPDATE pipeline_version SET status='PUBLISHED',definition_sha256=?,published_at=now() WHERE id=?",
                    sha256(current.definition()), id);
            List<Map<String, Object>> bindings = jdbc.queryForList("SELECT b.id,b.source_id FROM source_pipeline_binding b " +
                    "JOIN pipeline_version pv ON pv.id=b.pipeline_version_id " +
                    "WHERE b.effective_to IS NULL AND pv.pipeline_id=(SELECT pipeline_id FROM pipeline_version WHERE id=?)", id);
            for (Map<String, Object> binding : bindings) {
                long bindingId = ((Number) binding.get("id")).longValue();
                long sourceId = ((Number) binding.get("source_id")).longValue();
                jdbc.update("UPDATE source_pipeline_binding SET effective_to=now() WHERE id=?", bindingId);
                jdbc.update("INSERT INTO source_pipeline_binding(source_id,pipeline_version_id,effective_from) " +
                        "VALUES (?,?,now())", sourceId, id);
            }
            return view(row(id, false));
        });
    }

    private VersionRow row(long id, boolean lock) {
        List<VersionRow> rows = jdbc.query("SELECT pv.*,p.pipeline_code FROM pipeline_version pv " +
                        "JOIN pipeline p ON p.id=pv.pipeline_id WHERE pv.id=?" + (lock ? " FOR UPDATE OF pv" : ""),
                (rs, rowNum) -> new VersionRow(rs.getLong("id"), rs.getString("pipeline_code"), rs.getInt("version_no"),
                        rs.getString("status"), rs.getString("definition_sha256"), read(rs.getString("definition"))), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown Pipeline version " + id);
        return rows.get(0);
    }

    private static void requireDraft(VersionRow row) {
        if (!"DRAFT".equals(row.status())) {
            throw new PipelineVersionConflictException("Published Pipeline versions are immutable");
        }
    }

    private VersionView view(VersionRow row) {
        return view(row.id(), row.pipelineCode(), row.version(), row.status(), row.sha256(), row.definition());
    }

    private VersionView view(long id, String code, int version, String status, String sha, JsonNode definition) {
        return new VersionView(id, code, version, status, sha, mask(definition));
    }

    private static JsonNode mask(JsonNode definition) {
        ObjectNode masked = definition.deepCopy();
        maskFields(masked);
        return masked;
    }

    private static void maskFields(JsonNode node) {
        if (node instanceof ObjectNode object) {
            if (object.has("accessKeyId")) object.put("accessKeyId", MASK);
            if (object.has("accessKeySecret")) object.put("accessKeySecret", MASK);
            object.forEach(child -> {
                if (child.isObject() || child.isArray()) maskFields(child);
            });
        } else if (node.isArray()) {
            node.forEach(child -> {
                if (child.isObject() || child.isArray()) maskFields(child);
            });
        }
    }

    private static void restoreSecrets(ObjectNode updated, JsonNode previous) {
        for (String key : List.of("accessKeyId", "accessKeySecret")) {
            if (previous.has(key) && (!updated.has(key) || MASK.equals(updated.path(key).asText()))) {
                updated.set(key, previous.get(key));
            }
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = updated.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            JsonNode oldChild = previous.get(field.getKey());
            if (field.getValue() instanceof ObjectNode object && oldChild != null && oldChild.isObject()) {
                restoreSecrets(object, oldChild);
            } else if (field.getValue().isArray() && oldChild != null && oldChild.isArray()) {
                for (int i = 0; i < field.getValue().size() && i < oldChild.size(); i++) {
                    JsonNode child = field.getValue().get(i);
                    if (child instanceof ObjectNode object && oldChild.get(i).isObject()) {
                        restoreSecrets(object, oldChild.get(i));
                    }
                }
            }
        }
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid Pipeline definition JSON", e);
        }
    }

    private String sha256(JsonNode definition) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(definition)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash Pipeline definition", e);
        }
    }

    public record VersionView(long id, String pipelineCode, int version, String status,
                              String definitionSha256, JsonNode definition) {}
    private record VersionRow(long id, String pipelineCode, int version, String status,
                              String sha256, JsonNode definition) {}
}
