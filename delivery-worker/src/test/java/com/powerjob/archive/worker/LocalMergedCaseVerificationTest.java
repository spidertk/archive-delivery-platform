package com.powerjob.archive.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.domain.DiscoveredObject;
import com.powerjob.archive.core.domain.SourceData;
import com.powerjob.archive.core.pipeline.PipelineRepository;
import com.powerjob.archive.core.repository.DeliveryEventRepository;
import com.powerjob.archive.core.repository.DeliveryRepository;
import com.powerjob.archive.core.repository.DeliveryTaskRepository;
import com.powerjob.archive.core.repository.SourceDataRepository;
import com.powerjob.archive.core.service.DeliveryProjectionService;
import com.powerjob.archive.core.service.DeliveryRolloverService;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 只对显式指定的本机 Compose 数据库执行迁移和可保留的三来源映射 case，不替代真实节点验收。 */
class LocalMergedCaseVerificationTest {
    @Test void migrateAndVerifyThreeSourcesOneTaskOnLocalDatabase() {
        assumeTrue("true".equals(System.getenv("ARCHIVE_TEST_LOCAL_PG")), "Explicit local Compose database only");
        boolean reset = "true".equals(System.getenv("ARCHIVE_TEST_LOCAL_SCHEMA_RESET"));
        HikariDataSource dataSource = dataSource(reset ? "2026-11-06" : "2026-11-07");
        try {
            if (reset) {
                Flyway legacy = Flyway.configure().dataSource(dataSource).schemas("archive_delivery")
                        .defaultSchema("archive_delivery").cleanDisabled(false).target("13").load();
                legacy.clean();
                legacy.migrate();
                dataSource.close();
                dataSource = dataSource("2026-11-07");
            }
            Flyway flyway = Flyway.configure().dataSource(dataSource).schemas("archive_delivery")
                    .defaultSchema("archive_delivery").load();
            if (!reset) flyway.repair();
            flyway.migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            assertEquals("17", jdbc.queryForObject("SELECT version FROM archive_delivery.flyway_schema_history " +
                    "ORDER BY installed_rank DESC LIMIT 1", String.class));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='archive_delivery' " +
                    "AND table_name='delivery_mapping'", Integer.class));

            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            DeliveryRepository deliveries = new DeliveryRepository(jdbc, mapper);
            SourceDataRepository sources = new SourceDataRepository(jdbc, mapper);
            DeliveryTaskRepository tasks = new DeliveryTaskRepository(jdbc,
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
            long caseId = System.currentTimeMillis();
            long sourceId = jdbc.queryForObject("INSERT INTO ingest_source(oss_uri,channel_code,oss_type) " +
                    "VALUES(?,'LOCAL-MERGE-CASE','ALIBABA_CLOUD') RETURNING source_id", Long.class,
                    "oss://local-case/" + caseId + "/");
            long entityId = jdbc.queryForObject("INSERT INTO delivery_entity(vendor_code,entity_code,entity_name,protocol," +
                    "account_name,worker_group,endpoint_locator) VALUES(?,'LOCAL-MERGE','Local merge case','FTP','june','group1'," +
                    "'{\"host\":\"localhost\",\"rootPath\":\"/\"}'::jsonb) RETURNING delivery_entity_id", Long.class,
                    "LOCAL-MERGE-" + caseId);
            long deliveryId = jdbc.queryForObject("INSERT INTO delivery_submit(delivery_batch,source_id,pipeline_version_id," +
                    "delivery_date,cutoff_at,target_strategy) VALUES(?,?,1,?,now()+interval '2 days',?::jsonb) RETURNING delivery_id", Long.class,
                    "LOCAL-MERGE-" + caseId, sourceId, LocalDate.now(),
                    "{\"schemaVersion\":1,\"targets\":[{\"vendorCode\":\"LOCAL-MERGE-" + caseId + "\",\"entityCode\":\"LOCAL-MERGE\",\"targetCount\":1}]}");
            List<SourceData> planned = new ArrayList<>();
            for (int index = 1; index <= 3; index++) {
                DiscoveredObject object = new DiscoveredObject("local-case", deliveryId + "/source-" + index + ".tar.gz",
                        "1", "", 1024 * 1024, String.format("%064x", index));
                long dataId = sources.insertDiscovered(deliveryId, object).dataId();
                planned.add(sources.findById(dataId).orElseThrow());
            }
            long taskId = tasks.createPlanned(deliveries.find(deliveryId).orElseThrow(), planned,
                    deliveries.entity(entityId), 1, "sourceDownload");
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM source_data WHERE delivery_id=?", Integer.class, deliveryId));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM delivery_task WHERE delivery_id=?", Integer.class, deliveryId));
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM delivery_mapping WHERE task_id=?", Integer.class, taskId));
            assertEquals(3, jdbc.queryForObject("SELECT jsonb_array_length(artifacts) FROM delivery_task WHERE task_id=?", Integer.class, taskId));

            DeliveryRolloverService rollovers = new DeliveryRolloverService(jdbc,
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource)), tasks,
                    new DeliveryEventRepository(jdbc, mapper), new DeliveryProjectionService(jdbc), new PipelineRepository(mapper), mapper);
            LocalDate initialDate = jdbc.queryForObject("SELECT delivery_date FROM delivery_submit WHERE delivery_id=?", LocalDate.class, deliveryId);
            assertEquals(1, rollovers.rolloverDelivery(deliveryId, initialDate, initialDate.plusDays(1), "local merged case", "local-test"));
            long newTaskId = jdbc.queryForObject("SELECT (payload->>'newTaskId')::bigint FROM delivery_event " +
                    "WHERE delivery_id=? AND event_type='TASK_EXPIRED'", Long.class, deliveryId);
            assertNotEquals(taskId, newTaskId);
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM delivery_mapping WHERE task_id=?", Integer.class, newTaskId));
            assertEquals(planned.stream().map(SourceData::dataId).toList(),
                    sources.byTaskId(newTaskId).stream().map(SourceData::dataId).toList());
            System.out.println("LOCAL MERGED CASE PASSED deliveryId=" + deliveryId + " taskId=" + taskId
                    + " rolloverTaskId=" + newTaskId + " sourceCount=3 linksPerTask=3");
        } finally {
            dataSource.close();
        }
    }

    private static HikariDataSource dataSource(String upgradeAuthorization) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(required("ARCHIVE_DB_URL"));
        dataSource.setUsername(required("ARCHIVE_DB_USER"));
        dataSource.setPassword(required("ARCHIVE_DB_PASSWORD"));
        dataSource.setSchema("archive_delivery");
        dataSource.setConnectionInitSql("SET archive_delivery.upgrade_authorized='" + upgradeAuthorization + "'");
        return dataSource;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
