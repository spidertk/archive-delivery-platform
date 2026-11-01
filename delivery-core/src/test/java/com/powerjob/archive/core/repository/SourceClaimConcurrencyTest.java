package com.powerjob.archive.core.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.domain.DiscoveredObject;
import com.powerjob.archive.core.domain.SourcePackage;
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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class SourceClaimConcurrencyTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static SourcePackageRepository sources;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + "&currentSchema=archive_delivery", POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).schemas("archive_delivery")
                .defaultSchema("archive_delivery").load().migrate();
        sources = new SourcePackageRepository(new JdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), new ObjectMapper());
    }

    @Test
    void permitsOneRunningSourcePerLineWithoutBlockingOtherLines() throws Exception {
        String snapshot = sourcesSnapshot();
        for (int index = 0; index < 2; index++) {
            sources.upsertPlanned(object(index, 1), snapshot, "2026-10-30");
        }
        sources.upsertPlanned(object(2, 2), snapshot, "2026-10-30");
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(3);
        try {
            var first = pool.submit(claim("worker-1", 1, start));
            var second = pool.submit(claim("worker-2", 1, start));
            var otherLine = pool.submit(claim("worker-3", 2, start));
            start.countDown();
            List<Optional<SourcePackage>> claims = List.of(first.get(), second.get());
            assertThat(claims.stream().filter(Optional::isPresent)).hasSize(1);
            assertThat(otherLine.get()).isPresent();
        } finally {
            pool.shutdownNow();
        }
    }

    private static Callable<Optional<SourcePackage>> claim(String worker, long line, CountDownLatch start) {
        return () -> {
            start.await();
            return sources.claimNext(line, worker);
        };
    }

    private static DiscoveredObject object(int index, long line) {
        return new DiscoveredObject("bucket", "batch_0000" + index + ".tar.gz", "etag-" + index,
                "etag-" + index, 100, line);
    }

    private static String sourcesSnapshot() {
        return "{\"pipelineCode\":\"archive-delivery\",\"version\":1,\"nodes\":[]}";
    }
}
