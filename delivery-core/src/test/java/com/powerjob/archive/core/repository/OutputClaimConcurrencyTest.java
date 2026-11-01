package com.powerjob.archive.core.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.domain.DiscoveredObject;
import com.powerjob.archive.core.domain.OutputPackage;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class OutputClaimConcurrencyTest {
    private static final String OSS = "oss://junflow-ysai-source-hz-20260906/_derived/ysai-sftp-20261027/delivery_plan_0005/";
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static OutputPackageRepository outputs;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + "&currentSchema=archive_delivery", POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).schemas("archive_delivery")
                .defaultSchema("archive_delivery").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ObjectMapper mapper = new ObjectMapper();
        outputs = new OutputPackageRepository(jdbc, transactions, mapper);
        DeliveryRunRepository runs = new DeliveryRunRepository(jdbc, transactions);
        SourcePackageRepository sources = new SourcePackageRepository(jdbc, transactions, mapper);
        long runId = runs.create(OSS, "output-claims", LocalDate.of(2026, 10, 30), 2);
        for (int index = 0; index < 12; index++) {
            long line = index < 6 ? 1 : 2;
            long sourceId = sources.upsertDiscovered(runId, new DiscoveredObject("junflow-ysai-source-hz-20260906",
                    "_derived/ysai-sftp-20261027/delivery_plan_0005/batch_000" + String.format("%02d", index) + ".tar.gz",
                    "etag-" + index, "etag-" + index, 7, 0));
            jdbc.update("UPDATE source_package SET production_line_id=?,process_date='2026-10-30' WHERE id=?", line, sourceId);
            jdbc.update("INSERT INTO output_package(source_package_id,pipeline_version_id,production_group_id,production_line_id," +
                    "archive_date,delivery_date,cutoff_at,group_seq,new_package_name,manifest) " +
                    "VALUES (?,2,?,?,'2026-10-30','2026-10-30','2026-10-31 02:00+08',?,?,'{}')",
                    sourceId, line == 1 ? 1 : 2, line, line == 1 ? index + 1 : index - 5,
                    String.format("batch_%05d.tar.gz", index + 1));
        }
    }

    @Test
    void neverExceedsTenGlobalRunningOutputsOrDuplicatesClaims() throws Exception {
        Set<Long> claimed = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(3);
        try {
            List<java.util.concurrent.Future<?>> workers = new ArrayList<>();
            for (int worker = 0; worker < 3; worker++) {
                String workerId = "worker-" + worker;
                long lineId = worker % 2 + 1;
                workers.add(pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    while (true) {
                        OutputPackage next = outputs.claimNext(lineId, workerId).orElse(null);
                        if (next == null) return;
                        if (!claimed.add(next.id())) throw new AssertionError("duplicate claim " + next.id());
                    }
                }));
            }
            start.countDown();
            for (var worker : workers) worker.get();
        } finally {
            pool.shutdownNow();
        }
        assertThat(claimed).hasSize(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM output_package WHERE run_status='RUNNING'", Integer.class))
                .isEqualTo(10);
        assertThat(outputs.claimNext(1, "late")).isEmpty();
    }
}
