package com.powerjob.archive.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.domain.DeliveryEntity;
import com.powerjob.archive.core.domain.WorkerGroups;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Set;

@Service
public class DeliveryEntityService {
    private static final Set<String> LOCATOR_FIELDS = Set.of("schemaVersion", "host", "port", "rootPath");
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DeliveryEntityService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 实体保存非 Secret 定位和固定执行组；新任务据此选择持有本地工作文件的服务器组。 */
    public DeliveryEntity create(String vendorCode, String entityCode, String entityName, String protocol,
                                 String accountName, String workerGroup, JsonNode locator) {
        if (blank(vendorCode) || blank(entityCode) || blank(entityName) || blank(protocol) || blank(accountName)) {
            throw new IllegalArgumentException("Delivery entity fields are required");
        }
        WorkerGroups.requireValid(workerGroup);
        if (locator == null || !locator.isObject()) throw new IllegalArgumentException("endpointLocator must be an object");
        locator.fieldNames().forEachRemaining(name -> {
            if (!LOCATOR_FIELDS.contains(name)) throw new IllegalArgumentException("Unknown or secret endpointLocator field " + name);
        });
        String host = locator.required("host").asText();
        String rootPath = locator.path("rootPath").asText("/");
        if (!host.matches("[A-Za-z0-9.-]+") || rootPath.contains("..") || rootPath.contains("?") || rootPath.contains("#")) {
            throw new IllegalArgumentException("Unsafe delivery endpoint locator");
        }
        long id = jdbc.queryForObject("INSERT INTO delivery_entity(vendor_code,entity_code,entity_name,protocol,account_name,worker_group,endpoint_locator) " +
                "VALUES(?,?,?,?,?,?,?::jsonb) RETURNING delivery_entity_id", Long.class, vendorCode, entityCode, entityName,
                protocol.toUpperCase(java.util.Locale.ROOT), accountName, workerGroup, locator.toString());
        return find(id);
    }

    public DeliveryEntity find(long id) {
        return jdbc.query("SELECT * FROM delivery_entity WHERE delivery_entity_id=?", this::map, id).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown delivery entity"));
    }

    public List<DeliveryEntity> list() {
        return jdbc.query("SELECT * FROM delivery_entity ORDER BY delivery_entity_id", this::map);
    }

    private DeliveryEntity map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        try {
            return new DeliveryEntity(rs.getLong("delivery_entity_id"), rs.getString("vendor_code"), rs.getString("entity_code"),
                    rs.getString("entity_name"), rs.getString("protocol"), rs.getString("account_name"), rs.getString("worker_group"),
                    mapper.readTree(rs.getString("endpoint_locator")), rs.getBoolean("enabled"));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid endpoint locator", e);
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
