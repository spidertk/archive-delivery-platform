package com.powerjob.archive.core.service;

import com.powerjob.archive.core.domain.DiscoveredObject;
import com.powerjob.archive.core.repository.DeliveryRunRepository;
import com.powerjob.archive.core.repository.SourcePackageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class PlanningServiceTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static PlanningService planning;
    static DeliveryRunRepository runs;
    static SourcePackageRepository sources;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + "&currentSchema=archive_delivery", POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).schemas("archive_delivery")
                .defaultSchema("archive_delivery").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        runs = new DeliveryRunRepository(jdbc, transactions);
        sources = new SourcePackageRepository(jdbc, transactions, new ObjectMapper());
        planning = new PlanningService(jdbc, transactions, runs);
    }

    @Test
    void plansFourThreeThreeAndLeavesOldestBacklogUnassigned() {
        long runId = runs.create("oss://junflow-ysai-source-hz-20260906/_derived/ysai-sftp-20261027/delivery_plan_0005/",
                "planning-test", LocalDate.of(2026, 10, 30), 2L);
        for (int index = 0; index < 12; index++) {
            String key = String.format("_derived/ysai-sftp-20261027/delivery_plan_0005/batch_%05d.tar.gz", index + 1);
            sources.upsertDiscovered(runId, new DiscoveredObject("junflow-ysai-source-hz-20260906", key,
                    "etag-" + index, "etag-" + index, 100, 0));
        }
        assertThat(planning.plan(runId, null)).containsExactly(
                java.util.Map.entry("LINE-1", 4), java.util.Map.entry("LINE-2", 3), java.util.Map.entry("LINE-3", 3));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_package WHERE status='PLANNED'", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_package WHERE status='DISCOVERED' " +
                "AND production_line_id IS NULL AND process_date IS NULL", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_package WHERE production_line_id=1", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_package WHERE production_line_id=2", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_package WHERE production_line_id=3", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT process_date) FROM source_package WHERE status='PLANNED'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_package p " +
                "JOIN pipeline_version pv ON pv.id=p.pipeline_version_id " +
                "WHERE pv.definition->'config'->>'crrWaitSeconds'='180'", Integer.class)).isEqualTo(12);
        assertThat(planning.plan(runId, null)).containsExactly(
                java.util.Map.entry("LINE-1", 0), java.util.Map.entry("LINE-2", 0), java.util.Map.entry("LINE-3", 0));
    }
}
