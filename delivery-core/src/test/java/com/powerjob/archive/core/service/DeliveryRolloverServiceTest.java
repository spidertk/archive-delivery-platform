package com.powerjob.archive.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.domain.DiscoveredObject;
import com.powerjob.archive.core.domain.OutputPackage;
import com.powerjob.archive.core.repository.DeliveryRunRepository;
import com.powerjob.archive.core.repository.OperationAuditRepository;
import com.powerjob.archive.core.repository.OutputPackageRepository;
import com.powerjob.archive.core.repository.SourcePackageRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class DeliveryRolloverServiceTest {
    private static final String OSS = "oss://junflow-ysai-source-hz-20260906/_derived/ysai-sftp-20261027/delivery_plan_0005/";
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static DeliveryRolloverService rollovers;
    static OutputPackageRepository outputs;
    static long runId;
    static long sourceId;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + "&currentSchema=archive_delivery", POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).schemas("archive_delivery")
                .defaultSchema("archive_delivery").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ObjectMapper mapper = new ObjectMapper();
        DeliveryRunRepository runs = new DeliveryRunRepository(jdbc, transactions);
        SourcePackageRepository sources = new SourcePackageRepository(jdbc, transactions, mapper);
        outputs = new OutputPackageRepository(jdbc, transactions, mapper);
        rollovers = new DeliveryRolloverService(jdbc, transactions, new OperationAuditRepository(jdbc), mapper, Clock.systemUTC());
        runId = runs.create(OSS, "rollover-test", LocalDate.of(2026, 10, 30), 2);
        sourceId = sources.upsertDiscovered(runId, new DiscoveredObject("junflow-ysai-source-hz-20260906",
                "_derived/ysai-sftp-20261027/delivery_plan_0005/batch_00001.tar.gz", "etag", "etag", 7, 0));
        jdbc.update("UPDATE source_package SET production_line_id=1,process_date='2026-10-30' WHERE id=?", sourceId);
        String[] statuses = {"GROUPED", "HZ_UPLOADED", "HZ_UPLOADED", "GROUPED", "DELIVERED"};
        String[] runStatuses = {"READY", "RUNNING", "WAITING", "BLOCKED", "SUCCEEDED"};
        String[] nodes = {"hzUpload", "receipt", "sgVerify", "hzUpload", "receipt"};
        for (int index = 0; index < 5; index++) {
            jdbc.update("INSERT INTO output_package(source_package_id,pipeline_version_id,production_group_id,production_line_id," +
                    "archive_date,delivery_date,cutoff_at,group_seq,new_package_name,status,run_status,current_node,manifest) " +
                    "VALUES (?,2,1,1,'2026-10-30','2026-10-30','2026-10-31 02:00+08',?,?,?,?,?,'{}')",
                    sourceId, index + 1, "batch_0000" + (index + 1) + ".tar.gz", statuses[index], runStatuses[index], nodes[index]);
        }
    }

    @Test
    void manualAndAutomaticRolloverShareIdempotentTransaction() {
        assertThat(rollovers.autoRollover()).isZero();
        assertThat(rollovers.rolloverRun(runId, LocalDate.of(2026, 10, 30), "历史作业日复盘")).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM output_package WHERE delivery_date='2026-10-31'", Integer.class))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT delivery_date::text FROM output_package WHERE status='DELIVERED'", String.class))
                .isEqualTo("2026-10-30");
        assertThat(jdbc.queryForObject("SELECT run_status FROM output_package WHERE id=2", String.class)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT run_status FROM output_package WHERE id=4", String.class)).isEqualTo("BLOCKED");
        assertThat(jdbc.queryForObject("SELECT current_node FROM output_package WHERE id=2", String.class)).isEqualTo("receipt");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM package_event WHERE event_type='DELIVERY_ROLLED_OVER'", Integer.class))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operation_audit WHERE action='ROLLOVER'", Integer.class))
                .isEqualTo(4);
        assertThat(rollovers.rolloverRun(runId, LocalDate.of(2026, 10, 30), "重复入口")).isZero();

        jdbc.update("INSERT INTO output_package(source_package_id,pipeline_version_id,production_group_id,production_line_id," +
                "archive_date,delivery_date,cutoff_at,group_seq,new_package_name,status,run_status,current_node,manifest) " +
                "VALUES (?,2,1,1,'2026-10-30','2026-10-31','2099-11-02 02:00+08',6,'batch_00006.tar.gz'," +
                "'GROUPED','READY','hzUpload','{}')", sourceId);
        OutputPackage first = outputs.claimNext(1, "worker").orElseThrow();
        assertThat(first.id()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT rollover_count FROM output_package WHERE id=1", Integer.class))
                .isEqualTo(1);

        jdbc.update("UPDATE pipeline_version SET definition=jsonb_set(definition,'{config,autoRollover}','true') WHERE id=2");
        assertThat(rollovers.autoRollover()).isEqualTo(4);
        assertThat(rollovers.autoRollover()).isEqualTo(4);
        assertThat(rollovers.autoRollover()).isZero();
    }
}
