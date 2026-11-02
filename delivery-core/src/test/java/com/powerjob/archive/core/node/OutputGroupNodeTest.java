package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.config.MvpProperties;
import com.powerjob.archive.core.domain.SourcePackage;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeDefinition;
import com.powerjob.archive.core.repository.DeliveryRunRepository;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class OutputGroupNodeTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static OutputGroupNode node;
    static DeliveryRunRepository runs;
    static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + "&currentSchema=archive_delivery", POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).schemas("archive_delivery").defaultSchema("archive_delivery").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        runs = new DeliveryRunRepository(jdbc, transactions);
        node = new OutputGroupNode(jdbc, transactions, new MvpProperties());
    }

    @Test
    void createsIndependentGroupsForRunsOnTheSameDate() throws Exception {
        long firstRun = runs.create("oss://bucket/prefix/", "group-first", LocalDate.of(2026, 10, 30), 1L);
        long replayRun = runs.create("oss://bucket/prefix/", "group-replay", LocalDate.of(2026, 10, 30), 1L);
        long firstSource = source(firstRun, "batch_00001.tar.gz");
        long replaySource = source(replayRun, "batch_00001.tar.gz");

        node.execute(context(firstSource));
        jdbc.update("UPDATE production_group SET target_count=1,status='SEALED' WHERE archive_date=DATE '2026-10-30'");
        node.execute(context(replaySource));

        assertThat(jdbc.queryForObject("SELECT count(DISTINCT production_group_id) FROM output_package", Long.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT g.run_id FROM production_group g JOIN output_package p ON p.production_group_id=g.id " +
                "JOIN source_package s ON s.id=p.source_package_id ORDER BY s.run_id", Long.class))
                .containsExactly(firstRun, replayRun);
    }

    private static long source(long runId, String key) {
        return jdbc.queryForObject("INSERT INTO source_package(source_id,pipeline_version_id,bucket_name,object_key," +
                "object_version,source_size,status,run_status,production_line_id,process_date,run_id) " +
                "VALUES (1,1,'bucket',?,'v1',1,'REPAIRED','SUCCEEDED',1,DATE '2026-10-30',?) RETURNING id",
                Long.class, key, runId);
    }

    private static NodeContext context(long sourceId) throws Exception {
        SourcePackage source = new SourcePackage(sourceId, 1L, null, "bucket", "batch_00001.tar.gz", "v1",
                1, "a".repeat(64), "REPAIRED", "SUCCEEDED", 1L, "group", null, 1,
                MAPPER.readTree("{\"artifacts\":[{\"name\":\"batch_00001.tar.gz\"," +
                        "\"bytes\":1,\"sha256\":\"" + "a".repeat(64) + "\"}]}"));
        return new NodeContext(source, null, new NodeDefinition("group", "SOURCE", "output.group", Map.of()),
                null, 1, 1, "worker");
    }
}
