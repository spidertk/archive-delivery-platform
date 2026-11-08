package com.powerjob.archive.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.domain.SourceData;
import com.powerjob.archive.core.pipeline.PipelineRepository;
import com.powerjob.archive.core.repository.DeliveryEventRepository;
import com.powerjob.archive.core.repository.DeliveryNodeRepository;
import com.powerjob.archive.core.repository.DeliveryRepository;
import com.powerjob.archive.core.repository.DeliveryTaskRepository;
import com.powerjob.archive.core.repository.SourceDataRepository;
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
import java.util.ArrayList;
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
    static ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeAll static void migrate() {
        postgres.start();
        var legacy = new com.zaxxer.hikari.HikariDataSource();
        legacy.setJdbcUrl(jdbcUrl());
        legacy.setUsername(postgres.getUsername());
        legacy.setPassword(postgres.getPassword());
        legacy.setConnectionInitSql("SET archive_delivery.upgrade_authorized='2026-11-06'");
        legacy.setSchema("archive_delivery");
        Flyway.configure().dataSource(legacy).schemas("archive_delivery").defaultSchema("archive_delivery")
                .target("13").load().migrate();
        legacy.close();
        dataSource = new com.zaxxer.hikari.HikariDataSource();
        dataSource.setJdbcUrl(jdbcUrl());
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
                "AND table_name IN('delivery_group','delivery_task_source','delivery_task_process','source_pipeline_binding','node_definition','rule_set')", String.class);
        assertTrue(legacy.isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='archive_delivery' " +
                "AND table_name='delivery_mapping'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='archive_delivery' " +
                "AND table_name='delivery_task' AND column_name IN('data_id','target_seq','artifact_seq','original_file_name','sha256'," +
                "'superseded_by_task_id','invalidated_at','invalidation_reason','artifact')", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='archive_delivery' " +
                "AND table_name='delivery_task' AND column_name='entity_code'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='archive_delivery' " +
                "AND table_name='source_data' AND column_name IN('original_request_id','new_request_id','artifacts')", Integer.class));
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='archive_delivery' " +
                "AND table_name='source_data' AND column_name IN('original_file_name','artifact','created_at','updated_at')", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='archive_delivery' " +
                "AND table_name='delivery_mapping' AND column_name IN('created_at','updated_at')", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='archive_delivery' " +
                "AND table_name='delivery_submit' AND column_name='idempotency_key'", Integer.class));
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
        try (Connection connection = DriverManager.getConnection(jdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            String artifact = "{\"artifactSeq\":1,\"originalFileName\":\"batch_00001.tar.gz\",\"newFileName\":\"batch_00001.tar.gz\"," +
                    "\"sizeBytes\":1,\"sha256\":\"" + "0".repeat(64) + "\",\"token\":1,\"file_mapping\":{},\"extra\":true}";
            assertThrows(SQLException.class, () -> statement.execute("INSERT INTO archive_delivery.source_data(delivery_id,original_file_name,original_locator,artifact) " +
                    "VALUES(100,'batch_00001.tar.gz','{\"sourceUri\":\"oss://bucket/a\",\"sourceVersion\":\"1\"}','" + artifact + "')"));
            statement.execute("INSERT INTO archive_delivery.delivery_event(delivery_id,event_type,dedup_key) VALUES(100,'TEST','test-100')");
            assertThrows(SQLException.class, () -> statement.execute("UPDATE archive_delivery.delivery_event SET message='changed' WHERE delivery_id=100"));
        }
    }

    @Test void businessCodeSubmissionPausesAndIdenticalBatchResumes() {
        jdbc.update("UPDATE pipeline_version SET status='PUBLISHED',published_at=now() WHERE pipeline_version_id=1");
        jdbc.update("INSERT INTO delivery_entity(delivery_entity_id,vendor_code,entity_code,entity_name,protocol,account_name,worker_group,endpoint_locator) " +
                "VALUES(150,'VENDOR-150','ENTITY-150','Business submit','FTP','entity150','group1','{\"host\":\"localhost\",\"rootPath\":\"/\"}')");
        DeliveryRepository deliveries = new DeliveryRepository(jdbc, mapper);
        DeliveryTaskRepository tasks = new DeliveryTaskRepository(jdbc, transactions);
        DeliveryEventRepository events = new DeliveryEventRepository(jdbc, mapper);
        DeliveryProjectionService projections = new DeliveryProjectionService(jdbc);
        DeliverySubmissionService submission = new DeliverySubmissionService(jdbc, transactions, mapper, deliveries, tasks, events,
                projections, new PipelineRepository(mapper), new IngestSourceService(jdbc));
        OffsetDateTime cutoffAt = OffsetDateTime.now().plusDays(2);
        var command = new DeliverySubmissionService.SubmissionCommand("oss://bucket/business-submit/", "archive-delivery",
                LocalDate.now().plusDays(2), "BUSINESS-PAUSE-150", cutoffAt,
                List.of(new DeliverySubmissionService.TargetCommand(1, "VENDOR-150", "ENTITY-150")));
        long deliveryId = submission.submit(command).deliveryId();
        assertEquals(deliveryId, submission.submit(command).deliveryId());
        assertThrows(IdempotencyConflictException.class, () -> submission.submit(new DeliverySubmissionService.SubmissionCommand(
                command.ossUri(), command.pipelineCode(), command.deliveryDate(), command.deliveryBatch(), command.cutoffAt(),
                List.of(new DeliverySubmissionService.TargetCommand(2, "VENDOR-150", "ENTITY-150")))));
        SourceData source = new SourceDataRepository(jdbc, mapper).findById(insertSource(deliveryId, "business", 10)).orElseThrow();
        long taskId = tasks.createPlanned(deliveries.find(deliveryId).orElseThrow(), List.of(source), deliveries.entity(150), 1, "sourceDownload");
        jdbc.update("UPDATE delivery_submit SET discovery_completed_at=now() WHERE delivery_id=?", deliveryId);
        TaskOperationService operations = new TaskOperationService(jdbc, transactions, tasks, new SourceDataRepository(jdbc, mapper),
                new DeliveryNodeRepository(jdbc, mapper), events, projections, new PipelineRepository(mapper), mapper);
        assertEquals(1, operations.pause(deliveryId, "acceptance pause", "test"));
        assertEquals("PAUSED", status(deliveryId));
        assertEquals("PAUSED", jdbc.queryForObject("SELECT execution_status FROM delivery_task WHERE task_id=?", String.class, taskId));
        assertEquals(deliveryId, submission.submit(command).deliveryId());
        assertEquals("READY", jdbc.queryForObject("SELECT execution_status FROM delivery_task WHERE task_id=?", String.class, taskId));
        assertEquals("PROCESSING", status(deliveryId));
    }

    @Test void rolloverCopiesThreeMappingsAndRecordsEventLineage() {
        long deliveryId = 250;
        insertSubmit(deliveryId);
        jdbc.update("INSERT INTO delivery_entity(delivery_entity_id,vendor_code,entity_code,entity_name,protocol,account_name,worker_group,endpoint_locator) " +
                "VALUES(250,'VENDOR-250','MERGED','Merged target','FTP','june','group1','{\"host\":\"localhost\",\"rootPath\":\"/\"}')");
        SourceDataRepository sourceRepository = new SourceDataRepository(jdbc, mapper);
        List<SourceData> planned = new ArrayList<>();
        for (int index = 1; index <= 3; index++) planned.add(sourceRepository.findById(insertSource(deliveryId, "source-" + index, 10)).orElseThrow());
        DeliveryRepository deliveries = new DeliveryRepository(jdbc, mapper);
        DeliveryTaskRepository tasks = new DeliveryTaskRepository(jdbc, transactions);
        long taskId = tasks.createPlanned(deliveries.find(deliveryId).orElseThrow(), planned, deliveries.entity(250), 1, "sourceDownload");
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM delivery_mapping WHERE task_id=?", Integer.class, taskId));
        assertEquals(3, jdbc.queryForObject("SELECT jsonb_array_length(artifacts) FROM delivery_task WHERE task_id=?", Integer.class, taskId));
        DeliveryRolloverService rollovers = new DeliveryRolloverService(jdbc, transactions, tasks,
                new DeliveryEventRepository(jdbc, mapper), new DeliveryProjectionService(jdbc), new PipelineRepository(mapper), mapper);
        assertEquals(1, rollovers.rolloverDelivery(deliveryId, LocalDate.parse("2026-11-06"), LocalDate.parse("2026-11-07"), "merge lineage", "test"));
        long newTask = jdbc.queryForObject("SELECT (payload->>'newTaskId')::bigint FROM delivery_event WHERE delivery_id=? AND event_type='TASK_EXPIRED'",
                Long.class, deliveryId);
        assertNotEquals(taskId, newTask);
        assertEquals("EXPIRED", jdbc.queryForObject("SELECT execution_status FROM delivery_task WHERE task_id=?", String.class, taskId));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM delivery_mapping WHERE task_id=?", Integer.class, newTask));
        assertEquals(planned.stream().map(SourceData::dataId).toList(), sourceRepository.byTaskId(newTask).stream().map(SourceData::dataId).toList());
        assertEquals(jdbc.queryForObject("SELECT artifacts::text FROM delivery_task WHERE task_id=?", String.class, taskId),
                jdbc.queryForObject("SELECT artifacts::text FROM delivery_task WHERE task_id=?", String.class, newTask));
        assertEquals("MERGED", jdbc.queryForObject("SELECT entity_code FROM delivery_task WHERE task_id=?", String.class, newTask));
    }

    @Test void requestAllocationUsesDeliveryBatchAndReusesRange() {
        insertSubmit(300);
        long dataId = insertSource(300, "numbering", 10);
        RequestIdAllocationService allocations = new RequestIdAllocationService(jdbc, transactions);
        var first = allocations.reserve(dataId, 3, "BATCH-300");
        var second = allocations.reserve(dataId, 3, "BATCH-300");
        assertEquals(first, second);
        assertEquals("BATCH-300", jdbc.queryForObject("SELECT request_namespace FROM request_id_allocation WHERE allocation_id=?",
                String.class, first.allocationId()));
        assertThrows(RuntimeException.class, () -> allocations.reserve(dataId, 1, "MISSING-BATCH"));
    }

    @Test void sourceArtifactTimestampsAndDeliveryWidePackageNameAreEnforced() {
        insertSubmit(400);
        for (int entityId = 400; entityId <= 401; entityId++) {
            jdbc.update("INSERT INTO delivery_entity(delivery_entity_id,vendor_code,entity_code,entity_name,protocol,account_name,worker_group,endpoint_locator) " +
                    "VALUES(?,?,?,'Artifact case','FTP',?,'group1','{\"host\":\"localhost\",\"rootPath\":\"/\"}')",
                    entityId, "VENDOR-400", "ENTITY-" + entityId, "entity" + entityId);
        }
        SourceDataRepository sources = new SourceDataRepository(jdbc, mapper);
        DeliveryRepository deliveries = new DeliveryRepository(jdbc, mapper);
        DeliveryTaskRepository tasks = new DeliveryTaskRepository(jdbc, transactions);
        SourceData first = sources.findById(insertSource(400, "artifact-a", 10)).orElseThrow();
        SourceData second = sources.findById(insertSource(400, "artifact-b", 20)).orElseThrow();
        assertNotNull(jdbc.queryForObject("SELECT created_at FROM source_data WHERE data_id=?", OffsetDateTime.class, first.dataId()));
        assertNotNull(jdbc.queryForObject("SELECT updated_at FROM source_data WHERE data_id=?", OffsetDateTime.class, first.dataId()));
        long taskId = tasks.createPlanned(deliveries.find(400).orElseThrow(), List.of(first), deliveries.entity(400), 1, "sourceDownload");
        assertEquals(first.artifact(), tasks.findById(taskId).orElseThrow().artifacts().required(0).required("sourceArtifact"));
        assertThrows(RuntimeException.class, () -> tasks.createPlanned(deliveries.find(400).orElseThrow(), List.of(second),
                deliveries.entity(401), 1, "sourceDownload"));
    }

    @Test void threeEntitiesTwoEachDeliveredBeforeCutoff() {
        long deliveryId = 1000;
        createTwoTargetDelivery(deliveryId, true);
        new DeliveryProjectionService(jdbc).refresh(deliveryId);
        assertEquals("DELIVERED", status(deliveryId));
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM delivery_task WHERE delivery_id=? AND execution_status='SUCCEEDED'",
                Integer.class, deliveryId));
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
        for (int entityId = 1; entityId <= 3; entityId++) {
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM delivery_task WHERE delivery_id=? AND delivery_entity_id=? " +
                    "AND execution_status NOT IN('EXPIRED','CANCELLED')", Integer.class, deliveryId, entityId));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM delivery_task WHERE delivery_id=? AND delivery_entity_id=? " +
                    "AND delivery_date=? AND execution_status='READY'", Integer.class, deliveryId, entityId, nextDate));
        }
        jdbc.update("UPDATE delivery_task SET execution_status='SUCCEEDED',current_node_id='ftpDownloadVerify',completed_at=now(),updated_at=now() " +
                "WHERE delivery_id=? AND delivery_date=?", deliveryId, nextDate);
        projections.refresh(deliveryId);
        assertEquals("DELIVERED", status(deliveryId));
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM delivery_task WHERE delivery_id=? AND execution_status NOT IN('EXPIRED','CANCELLED') " +
                "AND execution_status='SUCCEEDED'", Integer.class, deliveryId));
    }

    private long insertSource(long deliveryId, String key, long size) {
        String sha = String.format("%064x", Math.abs((deliveryId + key).hashCode()));
        String artifact = "{\"artifactSeq\":1,\"originalFileName\":\"" + key + ".tar.gz\",\"newFileName\":\"" + key + ".tar.gz\"," +
                "\"sizeBytes\":" + size + ",\"sha256\":\"" + sha + "\",\"token\":" + size + ",\"file_mapping\":{}}";
        return jdbc.queryForObject("INSERT INTO source_data(delivery_id,original_file_name,original_locator,size_bytes,token_count,sha256,artifact) " +
                        "VALUES(?,?,?::jsonb,?,?,?,?::jsonb) RETURNING data_id", Long.class, deliveryId, key + ".tar.gz",
                "{\"sourceUri\":\"oss://bucket/" + deliveryId + "/" + key + "\",\"sourceVersion\":\"1\"}", size, size, sha, artifact);
    }

    private String status(long deliveryId) {
        return jdbc.queryForObject("SELECT status FROM delivery_submit WHERE delivery_id=?", String.class, deliveryId);
    }

    private void createTwoTargetDelivery(long deliveryId, boolean allSucceeded) {
        insertSubmit(deliveryId);
        DeliveryRepository deliveries = new DeliveryRepository(jdbc, mapper);
        SourceDataRepository sources = new SourceDataRepository(jdbc, mapper);
        DeliveryTaskRepository tasks = new DeliveryTaskRepository(jdbc, transactions);
        for (int entityId = 1; entityId <= 3; entityId++) {
            jdbc.update("INSERT INTO delivery_entity(delivery_entity_id,vendor_code,entity_code,entity_name,protocol,account_name,worker_group,endpoint_locator) " +
                            "VALUES(?,?,?,'State case','FTP',?,?,'{\"host\":\"localhost\",\"rootPath\":\"/\"}') ON CONFLICT(delivery_entity_id) DO NOTHING",
                    entityId, "STATE-" + entityId, "ENTITY-" + entityId, "entity" + entityId, entityId == 3 ? "group2" : "group1");
        }
        jdbc.update("UPDATE delivery_submit SET delivery_date='2026-11-07',target_strategy=?::jsonb,discovery_completed_at=now() WHERE delivery_id=?",
                "{\"schemaVersion\":1,\"targets\":[{\"targetCount\":2,\"vendorCode\":\"STATE-1\",\"entityCode\":\"ENTITY-1\"},{\"targetCount\":2,\"vendorCode\":\"STATE-2\",\"entityCode\":\"ENTITY-2\"},{\"targetCount\":2,\"vendorCode\":\"STATE-3\",\"entityCode\":\"ENTITY-3\"}]}", deliveryId);
        for (int entityId = 1; entityId <= 3; entityId++) {
            for (int sequence = 1; sequence <= 2; sequence++) {
                SourceData source = sources.findById(insertSource(deliveryId, entityId + "-" + sequence, sequence)).orElseThrow();
                int packageSequence = (entityId - 1) * 2 + sequence;
                long taskId = tasks.createPlanned(deliveries.find(deliveryId).orElseThrow(), List.of(source), deliveries.entity(entityId),
                        packageSequence, "sourceDownload");
                boolean succeeded = allSucceeded || sequence < 2;
                jdbc.update("UPDATE delivery_task SET current_node_id=?,execution_status=?,completed_at=?,updated_at=now() WHERE task_id=?",
                        succeeded ? "ftpDownloadVerify" : "targetUpload", succeeded ? "SUCCEEDED" : "READY",
                        succeeded ? OffsetDateTime.now() : null, taskId);
            }
        }
    }

    private static String jdbcUrl() {
        return postgres.getJdbcUrl() + "&sslmode=disable&connectTimeout=10&socketTimeout=30";
    }

    private void insertSubmit(long id) {
        jdbc.update("INSERT INTO ingest_source(source_id,oss_uri,channel_code,oss_type) VALUES(?,?,'TEST','ALIBABA_CLOUD')",
                id, "oss://bucket/prefix/" + id + "/");
        jdbc.update("INSERT INTO delivery_submit(delivery_id,delivery_batch,source_id,pipeline_version_id,delivery_date,cutoff_at,target_strategy) " +
                        "VALUES(?,?,?,1,'2026-11-06',?,'{\"schemaVersion\":1,\"targets\":[]}')",
                id, "BATCH-" + id, id, OffsetDateTime.now().plusDays(1));
    }
}
