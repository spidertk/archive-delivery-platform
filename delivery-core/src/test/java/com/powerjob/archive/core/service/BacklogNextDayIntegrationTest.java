package com.powerjob.archive.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.domain.DiscoveredObject;
import com.powerjob.archive.core.repository.DeliveryRunRepository;
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

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class BacklogNextDayIntegrationTest {
    private static final String OSS = "oss://junflow-ysai-source-hz-20260906/_derived/ysai-sftp-20261027/delivery_plan_0005/";
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static PlanningService planning;
    static SourcePackageRepository sources;
    static long runId;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + "&currentSchema=archive_delivery", POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).schemas("archive_delivery")
                .defaultSchema("archive_delivery").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        DeliveryRunRepository runs = new DeliveryRunRepository(jdbc, transactions);
        sources = new SourcePackageRepository(jdbc, transactions, new ObjectMapper());
        planning = new PlanningService(jdbc, transactions, runs);
        runId = runs.create(OSS, "backlog-next-day", LocalDate.of(2026, 10, 30), 2);
        for (int index = 1; index <= 12; index++) discover(index);
    }

    @Test
    void oldestUnacceptedBacklogPlansBeforeNewObjectsNextDay() {
        assertThat(planning.plan(runId, null).values()).containsExactly(4, 3, 3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_package WHERE status='DISCOVERED'", Integer.class))
                .isEqualTo(2);
        for (long sourceId = 1; sourceId <= 10; sourceId++) {
            long line = sourceId <= 4 ? 1 : sourceId <= 7 ? 2 : 3;
            jdbc.update("UPDATE source_package SET status='REPAIRED',run_status='SUCCEEDED' WHERE id=?", sourceId);
            jdbc.update("INSERT INTO output_package(source_package_id,pipeline_version_id,production_group_id,production_line_id," +
                    "archive_date,delivery_date,cutoff_at,group_seq,new_package_name,status,run_status,manifest) " +
                    "SELECT ?,2,?,?,'2026-10-30','2026-10-30','2026-10-31 02:00+08',?,?,'DELIVERED','SUCCEEDED','{}'",
                    sourceId, line, line, sourceId, "batch_" + String.format("%05d", sourceId) + ".tar.gz");
        }
        for (int index = 13; index <= 22; index++) discover(index);
        discover(23);
        jdbc.update("UPDATE source_package SET status='PLANNED',production_line_id=1,process_date='2026-10-30' " +
                "WHERE object_key LIKE '%batch_00023.tar.gz'");
        var planned = planning.plan(runId, LocalDate.of(2026, 10, 31));
        assertThat(planned.values()).containsExactly(3, 3, 3);
        assertThat(jdbc.queryForList("SELECT id FROM source_package WHERE process_date='2026-10-31' ORDER BY id LIMIT 2",
                Long.class)).containsExactly(11L, 12L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_package WHERE status='DISCOVERED'", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT process_date::text FROM source_package WHERE object_key LIKE '%batch_00023.tar.gz'",
                String.class)).isEqualTo("2026-10-30");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_package", Integer.class)).isEqualTo(23);
    }

    private static void discover(int index) {
        sources.upsertDiscovered(runId, new DiscoveredObject("junflow-ysai-source-hz-20260906",
                "_derived/ysai-sftp-20261027/delivery_plan_0005/batch_" + String.format("%05d", index) + ".tar.gz",
                "etag-" + index, "etag-" + index, 100, 0));
    }
}
