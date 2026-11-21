package com.powerjob.archive.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.domain.BatchPackage;
import com.powerjob.archive.core.domain.DeliverySubmit;
import com.powerjob.archive.core.domain.SourceData;
import com.powerjob.archive.core.pipeline.PipelineRepository;
import com.powerjob.archive.core.repository.DeliveryBatchRepository;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class ArchiveModelIntegrationTest {
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("archive_delivery").withUsername("archive").withPassword("archive_local");
    static com.zaxxer.hikari.HikariDataSource dataSource;
    static JdbcTemplate jdbc;
    static TransactionTemplate transactions;
    static ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    static AtomicLong ids = new AtomicLong(1000);

    @BeforeAll static void migrate() {
        postgres.start();
        dataSource = new com.zaxxer.hikari.HikariDataSource();
        dataSource.setJdbcUrl(jdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        dataSource.setSchema("archive_delivery");
        Flyway.configure().dataSource(dataSource).schemas("archive_delivery").defaultSchema("archive_delivery").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.update("UPDATE pipeline_version SET status='PUBLISHED',published_at=now() WHERE pipeline_version_id IN(1,2)");
    }

    @AfterAll static void stop() { dataSource.close(); postgres.stop(); }

    @Test void currentBaselineIsIdempotentAndContainsOnlyBatchLineage() {
        int before = jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='archive_delivery'", Integer.class);
        Flyway.configure().dataSource(dataSource).schemas("archive_delivery").defaultSchema("archive_delivery").load().migrate();
        assertEquals("25", jdbc.queryForObject("SELECT max(version) FROM archive_delivery.flyway_schema_history WHERE success", String.class));
        assertEquals(before, jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='archive_delivery'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='archive_delivery' " +
                "AND table_name='delivery_mapping'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='archive_delivery' " +
                "AND table_name='delivery_task' AND column_name='metadata_ids'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='archive_delivery' " +
                "AND table_name='source_data' AND column_name='unique_key'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pipeline WHERE pipeline_code='archive-batch'", Integer.class));
    }

    @Test void sourceUniqueKeyAndArtifactWhitelistAreEnforced() {
        long batch = insertBatch();
        try (Connection connection = DriverManager.getConnection(jdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            String artifact = "{\"artifactSeq\":1,\"originalFileName\":\"source.tar.gz\",\"newFileName\":\"source.tar.gz\"," +
                    "\"sizeBytes\":1,\"sha256\":\"" + "0".repeat(64) + "\",\"token\":1,\"file_mapping\":{},\"extra\":true}";
            assertThrows(Exception.class, () -> statement.execute("INSERT INTO archive_delivery.source_data(delivery_id,original_file_name," +
                    "original_locator,size_bytes,token_count,sha256,unique_key,artifact) VALUES(" + batch + ",'source.tar.gz'," +
                    "'{\"sourceUri\":\"oss://bucket/a\",\"sourceVersion\":\"1\"}',1,1,'" + "0".repeat(64) + "','"
                    + "0".repeat(64) + "','" + artifact + "')"));
            long data = insertSource(batch, "unique-source", 10);
            String sha = jdbc.queryForObject("SELECT unique_key FROM source_data WHERE data_id=?", String.class, data);
            assertThrows(Exception.class, () -> statement.execute("INSERT INTO archive_delivery.source_data(delivery_id,original_file_name," +
                    "original_locator,size_bytes,token_count,sha256,unique_key,artifact) SELECT delivery_id,'copy.tar.gz'," +
                    "original_locator,size_bytes,token_count,sha256,unique_key,artifact FROM archive_delivery.source_data WHERE data_id=" + data));
            assertEquals(sha, jdbc.queryForObject("SELECT sha256 FROM source_data WHERE data_id=?", String.class, data));
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    @Test void oneBatchSupportsTwoIndependentSubmissionsAndOverallDeliveryProjection() {
        long batchId = insertBatch();
        long entityId = ids.incrementAndGet();
        insertEntity(entityId, "VENDOR-" + entityId, "jun");
        DeliveryRepository deliveries = new DeliveryRepository(jdbc, mapper);
        SourceDataRepository sources = new SourceDataRepository(jdbc, mapper);
        DeliveryBatchRepository batches = new DeliveryBatchRepository(jdbc);
        DeliveryTaskRepository tasks = new DeliveryTaskRepository(jdbc, transactions, batches, sources);
        for (int sequence = 1; sequence <= 4; sequence++) {
            SourceData source = sources.findById(insertSource(batchId, "two-submit-" + sequence, 10)).orElseThrow();
            String name = String.format("batch_%05d.tar.gz", sequence);
            batches.insertPlanned(batchId, source, "BATCH-" + batchId, source.originalFileName(), name, 1,
                    entityId, "jun", sequence <= 2 ? "group1" : "group2");
        }
        markBatchPlanned(batchId);
        DeliverySubmissionService submission = new DeliverySubmissionService(jdbc, transactions, mapper, deliveries,
                new DeliveryEventRepository(jdbc, mapper), new PipelineRepository(mapper));
        var command = new DeliverySubmissionService.SubmissionCommand("oss://bucket/prefix/" + batchId + "/", "archive-delivery",
                LocalDate.now().plusDays(1), "BATCH-" + batchId,
                List.of(new DeliverySubmissionService.TargetCommand(2, "VENDOR-" + entityId, "jun")));
        long first = submission.submit(command).deliveryId();
        long second = submission.submit(command).deliveryId();
        assertNotEquals(first, second);
        DeliveryPreparationService preparation = new DeliveryPreparationService(jdbc, transactions, mapper, deliveries, batches,
                tasks, new DeliveryEventRepository(jdbc, mapper), new DeliveryProjectionService(jdbc), new PipelineRepository(mapper));
        preparation.prepare(first);
        preparation.prepare(second);
        assertEquals(List.of("batch_00001.tar.gz", "batch_00002.tar.gz"), tasks.byDelivery(first).stream()
                .map(task -> task.newFileName()).toList());
        assertEquals(List.of("batch_00003.tar.gz", "batch_00004.tar.gz"), tasks.byDelivery(second).stream()
                .map(task -> task.newFileName()).toList());
        assertEquals("group2", tasks.byDelivery(second).get(0).workerGroup());
        for (long deliveryId : List.of(first, second)) {
            for (var task : tasks.byDelivery(deliveryId)) {
                jdbc.update("UPDATE delivery_task SET execution_status='SUCCEEDED',current_node_id='ftpDownloadVerify',completed_at=now() WHERE task_id=?",
                        task.taskId());
                new DeliveryProjectionService(jdbc).refresh(deliveryId);
            }
            assertEquals("DELIVERED", status(deliveryId));
        }
        assertEquals("DELIVERED", status(batchId));
        assertEquals(4, jdbc.queryForObject("SELECT (batch_extension->>'packageCount')::int FROM delivery_submit WHERE delivery_id=?",
                Integer.class, batchId));
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM delivery_batch WHERE batch_delivery_id=? AND status='DELIVERED'",
                Integer.class, batchId));
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM delivery_task WHERE delivery_id IN(?,?) AND execution_status='SUCCEEDED'",
                Integer.class, first, second));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM delivery_batch WHERE batch_delivery_id=? AND delivery_id IS NULL",
                Integer.class, batchId));
    }

    @Test void batchMustBePlannedBeforeSubmission() {
        long batchId = insertBatch();
        long entityId = ids.incrementAndGet();
        insertEntity(entityId, "VENDOR-" + entityId, "jun");
        DeliverySubmissionService submission = new DeliverySubmissionService(jdbc, transactions, mapper,
                new DeliveryRepository(jdbc, mapper), new DeliveryEventRepository(jdbc, mapper), new PipelineRepository(mapper));
        var command = new DeliverySubmissionService.SubmissionCommand("oss://bucket/prefix/" + batchId + "/", "archive-delivery",
                LocalDate.now().plusDays(1), "BATCH-" + batchId,
                List.of(new DeliverySubmissionService.TargetCommand(1, "VENDOR-" + entityId, "jun")));
        assertThrows(IdempotencyConflictException.class, () -> submission.submit(command));
    }

    @Test void pauseRetryAndRolloverKeepDeliveryBatchSources() {
        long batchId = insertBatch();
        long entityId = ids.incrementAndGet();
        insertEntity(entityId, "VENDOR-" + entityId, "jun");
        DeliveryRepository deliveries = new DeliveryRepository(jdbc, mapper);
        SourceDataRepository sources = new SourceDataRepository(jdbc, mapper);
        DeliveryBatchRepository batches = new DeliveryBatchRepository(jdbc);
        DeliveryTaskRepository tasks = new DeliveryTaskRepository(jdbc, transactions, batches, sources);
        long submitId = insertSubmission(batchId, entityId, 1);
        SourceData source = sources.findById(insertSource(batchId, "operations", 10)).orElseThrow();
        batches.insertPlanned(batchId, source, "BATCH-" + batchId, source.originalFileName(), "batch_00001.tar.gz", 1,
                entityId, "jun", "group1");
        long taskId = tasks.createPlanned(deliveries.find(submitId).orElseThrow(),
                new BatchPackage("batch_00001.tar.gz", "group1", 1, 10), deliveries.entity(entityId), "sourceDownload");
        jdbc.update("UPDATE delivery_submit SET discovery_completed_at=now() WHERE delivery_id=?", submitId);
        DeliveryProjectionService projections = new DeliveryProjectionService(jdbc);
        DeliveryEventRepository events = new DeliveryEventRepository(jdbc, mapper);
        TaskOperationService operations = new TaskOperationService(jdbc, transactions, tasks, sources,
                new DeliveryNodeRepository(jdbc, mapper), events, projections, new PipelineRepository(mapper), mapper);
        assertEquals(1, operations.pause(submitId, "acceptance pause", "test"));
        assertEquals("PAUSED", jdbc.queryForObject("SELECT status FROM delivery_batch WHERE delivery_id=?", String.class, submitId));
        assertEquals(1, tasks.resumePaused(submitId));
        projections.refresh(submitId);
        assertEquals("PROCESSING", jdbc.queryForObject("SELECT status FROM delivery_batch WHERE delivery_id=?", String.class, submitId));
        jdbc.update("INSERT INTO delivery_node(delivery_id,task_id,original_file_name,new_file_name,node_id,node_type,stage_code,attempt,fence,status,output_locator) " +
                        "VALUES(?,?,'operations.tar.gz','batch_00001.tar.gz','repair','python.repair','REPAIR',1,0,'SUCCEEDED',?::jsonb)",
                submitId, taskId, "{\"nodeId\":\"repair\",\"localPath\":\"/tmp/batch_00001.tar.gz\",\"sha256\":\"" + "a".repeat(64) + "\"}");
        DeliveryRolloverService rollovers = new DeliveryRolloverService(jdbc, transactions, tasks, events, projections,
                new PipelineRepository(mapper), mapper);
        LocalDate original = jdbc.queryForObject("SELECT delivery_date FROM delivery_submit WHERE delivery_id=?", LocalDate.class, submitId);
        assertEquals(1, rollovers.rolloverDelivery(submitId, original, original.plusDays(1), "cutoff continuation", "test"));
        long newTask = jdbc.queryForObject("SELECT (payload->>'newTaskId')::bigint FROM delivery_event WHERE delivery_id=? AND event_type='TASK_EXPIRED'",
                Long.class, submitId);
        assertEquals(List.of(source.dataId()), sources.byTaskId(newTask).stream().map(SourceData::dataId).toList());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM delivery_batch WHERE delivery_id=? AND new_file_name='batch_00001.tar.gz'",
                Integer.class, submitId));
        jdbc.update("UPDATE delivery_task SET cutoff_at=now()-interval '1 minute' WHERE task_id=?", newTask);
        assertEquals(1, rollovers.rolloverDue());
    }

    @Test void repairOverflowMovesWholeSourceIntoAdditionalPlannedPackage() {
        long batchId = insertBatch();
        long entityId = ids.incrementAndGet();
        insertEntity(entityId, "VENDOR-" + entityId, "jun");
        DeliveryRepository deliveries = new DeliveryRepository(jdbc, mapper);
        SourceDataRepository sources = new SourceDataRepository(jdbc, mapper);
        DeliveryBatchRepository batches = new DeliveryBatchRepository(jdbc);
        DeliveryTaskRepository tasks = new DeliveryTaskRepository(jdbc, transactions, batches, sources);
        long submitId = insertSubmission(batchId, entityId, 1);
        List<SourceData> planned = new java.util.ArrayList<>();
        for (int index = 1; index <= 4; index++) {
            SourceData source = sources.findById(insertSource(batchId, "overflow-" + index, index * 10L)).orElseThrow();
            planned.add(source);
            batches.insertPlanned(batchId, source, "BATCH-" + batchId, source.originalFileName(),
                    "batch_00001.tar.gz", index, entityId, "jun", "group1");
        }
        long taskId = tasks.createPlanned(deliveries.find(submitId).orElseThrow(),
                new BatchPackage("batch_00001.tar.gz", "group1", 4, 100), deliveries.entity(entityId), "sourceDownload");
        jdbc.update("UPDATE delivery_task SET execution_status='RUNNING',lease_owner='worker',lease_until=now()+interval '1 minute' WHERE task_id=?", taskId);
        var result = new BatchReplanService(jdbc, tasks, sources, deliveries, mapper)
                .moveLastSourceToNewPackage(taskId, 0, "worker");
        assertEquals(List.of(planned.get(2).dataId(), planned.get(3).dataId()), result.dataIds());
        assertEquals("batch_00002.tar.gz", result.newFileName());
        assertEquals("READY", tasks.findById(taskId).orElseThrow().executionStatus());
        assertEquals(2, tasks.findById(taskId).orElseThrow().artifacts().size());
        assertEquals(30, tasks.findById(taskId).orElseThrow().sizeBytes());
        assertEquals("PLANNED", jdbc.queryForObject("SELECT DISTINCT status FROM delivery_batch WHERE batch_delivery_id=? AND new_file_name='batch_00002.tar.gz'",
                String.class, batchId));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM delivery_batch WHERE batch_delivery_id=? AND new_file_name='batch_00002.tar.gz'",
                Integer.class, batchId));
        assertEquals(List.of(1, 2), jdbc.queryForList("SELECT source_seq FROM delivery_batch WHERE batch_delivery_id=? " +
                "AND new_file_name='batch_00002.tar.gz' ORDER BY source_seq", Integer.class, batchId));
    }

    @Test void requestAllocationUsesSharedBatchNamespace() {
        long batchId = insertBatch();
        long dataId = insertSource(batchId, "numbering", 10);
        RequestIdAllocationService allocations = new RequestIdAllocationService(jdbc, transactions);
        var first = allocations.reserve(dataId, 3, "BATCH-" + batchId, "1:batch_00001.tar.gz");
        var second = allocations.reserve(dataId, 3, "BATCH-" + batchId, "1:batch_00001.tar.gz");
        assertEquals(first, second);
        var expanded = allocations.reserve(dataId, 5, "BATCH-" + batchId, "1:batch_00001.tar.gz");
        assertEquals(first.allocationId(), expanded.allocationId());
        assertEquals(5, expanded.count());
        var otherPackage = allocations.reserve(dataId, 3, "BATCH-" + batchId, "1:batch_00002.tar.gz");
        assertNotEquals(first.allocationId(), otherPackage.allocationId());
    }

    @Test void simultaneousWorkersCannotExceedGroupCapacity() throws Exception {
        // 四个真实 PG 事务争抢同组四个 READY；容量为 1 时只能有一个 claim 成功。
        long batchId = insertBatch();
        long entityId = ids.incrementAndGet();
        insertEntity(entityId, "VENDOR-" + entityId, "jun");
        String group = "capacity" + entityId;
        DeliveryRepository deliveries = new DeliveryRepository(jdbc, mapper);
        SourceDataRepository sources = new SourceDataRepository(jdbc, mapper);
        DeliveryBatchRepository batches = new DeliveryBatchRepository(jdbc);
        DeliveryTaskRepository tasks = new DeliveryTaskRepository(jdbc, transactions, batches, sources);
        long submitId = insertSubmission(batchId, entityId, 4);
        for (int index = 1; index <= 4; index++) {
            SourceData source = sources.findById(insertSource(batchId, "capacity-" + index, 10)).orElseThrow();
            String name = String.format("batch_%05d.tar.gz", index);
            batches.insertPlanned(batchId, source, "BATCH-" + batchId, source.originalFileName(), name,
                    1, entityId, "jun", group);
            tasks.createPlanned(deliveries.find(submitId).orElseThrow(), new BatchPackage(name, group, 1, 10),
                    deliveries.entity(entityId), "sourceDownload");
        }
        var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int index = 0; index < 4; index++) {
                String worker = "capacity-worker-" + index;
                futures.add(executor.submit(() -> {
                    start.await();
                    return tasks.claimNext(worker, group, 1, 900).isPresent();
                }));
            }
            start.countDown();
            int claimed = 0;
            for (var future : futures) if (future.get(10, java.util.concurrent.TimeUnit.SECONDS)) claimed++;
            assertEquals(1, claimed);
        } finally {
            executor.shutdownNow();
            jdbc.update("UPDATE delivery_task SET execution_status='PAUSED' WHERE delivery_id=?", submitId);
        }
    }

    private long insertBatch() {
        long id = ids.incrementAndGet();
        jdbc.update("INSERT INTO ingest_source(source_id,oss_uri,channel_code,oss_type) VALUES(?,?,'TEST','ALIBABA_CLOUD')",
                id, "oss://bucket/prefix/" + id + "/");
        jdbc.update("INSERT INTO delivery_submit(delivery_id,delivery_batch,source_id,pipeline_version_id,delivery_date,cutoff_at," +
                        "submitted_at,target_strategy,submission_type,batch_extension) VALUES(?,?,?,2,?,?,NULL,?::jsonb,'BATCH_CREATE',?::jsonb)",
                id, "BATCH-" + id, id, LocalDate.now().plusDays(1), OffsetDateTime.now().plusDays(2),
                "{\"schemaVersion\":1,\"targets\":[]}", "{\"maxPackageBytes\":104857600}");
        return id;
    }

    private long insertSubmission(long batchId, long entityId, int count) {
        long id = ids.incrementAndGet();
        String strategy = "{\"schemaVersion\":1,\"targets\":[{\"targetCount\":" + count + ",\"vendorCode\":\"VENDOR-"
                + entityId + "\",\"entityCode\":\"jun\"}]}";
        jdbc.update("INSERT INTO delivery_submit(delivery_id,delivery_batch,source_id,pipeline_version_id,delivery_date,cutoff_at," +
                        "target_strategy,submission_type,batch_delivery_id,batch_extension) SELECT ?,delivery_batch,source_id,1,delivery_date,cutoff_at," +
                        "?::jsonb,'BATCH_SUBMIT',delivery_id,batch_extension FROM delivery_submit WHERE delivery_id=?",
                id, strategy, batchId);
        return id;
    }

    private void markBatchPlanned(long batchId) {
        jdbc.update("UPDATE delivery_submit SET discovery_completed_at=now() WHERE delivery_id=?", batchId);
        new DeliveryProjectionService(jdbc).refresh(batchId);
    }

    private long insertSource(long deliveryId, String key, long size) {
        String sha = String.format("%064x", (deliveryId + key).hashCode()).replace('-', 'f');
        if (sha.length() > 64) sha = sha.substring(0, 64);
        String artifact = "{\"artifactSeq\":1,\"originalFileName\":\"" + key + ".tar.gz\",\"newFileName\":\"" + key + ".tar.gz\"," +
                "\"sizeBytes\":" + size + ",\"sha256\":\"" + sha + "\",\"token\":" + size + ",\"file_mapping\":{}}";
        return jdbc.queryForObject("INSERT INTO source_data(delivery_id,original_file_name,original_locator,size_bytes,token_count,sha256,unique_key,artifact) " +
                        "VALUES(?,?,?::jsonb,?,?,?,?,?::jsonb) RETURNING data_id", Long.class, deliveryId, key + ".tar.gz",
                "{\"sourceUri\":\"oss://bucket/" + deliveryId + "/" + key + "\",\"sourceVersion\":\"1\"}", size, size, sha, sha, artifact);
    }

    private void insertEntity(long id, String vendor, String entity) {
        jdbc.update("INSERT INTO delivery_entity(delivery_entity_id,vendor_code,entity_code,entity_name,protocol,account_name,worker_group,endpoint_locator) " +
                "VALUES(?,?,?,'Batch entity','FTP',?,'group1','{\"host\":\"localhost\",\"rootPath\":\"/\"}')",
                id, vendor, entity, "account" + id);
    }

    private String status(long deliveryId) {
        return jdbc.queryForObject("SELECT status FROM delivery_submit WHERE delivery_id=?", String.class, deliveryId);
    }

    private static String jdbcUrl() {
        return postgres.getJdbcUrl() + "&sslmode=disable&connectTimeout=10&socketTimeout=30";
    }
}
