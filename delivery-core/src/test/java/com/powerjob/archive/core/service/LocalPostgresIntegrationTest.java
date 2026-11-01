package com.powerjob.archive.core.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ARCHIVE_TEST_LOCAL_PG", matches = "true")
class LocalPostgresIntegrationTest {
    @Test
    void initializesAndConnectsToLocalDockerPostgres() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                envOrDefault("ARCHIVE_DB_URL", "jdbc:postgresql://localhost:55432/archive_delivery"),
                envOrDefault("ARCHIVE_DB_USER", "archive"),
                envOrDefault("ARCHIVE_DB_PASSWORD", "archive_local"));
        var result = Flyway.configure().dataSource(dataSource)
                .schemas("archive_delivery").defaultSchema("archive_delivery").load().migrate();
        assertThat(result.success).isTrue();

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("archive_delivery");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables " +
                "WHERE table_schema='archive_delivery' AND table_name IN " +
                "('source_package','source_member','output_package','request_mapping','output_artifact')",
                Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM archive_delivery.production_line", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM archive_delivery.production_group g " +
                "JOIN archive_delivery.production_line l ON l.id=g.production_line_id " +
                "WHERE l.line_code='LINE-3' AND g.target_count=3", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM archive_delivery.pipeline_version " +
                "WHERE pipeline_id=1 AND version_no=1 AND status='PUBLISHED'", Integer.class)).isEqualTo(1);
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
