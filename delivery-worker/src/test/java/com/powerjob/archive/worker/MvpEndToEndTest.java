package com.powerjob.archive.worker;

import com.powerjob.archive.core.repository.OutputPackageRepository;
import com.powerjob.archive.core.service.DeliveryRolloverService;
import com.powerjob.archive.core.service.MvpSeedService;
import com.powerjob.archive.core.service.NumberReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tech.powerjob.worker.core.processor.TaskContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同样本复制 10 份 + Testcontainers PostgreSQL 的端到端测试，不访问真实 OSS。
 *
 * <p>如何运行 / Debug（IntelliJ IDEA）：Project SDK 必须是 JDK 17（本机默认 java 是 11，
 * IDEA 里 Download JDK 选 Temurin 17）；需要本机 Docker daemon（Testcontainers 自动拉起
 * postgres:16-alpine）和 python3；样本默认取 {@code <仓库>/old/sample/batch_00002.tar.gz}，
 * 容器内跑时用 ARCHIVE_TEST_SAMPLE 指定（见 scripts/mvp-test.sh）。
 * 本机 Docker Desktop for Mac 会拒绝 Testcontainers 内置 docker-java 默认的 Docker API v1.32
 * 请求（HTTP 400），因此运行配置 VM options 需要加 {@code -Dapi.version=1.43}。
 * 断点建议：PipelineEngine.executeSource/executeOutput（节点调度）、OutputGroupNode.group
 * （分组与封组）、DeliveryRolloverService.rollover（截止流转）。
 */
@SpringBootTest(properties = {
        "archive.discovery-mode=local",
        "archive.debug-loop=false",
        "powerjob.worker.enabled=false"
})
@Testcontainers
class MvpEndToEndTest {
    private static final Path PROJECT = Path.of(System.getProperty("user.dir")).getParent();
    private static final Path SAMPLE = samplePath();
    private static final Path TEMP = createTemp();
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("archive.local-source-dir", () -> TEMP.resolve("sources").toString());
        registry.add("archive.work-root", () -> TEMP.resolve("work").toString());
        registry.add("archive.hz-root", () -> TEMP.resolve("hz").toString());
        registry.add("archive.sg-root", () -> TEMP.resolve("sg").toString());
        registry.add("archive.ftp-root", () -> TEMP.resolve("ftp").toString());
        registry.add("archive.python-command", () -> "python3");
        registry.add("archive.python-script", () -> PROJECT.resolve("nodes/python/archive_repair.py").toString());
    }

    @Autowired MvpSeedService seeds;
    @Autowired PackageTickProcessor processor;
    @Autowired OutputPackageRepository outputs;
    @Autowired NumberReservationService numbers;
    @Autowired DeliveryRolloverService rollovers;
    @Autowired JdbcTemplate jdbc;

    @Test
    void deliversFourThreeThreePackagesToThreeFtpGroups() throws Exception {
        Path sourceDir = TEMP.resolve("sources");
        Files.createDirectories(sourceDir);
        for (int index = 0; index < 10; index++) {
            Files.copy(SAMPLE, sourceDir.resolve(String.format("batch_%05d.tar.gz", index + 2)),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        assertThat(seeds.seed()).hasSize(10);
        assertThat(seeds.seed()).hasSize(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_package", Integer.class)).isEqualTo(10);

        var first = numbers.reserve(1L, 4, "customer-request:20261030");
        assertThat(numbers.reserve(1L, 4, "customer-request:20261030")).isEqualTo(first);

        int ticks = 0;
        while (jdbc.queryForObject("SELECT count(*) FROM output_package", Integer.class) == 0 && ticks++ < 20) {
            processor.process(new TaskContext());
        }
        Long firstOutput = jdbc.queryForObject("SELECT min(id) FROM output_package", Long.class);
        Long originalGroup = jdbc.queryForObject("SELECT production_group_id FROM output_package WHERE id=?",
                Long.class, firstOutput);
        jdbc.update("UPDATE output_package SET cutoff_at=now()-interval '1 minute' WHERE id=?", firstOutput);
        assertThat(rollovers.rollover(1L)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT delivery_date::text FROM output_package WHERE id=?",
                String.class, firstOutput)).isEqualTo("2026-10-31");
        assertThat(jdbc.queryForObject("SELECT production_group_id FROM output_package WHERE id=?",
                Long.class, firstOutput)).isEqualTo(originalGroup);

        while (outputs.countDelivered() < 10 && ticks++ < 300) {
            processor.process(new TaskContext());
            Thread.sleep(20);
        }
        assertThat(outputs.countDelivered()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM production_group WHERE status='SEALED' " +
                "AND assigned_count=target_count AND target_count IN (4,3)", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM request_mapping", Integer.class)).isEqualTo(40);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT new_request_id) FROM request_mapping", Integer.class))
                .isEqualTo(40);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_package WHERE status='REPAIRED' " +
                "AND run_status='SUCCEEDED'", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM output_artifact WHERE published_path IS NOT NULL",
                Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM package_node_run WHERE status='SUCCEEDED'",
                Integer.class)).isEqualTo(90);
    }

    private static Path samplePath() {
        String override = System.getenv("ARCHIVE_TEST_SAMPLE");
        return override == null || override.isBlank()
                ? PROJECT.getParent().resolve("old/sample/batch_00002.tar.gz")
                : Path.of(override);
    }

    private static Path createTemp() {
        try {
            return Files.createTempDirectory("archive-mvp-e2e");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
