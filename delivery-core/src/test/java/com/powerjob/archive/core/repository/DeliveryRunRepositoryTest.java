package com.powerjob.archive.core.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.domain.DeliveryRun;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class DeliveryRunRepositoryTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static DeliveryRunRepository runs;
    static OperationAuditRepository audits;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + "&currentSchema=archive_delivery", POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).schemas("archive_delivery")
                .defaultSchema("archive_delivery").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        runs = new DeliveryRunRepository(jdbc, transactions);
        audits = new OperationAuditRepository(jdbc);
    }

    @Test
    void createsIdempotentRunsAndAuditsOperations() {
        assertThat(jdbc.queryForList("SELECT line_code,legacy_batch,provider_code FROM production_line ORDER BY id"))
                .extracting(row -> row.get("line_code") + ":" + row.get("legacy_batch") + ":" + row.get("provider_code"))
                .containsExactly("LINE-1:3:june", "LINE-2:4:june-1", "LINE-3:5:june-2");

        LocalDate jobDate = LocalDate.of(2026, 10, 30);
        long first = runs.create("oss://bucket/prefix/", "same-key", jobDate, 1L);
        long second = runs.create("oss://bucket/prefix/", "same-key", jobDate, 1L);
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_run", Long.class)).isEqualTo(1);
        DeliveryRun run = runs.findById(first).orElseThrow();
        assertThat(run.runCode()).isEqualTo("RUN-20261030-" + first);
        assertThat(run.jobDate()).isEqualTo(jobDate);
        assertThat(run.pipelineVersionId()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns " +
                "WHERE table_schema='archive_delivery' AND table_name='delivery_run' " +
                "AND column_name IN ('crr_wait_seconds','auto_rollover','ftp_clean_before_download')", Long.class))
                .isZero();

        jdbc.update("INSERT INTO source_package(source_id,pipeline_version_id,definition_snapshot,bucket_name," +
                        "object_key,object_version,source_size,run_id) VALUES (1,1,'{}','bucket','batch_00001.tar.gz','v1',1,?)",
                first);
        assertThat(jdbc.queryForObject("SELECT run_id FROM source_package WHERE object_key='batch_00001.tar.gz'", Long.class))
                .isEqualTo(first);

        ObjectMapper mapper = new ObjectMapper();
        long auditId = audits.append(first, null, "RESUME", "verified restart",
                mapper.createObjectNode().put("node", "sgVerify"), mapper.createObjectNode().put("node", "sgDownload"));
        assertThat(jdbc.queryForObject("SELECT action FROM operation_audit WHERE id=?", String.class, auditId))
                .isEqualTo("RESUME");
        assertThat(jdbc.queryForObject("SELECT reason FROM operation_audit WHERE id=?", String.class, auditId))
                .isEqualTo("verified restart");
        assertThat(jdbc.queryForObject("SELECT before_content->>'node' FROM operation_audit WHERE id=?", String.class, auditId))
                .isEqualTo("sgVerify");
        assertThat(jdbc.queryForObject("SELECT after_content->>'node' FROM operation_audit WHERE id=?", String.class, auditId))
                .isEqualTo("sgDownload");
        assertThatThrownBy(() -> audits.append(first, null, "RESUME", " ", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
