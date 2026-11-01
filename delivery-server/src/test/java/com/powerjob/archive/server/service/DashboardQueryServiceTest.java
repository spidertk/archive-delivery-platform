package com.powerjob.archive.server.service;

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class DashboardQueryServiceTest {
    private static final String OSS = "oss://junflow-ysai-source-hz-20260906/_derived/ysai-sftp-20261027/delivery_plan_0005/";
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static DashboardQueryService dashboard;
    static long runId;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + "&currentSchema=archive_delivery", POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).schemas("archive_delivery")
                .defaultSchema("archive_delivery").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        DeliveryRunRepository runs = new DeliveryRunRepository(jdbc, transactions);
        SourcePackageRepository sources = new SourcePackageRepository(jdbc, transactions, new ObjectMapper());
        dashboard = new DashboardQueryService(jdbc);
        runId = runs.create(OSS, "dashboard-test", LocalDate.of(2026, 10, 30), 2);
        long first = sources.upsertDiscovered(runId, object(1));
        long second = sources.upsertDiscovered(runId, object(2));
        jdbc.update("UPDATE source_package SET production_line_id=1,process_date='2026-10-30',status='REPAIRED' WHERE id=?", first);
        jdbc.update("UPDATE source_package SET production_line_id=1,process_date='2026-10-30' WHERE id=?", second);
        output(jdbc, first, "batch_00001.tar.gz", "DELIVERED", "SUCCEEDED", 1);
        output(jdbc, first, "batch_00002.tar.gz", "GROUPED", "BLOCKED", 2);
        jdbc.update("INSERT INTO package_node_run(output_package_id,node_id,node_type,implementation,attempt,fence,status," +
                "worker_instance_id,finished_at) VALUES (1,'receipt','delivery.receipt','delivery.receipt',1,1,'SUCCEEDED','worker',now())");
        jdbc.update("INSERT INTO package_event(run_id,event_seq,event_type,message) VALUES (1,1,'DELIVERY_ROLLED_OVER','test')");
    }

    @Test
    void summaryKeepsSixStagesConsistentAndTimelineTraceable() {
        var filter = new DashboardQueryService.Filter(runId, LocalDate.of(2026, 10, 30), null, null, null);
        Map<String, Object> summary = dashboard.summary(filter);
        assertThat(summary.get("outputTotal")).isEqualTo(2L);
        assertThat(summary.get("delivered")).isEqualTo(1L);
        assertThat(summary.get("blocked")).isEqualTo(1L);
        assertThat(summary.get("backlog")).isEqualTo(1L);
        List<Map<String, Object>> groups = dashboard.groups(filter);
        assertThat(groups).hasSize(1);
        Map<String, Object> group = groups.get(0);
        long stages = ((Number) group.get("grouped")).longValue() + ((Number) group.get("hzUploaded")).longValue()
                + ((Number) group.get("sgVerified")).longValue() + ((Number) group.get("sgDownloaded")).longValue()
                + ((Number) group.get("published")).longValue() + ((Number) group.get("delivered")).longValue();
        assertThat(stages).isEqualTo(2);
        assertThat(group.get("blocked")).isEqualTo(1L);
        assertThat(dashboard.packages(filter, null, "00002", 0, 10).get("total")).isEqualTo(1L);
        assertThat(dashboard.packageDetail(1)).containsEntry("receipt_id", "1:sha");
        assertThat(dashboard.timeline(1).get("nodes")).asList().hasSize(1);
        assertThat(dashboard.timeline(1).get("events")).asList().hasSize(1);
        assertThat(dashboard.run(runId)).containsEntry("pipelineVersion", 2);
    }

    private static DiscoveredObject object(int index) {
        return new DiscoveredObject("junflow-ysai-source-hz-20260906",
                "_derived/ysai-sftp-20261027/delivery_plan_0005/batch_0000" + index + ".tar.gz",
                "etag-" + index, "etag-" + index, 7, 0);
    }

    private static void output(JdbcTemplate jdbc, long sourceId, String name, String status, String runStatus, int sequence) {
        Long id = jdbc.queryForObject("INSERT INTO output_package(source_package_id,pipeline_version_id,production_group_id,production_line_id," +
                "archive_date,delivery_date,cutoff_at,group_seq,new_package_name,status,run_status,current_node,manifest,receipt_id) " +
                "VALUES (?,2,1,1,'2026-10-30','2026-10-30','2026-10-31 02:00+08',?,?,?,?,'receipt','{}',?) RETURNING id",
                Long.class, sourceId, sequence, name, status, runStatus, sequence + ":sha");
        jdbc.update("INSERT INTO output_artifact(output_package_id,artifact_seq,new_file_name,size_bytes,sha256) " +
                "VALUES (?,1,?,7,'sha')", id, name);
    }
}
