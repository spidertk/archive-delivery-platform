package com.powerjob.archive.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.pipeline.PipelineRepository;
import com.powerjob.archive.core.repository.DeliveryEventRepository;
import com.powerjob.archive.core.repository.DeliveryTaskRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class ArchiveModelIntegrationTest {
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("archive_delivery").withUsername("archive").withPassword("archive_local")
            .withEnv("PGOPTIONS", "-c archive_delivery.upgrade_authorized=2026-11-07");
    static com.zaxxer.hikari.HikariDataSource dataSource;
    static JdbcTemplate jdbc;
    static TransactionTemplate transactions;
    static ObjectMapper mapper = new ObjectMapper();

    @BeforeAll static void migrate() {
        postgres.start();
        var legacy = new com.zaxxer.hikari.HikariDataSource();
        legacy.setJdbcUrl(postgres.getJdbcUrl());
        legacy.setUsername(postgres.getUsername());
        legacy.setPassword(postgres.getPassword());
        legacy.setConnectionInitSql("SET archive_delivery.upgrade_authorized='2026-11-06'");
        legacy.setSchema("archive_delivery");
        Flyway.configure().dataSource(legacy).schemas("archive_delivery").defaultSchema("archive_delivery")
                .target("13").load().migrate();
        legacy.close();
        dataSource = new com.zaxxer.hikari.HikariDataSource();
        dataSource.setJdbcUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        dataSource.setConnectionInitSql("SET archive_delivery.upgrade_authorized='2026-11-07'");
        dataSource.setSchema("archive_delivery");
        Flyway.configure().dataSource(dataSource).schemas("archive_delivery").defaultSchema("archive_delivery").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll static void stop() { postgres.stop(); }

    @Test void upgradedSchemaHasNoLegacyObjectsAndHasCompleteComments() {
        List<String> legacy = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='archive_delivery' " +
                "AND table_name IN('delivery_run','output_package','source_package','delivery_node_task','operation_audit','production_line'," +
                "'delivery_group','delivery_pipeline_submit','delivery_task_process','source_pipeline_binding','node_definition','rule_set')", String.class);
        assertTrue(legacy.isEmpty());
        List<String> current = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='archive_delivery' " +
                "AND table_name IN('delivery_submit','delivery_task','delivery_node')", String.class);
        assertEquals(3, current.size());
        Integer uncommentedTables = jdbc.queryForObject("SELECT count(*) FROM information_schema.tables t " +
                "LEFT JOIN pg_class c ON c.relname=t.table_name LEFT JOIN pg_namespace n ON n.oid=c.relnamespace AND n.nspname=t.table_schema " +
                "LEFT JOIN pg_description d ON d.objoid=c.oid AND d.objsubid=0 WHERE t.table_schema='archive_delivery' " +
                "AND t.table_name<>'flyway_schema_history' AND d.description IS NULL", Integer.class);
        Integer uncommentedColumns = jdbc.queryForObject("SELECT count(*) FROM information_schema.columns col " +
                "LEFT JOIN pg_class c ON c.relname=col.table_name LEFT JOIN pg_namespace n ON n.oid=c.relnamespace AND n.nspname=col.table_schema " +
                "LEFT JOIN pg_attribute a ON a.attrelid=c.oid AND a.attname=col.column_name " +
                "LEFT JOIN pg_description d ON d.objoid=c.oid AND d.objsubid=a.attnum WHERE col.table_schema='archive_delivery' " +
                "AND col.table_name<>'flyway_schema_history' AND d.description IS NULL", Integer.class);
        assertEquals(0, uncommentedTables);
        assertEquals(0, uncommentedColumns);
    }

    @Test void artifactWhitelistAndEventImmutabilityAreEnforcedByDatabase() throws Exception {
        insertSubmit(100);
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            String artifact = "{\"artifactSeq\":1,\"originalFileName\":\"batch_00001.tar.gz\",\"newFileName\":\"batch_00001.tar.gz\"," +
                    "\"sizeBytes\":1,\"sha256\":\"" + "0".repeat(64) + "\",\"token\":1,\"file_mapping\":{},\"extra\":true}";
            assertThrows(SQLException.class, () -> statement.execute("INSERT INTO archive_delivery.source_data(delivery_id,original_locator,artifacts) " +
                    "VALUES(100,'{\"sourceUri\":\"oss://bucket/a\",\"sourceVersion\":\"1\"}','[" + artifact + "]')"));
            statement.execute("INSERT INTO archive_delivery.delivery_event(delivery_id,event_type,dedup_key) VALUES(100,'TEST','test-100')");
            assertThrows(SQLException.class, () -> statement.execute("UPDATE archive_delivery.delivery_event SET message='changed' WHERE delivery_id=100"));
        }
    }

    @Test void rolloverCreatesNextDayTaskAndPreservesEntityTarget() {
        insertSubmit(200);
        jdbc.update("INSERT INTO delivery_entity(vendor_code,entity_code,entity_name,protocol,account_name,endpoint_locator) " +
                "VALUES('TEST','ENTITY-1','Test','FTP','entity1','{\"host\":\"localhost\",\"rootPath\":\"/\"}')");
        long entityId = jdbc.queryForObject("SELECT max(delivery_entity_id) FROM delivery_entity", Long.class);
        String sha = "a".repeat(64);
        String artifact = "[{\"artifactSeq\":1,\"originalFileName\":\"batch_00001.tar.gz\",\"newFileName\":\"batch_00001.tar.gz\"," +
                "\"sizeBytes\":10,\"sha256\":\"" + sha + "\",\"token\":10,\"file_mapping\":{\"old\":\"new\"}}]";
        jdbc.update("INSERT INTO source_data(delivery_id,original_locator,qualification,size_bytes,token_count,sha256,artifacts) " +
                "VALUES(200,'{\"sourceUri\":\"oss://bucket/a\",\"sourceVersion\":\"1\"}','QUALIFIED',10,10,?,?::jsonb)", sha, artifact);
        long dataId = jdbc.queryForObject("SELECT max(data_id) FROM source_data", Long.class);
        jdbc.update("INSERT INTO delivery_task(delivery_id,data_id,delivery_entity_id,target_seq,artifact_seq,delivery_date,cutoff_at," +
                "original_file_name,new_file_name,size_bytes,token_count,sha256,current_node_id) VALUES(200,?,?,1,1,'2026-11-06',now()+interval '1 day'," +
                "'batch_00001.tar.gz','batch_00001.tar.gz',10,10,?,'targetUpload')", dataId, entityId, sha);
        long oldTask = jdbc.queryForObject("SELECT max(task_id) FROM delivery_task", Long.class);
        DeliveryRolloverService rollovers = new DeliveryRolloverService(jdbc, transactions, new DeliveryTaskRepository(jdbc, transactions),
                new DeliveryEventRepository(jdbc, mapper), new DeliveryProjectionService(jdbc), new PipelineRepository(mapper), mapper);
        assertEquals(1, rollovers.rolloverDelivery(200, LocalDate.parse("2026-11-06"), LocalDate.parse("2026-11-07"), "acceptance", "test"));
        var old = jdbc.queryForMap("SELECT execution_status,superseded_by_task_id FROM delivery_task WHERE task_id=?", oldTask);
        long newTask = ((Number) old.get("superseded_by_task_id")).longValue();
        assertNotEquals(oldTask, newTask);
        assertEquals("EXPIRED", old.get("execution_status"));
        assertEquals(LocalDate.parse("2026-11-07"), jdbc.queryForObject("SELECT delivery_date FROM delivery_task WHERE task_id=?", LocalDate.class, newTask));
        assertEquals(200, jdbc.queryForObject("SELECT delivery_id FROM delivery_task WHERE task_id=?", Long.class, newTask));
        assertEquals(entityId, jdbc.queryForObject("SELECT delivery_entity_id FROM delivery_task WHERE task_id=?", Long.class, newTask));
        assertEquals(1, jdbc.queryForObject("SELECT target_seq FROM delivery_task WHERE task_id=?", Integer.class, newTask));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM delivery_task WHERE delivery_id=200 AND delivery_entity_id=? " +
                "AND execution_status NOT IN('EXPIRED','CANCELLED')", Integer.class, entityId));
    }

    @Test void threeEntitiesTwoEachDeliveredBeforeCutoff() {
        long deliveryId = 1000;
        createTwoTargetDelivery(deliveryId, true);
        new DeliveryProjectionService(jdbc).refresh(deliveryId);
        assertEquals("DELIVERED", status(deliveryId));
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM delivery_task WHERE delivery_id=? AND execution_status='SUCCEEDED'", Integer.class, deliveryId));
        assertNotNull(jdbc.queryForObject("SELECT completed_at FROM delivery_submit WHERE delivery_id=?", OffsetDateTime.class, deliveryId));
    }

    @Test void threeEntitiesTwoEachPartiallyDeliveredThenCompletedOnRequestedNextDate() {
        long deliveryId = 2000;
        LocalDate initialDate = LocalDate.parse("2026-11-07");
        LocalDate nextDate = LocalDate.parse("2026-11-08");
        createTwoTargetDelivery(deliveryId, false);
        DeliveryProjectionService projections = new DeliveryProjectionService(jdbc);
        projections.refresh(deliveryId);
        assertEquals("PROCESSING", status(deliveryId));
        DeliveryRolloverService rollovers = new DeliveryRolloverService(jdbc, transactions, new DeliveryTaskRepository(jdbc, transactions),
                new DeliveryEventRepository(jdbc, mapper), projections, new PipelineRepository(mapper), mapper);
        assertEquals(3, rollovers.rolloverDelivery(deliveryId, initialDate, nextDate, "cutoff continuation", "acceptance"));
        assertEquals("PARTIALLY_DELIVERED", status(deliveryId));
        assertNull(jdbc.queryForObject("SELECT completed_at FROM delivery_submit WHERE delivery_id=?", OffsetDateTime.class, deliveryId));
        List<Long> continued = jdbc.queryForList("SELECT task_id FROM delivery_task WHERE delivery_id=? AND delivery_date=? ORDER BY delivery_entity_id",
                Long.class, deliveryId, nextDate);
        assertEquals(3, continued.size());
        for (int entityIndex = 1; entityIndex <= 3; entityIndex++) {
            long entityId = entityIndex;
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM delivery_task WHERE delivery_id=? AND delivery_entity_id=? " +
                    "AND execution_status NOT IN('EXPIRED','CANCELLED')", Integer.class, deliveryId, entityId));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM delivery_task WHERE delivery_id=? AND delivery_entity_id=? " +
                    "AND execution_status='SUCCEEDED'", Integer.class, deliveryId, entityId));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM delivery_task WHERE delivery_id=? AND delivery_entity_id=? " +
                    "AND delivery_date=? AND execution_status='READY'", Integer.class, deliveryId, entityId, nextDate));
            assertEquals(2, jdbc.queryForObject("SELECT target_seq FROM delivery_task WHERE delivery_id=? AND delivery_entity_id=? " +
                    "AND delivery_date=?", Integer.class, deliveryId, entityId, nextDate));
        }
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM delivery_task WHERE delivery_id=? AND execution_status='EXPIRED'", Integer.class, deliveryId));
        jdbc.update("UPDATE delivery_task SET execution_status='SUCCEEDED',current_node_id='ftpDownloadVerify',completed_at=now(),updated_at=now() " +
                "WHERE delivery_id=? AND delivery_date=?", deliveryId, nextDate);
        projections.refresh(deliveryId);
        assertEquals("DELIVERED", status(deliveryId));
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM delivery_task WHERE delivery_id=? AND execution_status NOT IN('EXPIRED','CANCELLED') " +
                "AND execution_status='SUCCEEDED'", Integer.class, deliveryId));
        assertNotNull(jdbc.queryForObject("SELECT completed_at FROM delivery_submit WHERE delivery_id=?", OffsetDateTime.class, deliveryId));
    }

    private String status(long deliveryId) {
        return jdbc.queryForObject("SELECT status FROM delivery_submit WHERE delivery_id=?", String.class, deliveryId);
    }

    /** 小样本构造每实体 2 个独立 artifact；逆向案例仅保留每实体第 2 个未完成，验证跨日配额和主单状态。 */
    private void createTwoTargetDelivery(long deliveryId, boolean allSucceeded) {
        LocalDate deliveryDate = LocalDate.parse("2026-11-07");
        OffsetDateTime cutoff = OffsetDateTime.now().plusDays(1);
        jdbc.update("INSERT INTO ingest_source(source_id,oss_uri,channel_code,oss_type) VALUES(?,?,'STATE-CASE','ALIBABA_CLOUD')",
                deliveryId, "oss://bucket/state-case/" + deliveryId + "/");
        for (int entityIndex = 1; entityIndex <= 3; entityIndex++) {
            jdbc.update("INSERT INTO delivery_entity(delivery_entity_id,vendor_code,entity_code,entity_name,protocol,account_name,endpoint_locator) " +
                    "VALUES(?,'STATE-CASE',?,'State case','FTP',?,'{\"host\":\"localhost\",\"rootPath\":\"/\"}') ON CONFLICT(delivery_entity_id) DO NOTHING",
                    entityIndex, "ENTITY-" + entityIndex, "entity" + entityIndex);
        }
        String targets = "{\"schemaVersion\":1,\"targets\":[" +
                "{\"targetCount\":2,\"deliveryEntityId\":1},{\"targetCount\":2,\"deliveryEntityId\":2},{\"targetCount\":2,\"deliveryEntityId\":3}]}";
        jdbc.update("INSERT INTO delivery_submit(delivery_id,delivery_batch,source_id,pipeline_version_id,idempotency_key,delivery_date,cutoff_at," +
                "target_strategy,discovery_completed_at) VALUES(?,?,?,1,?,?,?,?::jsonb,now())",
                deliveryId, "STATE-" + deliveryId, deliveryId, "state-" + deliveryId, deliveryDate, cutoff, targets);
        List<Object[]> sources = new java.util.ArrayList<>(6);
        List<Object[]> tasks = new java.util.ArrayList<>(6);
        for (int entityIndex = 1; entityIndex <= 3; entityIndex++) {
            for (int sequence = 1; sequence <= 2; sequence++) {
                long id = deliveryId * 10000 + entityIndex * 1000L + sequence;
                String fileName = String.format("batch_%04d.tar.gz", sequence);
                String sha = String.format("%064x", id);
                String locator = "{\"sourceUri\":\"oss://bucket/state-case/" + deliveryId + "/" + entityIndex + "/" + sequence + "\",\"sourceVersion\":\"1\"}";
                String artifact = "[{\"artifactSeq\":1,\"originalFileName\":\"" + fileName + "\",\"newFileName\":\"" + fileName + "\"," +
                        "\"sizeBytes\":" + sequence + ",\"sha256\":\"" + sha + "\",\"token\":" + sequence + ",\"file_mapping\":{}}]";
                sources.add(new Object[]{id, deliveryId, locator, sequence, sequence, sha, artifact});
                boolean succeeded = allSucceeded || sequence < 2;
                tasks.add(new Object[]{id, deliveryId, id, entityIndex, sequence, deliveryDate, cutoff, fileName, fileName,
                        sequence, sequence, sha, succeeded ? "ftpDownloadVerify" : "targetUpload", succeeded ? "SUCCEEDED" : "READY",
                        succeeded ? OffsetDateTime.now() : null});
            }
        }
        jdbc.batchUpdate("INSERT INTO source_data(data_id,delivery_id,original_locator,qualification,size_bytes,token_count,sha256,artifacts) " +
                "VALUES(?,?,?::jsonb,'QUALIFIED',?,?,?,?::jsonb)", sources);
        jdbc.batchUpdate("INSERT INTO delivery_task(task_id,delivery_id,data_id,delivery_entity_id,target_seq,artifact_seq,delivery_date,cutoff_at," +
                "original_file_name,new_file_name,size_bytes,token_count,sha256,current_node_id,execution_status,completed_at) " +
                "VALUES(?,?,?,?,?,1,?,?,?,?,?,?,?,?,?,?)", tasks);
    }

    private void insertSubmit(long id) {
        jdbc.update("INSERT INTO ingest_source(source_id,oss_uri,channel_code,oss_type) VALUES(?,?,'TEST','ALIBABA_CLOUD')",
                id, "oss://bucket/prefix/" + id + "/");
        jdbc.update("INSERT INTO delivery_submit(delivery_id,delivery_batch,source_id,pipeline_version_id,idempotency_key," +
                "delivery_date,cutoff_at,target_strategy) VALUES(?,? ,?,1,?,'2026-11-06',?,'{\"schemaVersion\":1,\"targets\":[]}')",
                id, "BATCH-" + id, id, "submit-" + id, OffsetDateTime.now().plusDays(1));
    }
}
