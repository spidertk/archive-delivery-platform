package com.powerjob.archive.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.domain.DeliveryEntity;
import com.powerjob.archive.core.domain.WorkerGroups;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    /** 只修正同一 FTP 的非 Secret 地址；短事务阻止新提交进入，任何未完成任务都禁止切换。 */
    @Transactional
    public DeliveryEntity updateEndpoint(String vendorCode, String entityCode, JsonNode expected, JsonNode replacement) {
        validateEndpoint(replacement);
        // 与提交写入互斥，确保检查期间不能出现读取旧定位的新任务；不执行外部 IO。
        jdbc.execute("SET LOCAL lock_timeout='5s'");
        jdbc.execute("LOCK TABLE delivery_submit IN SHARE MODE");
        DeliveryEntity entity = jdbc.query("SELECT * FROM delivery_entity WHERE vendor_code=? AND entity_code=? FOR UPDATE",
                this::map, vendorCode, entityCode).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown delivery entity"));
        if (!entity.endpointLocator().equals(expected)) throw new IllegalArgumentException("Endpoint changed; reload before retry");
        // 已规划来源不改变执行组，定位修复也不能影响正在执行或等待恢复的任务。
        Integer unfinished = jdbc.queryForObject("SELECT count(*) FROM delivery_task WHERE delivery_entity_id=? " +
                "AND execution_status NOT IN('SUCCEEDED','EXPIRED','CANCELLED')", Integer.class, entity.deliveryEntityId());
        if (unfinished != 0) throw new IllegalArgumentException("Endpoint cannot change while unfinished tasks exist");
        jdbc.update("UPDATE delivery_entity SET endpoint_locator=?::jsonb WHERE delivery_entity_id=?",
                replacement.toString(), entity.deliveryEntityId());
        return find(entity.deliveryEntityId());
    }

    private static void validateEndpoint(JsonNode locator) {
        // 同创建入口保持严格定位白名单；凭据和任意协议参数不能进入实体。
        if (locator == null || !locator.isObject()) throw new IllegalArgumentException("endpointLocator must be an object");
        locator.fieldNames().forEachRemaining(name -> {
            if (!LOCATOR_FIELDS.contains(name)) throw new IllegalArgumentException("Unknown endpointLocator field");
        });
        if (locator.path("schemaVersion").asInt() != 1 || !locator.path("host").asText().matches("[A-Za-z0-9.-]+")
                || !locator.path("port").canConvertToInt() || locator.path("port").asInt() < 1
                || locator.path("port").asInt() > 65535 || !locator.path("rootPath").asText().startsWith("/")
                || locator.path("rootPath").asText().matches(".*(?:\\.\\.|[?#\\\\\\r\\n]).*")) {
            throw new IllegalArgumentException("Unsafe delivery endpoint locator");
        }
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
