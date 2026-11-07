package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PipelineAdminService {
    private static final String MASK = "********";
    private static final Set<String> SECRET_FIELDS = Set.of("accesskeyid", "accesskeysecret", "password", "privatekey", "ftpcredential");
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
                        "JOIN pipeline p ON p.pipeline_id=pv.pipeline_id WHERE p.pipeline_code=? ORDER BY pv.pipeline_version_id",
                (rs, rowNum) -> view(new VersionRow(rs.getLong("pipeline_version_id"), rs.getString("pipeline_code"),
                        rs.getInt("version_no"), rs.getString("status"), rs.getString("definition_sha256"),
                        read(rs.getString("definition")))), pipelineCode);
    }

    public VersionView get(long id) {
        return view(row(id, false));
    }

    /** 仅供 Worker 内部读取当前版本；不得从 Controller 返回此原始定义。 */
    public JsonNode rawDefinition(long id) {
        return row(id, false).definition();
    }

    public VersionView createDraft(String pipelineCode, long baseVersionId) {
        return transactions.execute(status -> {
            VersionRow base = row(baseVersionId, true);
            if (!base.pipelineCode().equals(pipelineCode)) throw new IllegalArgumentException("Pipeline version code mismatch");
            Integer next = jdbc.queryForObject("SELECT COALESCE(max(version_no),0)+1 FROM pipeline_version " +
                    "WHERE pipeline_id=(SELECT pipeline_id FROM pipeline WHERE pipeline_code=?)", Integer.class, pipelineCode);
            ObjectNode definition = base.definition().deepCopy();
            definition.put("version", next);
            long id = jdbc.queryForObject("INSERT INTO pipeline_version " +
                            "(pipeline_id,version_no,status,definition,definition_sha256) " +
                            "SELECT pipeline_id,?,'DRAFT',?::jsonb,? FROM pipeline WHERE pipeline_code=? RETURNING pipeline_version_id",
                    Long.class, next, definition.toString(), sha256(definition), pipelineCode);
            return view(row(id, false));
        });
    }

    public VersionView updateDraft(long id, JsonNode definition) {
        return transactions.execute(status -> {
            VersionRow current = row(id, true);
            requireDraft(current);
            ObjectNode updated = definition.deepCopy();
            updated.put("pipelineCode", current.pipelineCode());
            updated.put("version", current.version());
            restoreSecrets(updated, current.definition());
            pipelines.read(updated).validateProduction();
            jdbc.update("UPDATE pipeline_version SET definition=?::jsonb,definition_sha256=? WHERE pipeline_version_id=?",
                    updated.toString(), sha256(updated), id);
            return view(row(id, false));
        });
    }

    public VersionView publish(long id) {
        return transactions.execute(status -> {
            VersionRow current = row(id, true);
            requireDraft(current);
            pipelines.read(current.definition()).validateProduction();
            jdbc.update("UPDATE pipeline_version SET status='PUBLISHED',definition_sha256=?,published_at=now() WHERE pipeline_version_id=?",
                    sha256(current.definition()), id);
            return view(row(id, false));
        });
    }

    private VersionRow row(long id, boolean lock) {
        List<VersionRow> rows = jdbc.query("SELECT pv.*,p.pipeline_code FROM pipeline_version pv " +
                        "JOIN pipeline p ON p.pipeline_id=pv.pipeline_id WHERE pv.pipeline_version_id=?" + (lock ? " FOR UPDATE OF pv" : ""),
                (rs, rowNum) -> new VersionRow(rs.getLong("pipeline_version_id"), rs.getString("pipeline_code"),
                        rs.getInt("version_no"), rs.getString("status"), rs.getString("definition_sha256"),
                        read(rs.getString("definition"))), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown Pipeline version " + id);
        return rows.get(0);
    }

    private static void requireDraft(VersionRow row) {
        if (!"DRAFT".equals(row.status())) throw new PipelineVersionConflictException("Published Pipeline versions are immutable");
    }

    private VersionView view(VersionRow row) {
        return new VersionView(row.id(), row.pipelineCode(), row.version(), row.status(), row.sha256(), mask(row.definition()));
    }

    /** Secret 容器整体只写不读；ftpCredential 中每个账户密码都替换为掩码。 */
    private static JsonNode mask(JsonNode definition) {
        ObjectNode masked = definition.deepCopy();
        maskFields(masked);
        return masked;
    }

    private static void maskFields(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (SECRET_FIELDS.contains(field.getKey().toLowerCase(java.util.Locale.ROOT))) {
                    if (field.getValue() instanceof ObjectNode secrets) {
                        List<String> names = new java.util.ArrayList<>();
                        secrets.fieldNames().forEachRemaining(names::add);
                        names.forEach(name -> secrets.put(name, MASK));
                    } else {
                        object.put(field.getKey(), MASK);
                    }
                } else {
                    maskFields(field.getValue());
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(PipelineAdminService::maskFields);
        }
    }

    private static void restoreSecrets(ObjectNode updated, JsonNode previous) {
        Iterator<Map.Entry<String, JsonNode>> fields = updated.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode old = previous.get(field.getKey());
            if (SECRET_FIELDS.contains(field.getKey().toLowerCase(java.util.Locale.ROOT)) && old != null) {
                if (field.getValue() instanceof ObjectNode currentSecrets && old instanceof ObjectNode oldSecrets) {
                    Iterator<Map.Entry<String, JsonNode>> secrets = currentSecrets.fields();
                    while (secrets.hasNext()) {
                        Map.Entry<String, JsonNode> secret = secrets.next();
                        if (MASK.equals(secret.getValue().asText()) && oldSecrets.has(secret.getKey())) {
                            currentSecrets.set(secret.getKey(), oldSecrets.get(secret.getKey()));
                        }
                    }
                } else if (MASK.equals(field.getValue().asText())) {
                    field.setValue(old);
                }
            } else if (field.getValue() instanceof ObjectNode object && old != null && old.isObject()) {
                restoreSecrets(object, old);
            } else if (field.getValue() instanceof ArrayNode array && old instanceof ArrayNode oldArray) {
                for (int i = 0; i < array.size() && i < oldArray.size(); i++) {
                    if (array.get(i) instanceof ObjectNode object && oldArray.get(i).isObject()) restoreSecrets(object, oldArray.get(i));
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
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(definition)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash Pipeline definition", e);
        }
    }

    public record VersionView(long id, String pipelineCode, int version, String status,
                              String definitionSha256, JsonNode definition) {}
    private record VersionRow(long id, String pipelineCode, int version, String status,
                              String sha256, JsonNode definition) {}
}
