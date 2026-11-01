package com.powerjob.archive.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.config.MvpProperties;
import com.powerjob.archive.core.service.FileTransferService;
import com.powerjob.archive.core.service.MvpSeedService;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tech.powerjob.worker.core.processor.TaskContext;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重试语义端到端：HZ upload 首次执行注入一次瞬时 IO 异常，断言输出包保持 GROUPED + RETRY_WAIT、
 * fence 递增、仅重试 hzUpload 节点，最终 DELIVERED；repair 不重复执行、requestMapping 不变、
 * 编号区间不变。使用本地 Docker PostgreSQL（compose 的 deploy-postgres-1）与本地样本目录适配器，
 * 每次运行前 Flyway clean + migrate 重置 archive_delivery schema，因此只适用于可丢弃的本地开发库。
 *
 * <p>如何运行 / Debug（IntelliJ IDEA）：
 * <ol>
 *   <li>Project SDK 必须是 JDK 17（本机默认 java 是 11，IDEA 里 Download JDK 选 Temurin 17）。</li>
 *   <li>启动数据库：{@code docker compose -f deploy/docker-compose.yml up -d postgres}；
 *       本机直连默认 {@code jdbc:postgresql://localhost:55432/archive_delivery}（archive/archive_local）。
 *       从 IDEA 直接跑不需要 OSS 凭据。</li>
 *   <li>Environment variables 必须加 ARCHIVE_TEST_LOCAL_PG=true，否则测试会被
 *       {@code @EnabledIfEnvironmentVariable} 静默跳过（不是失败）。</li>
 *   <li>本机 PATH 里要有 python3；样本默认取 {@code <仓库>/old/sample/batch_00002.tar.gz}，
 *       路径不对时用 ARCHIVE_TEST_SAMPLE 环境变量指定。</li>
 *   <li>推荐 Debug 断点（按执行顺序）：TransientHzUploadFailureConfig 匿名类的 copyVerified
 *       （看注入时机与条件：targetRoot==hzRoot 且 relativePath 以 june/ 开头）、
 *       PipelineEngine.executeOutput 的 catch 分支（看 markRetry 调用）、
 *       OutputPackageRepository.markRetry/complete（看 fence 条件更新）、
 *       LocalHzUploadNode.execute（看重试成功后如何推进 sgVerify）。</li>
 *   <li>运行时长约 1 分钟。失败现场可在 {@code @BeforeAll} 重跑前用 runbook 第 7 节的 SQL 查询，
 *       注意下一次运行会清库。</li>
 * </ol>
 * 警告：{@code @BeforeAll} 每次都会 Flyway clean + migrate 重置 archive_delivery schema，
 * 不要把 ARCHIVE_DB_URL 指向不可清空的数据库。
 */
@SpringBootTest(properties = {
        "archive.discovery-mode=local",
        "archive.group-size=1",
        "archive.debug-loop=false",
        "powerjob.worker.enabled=false",
        "spring.flyway.enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "ARCHIVE_TEST_LOCAL_PG", matches = "true")
class RetryEndToEndTest {
    private static final Path PROJECT = Path.of(System.getProperty("user.dir")).getParent();
    private static final Path SAMPLE = envOrDefault("ARCHIVE_TEST_SAMPLE",
            PROJECT.getParent().resolve("old/sample/batch_00002.tar.gz"));
    private static final Path TEMP = createTemp();
    private static final Pattern NEW_ID = Pattern.compile("req_20261030_\\d{9}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired DataSource dataSource;
    @Autowired MvpSeedService seeds;
    @Autowired PackageTickProcessor processor;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("archive.local-source-dir", () -> TEMP.resolve("sources").toString());
        registry.add("archive.work-root", () -> TEMP.resolve("work").toString());
        registry.add("archive.hz-root", () -> TEMP.resolve("hz").toString());
        registry.add("archive.sg-root", () -> TEMP.resolve("sg").toString());
        registry.add("archive.ftp-root", () -> TEMP.resolve("ftp").toString());
        registry.add("archive.python-command", () -> "python3");
        registry.add("archive.python-script", () -> PROJECT.resolve("nodes/python/archive_repair.py").toString());
    }

    @BeforeAll
    void resetSchemaAndSources() throws Exception {
        flyway().clean();
        flyway().migrate();
        Path sourceDir = TEMP.resolve("sources");
        Files.createDirectories(sourceDir);
        Files.copy(SAMPLE, sourceDir.resolve("batch_90001.tar.gz"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(SAMPLE, sourceDir.resolve("batch_90002.tar.gz"), StandardCopyOption.REPLACE_EXISTING);
        for (String root : List.of("work", "hz", "sg", "ftp")) {
            Files.createDirectories(TEMP.resolve(root));
        }
    }

    @Test
    void retriesOnlyHzUploadAfterOneTransientIoFailure() throws Exception {
        assertThat(seeds.seed()).hasSize(2);
        assertThat(seeds.seed()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_package", Integer.class)).isEqualTo(2);

        tickUntil(() -> count("SELECT count(*) FROM output_package") == 2, 60);
        long output1 = jdbc.queryForObject(
                "SELECT min(id) FROM output_package WHERE production_line_id=1", Long.class);
        long source1 = jdbc.queryForObject(
                "SELECT source_package_id FROM output_package WHERE id=?", Long.class, output1);

        awaitFailure(output1);

        Map<String, Object> failed = jdbc.queryForMap("SELECT status,run_status,current_node,retry_count," +
                "fence,hz_path FROM output_package WHERE id=?", output1);
        assertThat(failed.get("status")).isEqualTo("GROUPED");
        assertThat(failed.get("run_status")).isEqualTo("RETRY_WAIT");
        assertThat(failed.get("current_node")).isEqualTo("hzUpload");
        assertThat((Number) failed.get("retry_count")).isEqualTo(1);
        assertThat((Number) failed.get("fence")).isEqualTo(1L);
        assertThat(failed.get("hz_path")).isNull();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM package_node_run WHERE output_package_id=? " +
                "AND node_id='hzUpload' AND status='FAILED' AND attempt=1 AND " +
                "error_detail LIKE '%injected%'", Integer.class, output1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM package_node_run WHERE output_package_id=? " +
                "AND node_id<>'hzUpload'", Integer.class, output1)).isZero();
        for (String node : List.of("download", "repair", "qualify", "group")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM package_node_run WHERE source_package_id=? " +
                    "AND node_id=? AND status='SUCCEEDED' AND attempt=1", Integer.class, source1, node))
                    .isEqualTo(1);
        }

        List<String> reservedIds = jdbc.queryForList("SELECT new_request_id FROM request_mapping rm " +
                "JOIN source_member sm ON sm.id=rm.source_member_id WHERE sm.source_package_id=? ORDER BY 1",
                String.class, source1);
        assertThat(reservedIds).hasSize(4).allMatch(id -> NEW_ID.matcher(id).matches());
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT new_request_id) FROM request_mapping rm " +
                "JOIN source_member sm ON sm.id=rm.source_member_id WHERE sm.source_package_id=?",
                Integer.class, source1)).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT start_value FROM number_reservation WHERE source_package_id=?",
                Long.class, source1)).containsExactly(1L);
        assertThat(jdbc.queryForList("SELECT count_value FROM number_reservation WHERE source_package_id=?",
                Integer.class, source1)).containsExactly(4);
        assertNoPartialFiles();

        Thread.sleep(1100);
        tickUntil(() -> count("SELECT count(*) FROM output_package WHERE status='DELIVERED'") == 2, 60);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM package_node_run WHERE output_package_id=? " +
                "AND node_id='hzUpload' AND status='SUCCEEDED' AND attempt=2", Integer.class, output1)).isEqualTo(1);
        for (String node : List.of("sgVerify", "sgDownload", "publish", "receipt")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM package_node_run WHERE output_package_id=? " +
                    "AND node_id=? AND status='SUCCEEDED' AND attempt=1", Integer.class, output1, node))
                    .isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM package_node_run WHERE source_package_id=? " +
                "AND node_id='repair'", Integer.class, source1)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT new_request_id FROM request_mapping rm " +
                "JOIN source_member sm ON sm.id=rm.source_member_id WHERE sm.source_package_id=? ORDER BY 1",
                String.class, source1)).containsExactlyElementsOf(reservedIds);
        assertThat(jdbc.queryForList("SELECT start_value FROM number_reservation WHERE source_package_id=?",
                Long.class, source1)).containsExactly(1L);

        Map<String, Object> delivered = jdbc.queryForMap("SELECT published_path,receipt_id,status FROM " +
                "output_package WHERE id=?", output1);
        assertThat(delivered.get("status")).isEqualTo("DELIVERED");
        Path published = Path.of((String) delivered.get("published_path"));
        assertThat(published).exists();
        String sha = sha256(published);
        assertThat(delivered.get("receipt_id")).isEqualTo(output1 + ":" + sha);
        assertNoPartialFiles();
        assertPublishedRepair(published);
    }

    private void assertPublishedRepair(Path published) throws Exception {
        Map<String, JsonNode> metadataByRequest = new HashMap<>();
        Map<String, Set<String>> filesByRequest = new HashMap<>();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(Files.newInputStream(published)))) {
            var entry = tar.getNextEntry();
            while (entry != null) {
                String name = entry.getName();
                int slash = name.indexOf('/');
                if (slash > 0) {
                    String top = name.substring(0, slash);
                    String rest = name.substring(slash + 1);
                    if (!rest.isEmpty()) {
                        filesByRequest.computeIfAbsent(top, key -> new HashSet<>()).add(rest);
                        if (rest.equals("metadata.json")) {
                            metadataByRequest.put(top, MAPPER.readTree(tar.readAllBytes()));
                        }
                    }
                }
                entry = tar.getNextEntry();
            }
        }
        assertThat(metadataByRequest).hasSize(4);
        for (Map.Entry<String, JsonNode> request : metadataByRequest.entrySet()) {
            JsonNode metadata = request.getValue();
            Set<String> files = filesByRequest.get(request.getKey());
            assertThat(metadata.required("requestId").asText()).isEqualTo(request.getKey());
            assertThat(metadata.required("providerCode").asText()).isEqualTo("june");
            assertThat(metadata.required("requestTime").asText()).startsWith("2026-10-30 ");
            assertThat(files).contains("biz_request.json");
            String mode = metadata.path("requestMode").asText("");
            if (mode.equals("STREAM")) {
                assertThat(files).contains("biz_stream_response.jsonl");
            } else if (mode.equals("SYNC")) {
                assertThat(files).contains("biz_response.json");
            }
        }
    }

    private void assertNoPartialFiles() throws Exception {
        for (String root : List.of("hz", "sg", "ftp")) {
            try (var walk = Files.walk(TEMP.resolve(root))) {
                List<Path> partial = walk.filter(path -> path.getFileName().toString().endsWith(".partial")).toList();
                assertThat(partial).as("no .partial left under %s", root).isEmpty();
            }
        }
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private void awaitFailure(long outputId) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer failed = jdbc.queryForObject("SELECT count(*) FROM output_package WHERE id=? " +
                    "AND last_error_code='IOException' AND run_status='RETRY_WAIT'", Integer.class, outputId);
            if (failed != null && failed == 1) return;
            Thread.sleep(40);
        }
        throw new AssertionError("RETRY_WAIT was not observed for output " + outputId);
    }

    private void tickUntil(BooleanSupplier condition, int maxTicks) throws Exception {
        int ticks = 0;
        while (!condition.getAsBoolean() && ticks++ < maxTicks) {
            processor.process(new TaskContext());
            Thread.sleep(40);
        }
        assertThat(condition.getAsBoolean()).as("condition reached within %s ticks", maxTicks).isTrue();
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(dataSource)
                .schemas("archive_delivery").defaultSchema("archive_delivery").load();
    }

    private static String sha256(Path path) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static Path createTemp() {
        try {
            return Files.createTempDirectory("archive-mvp-retry-e2e");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Path envOrDefault(String name, Path fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Path.of(value);
    }

    @TestConfiguration
    static class TransientHzUploadFailureConfig {
        @Bean
        @Primary
        FileTransferService flakyFileTransferService(MvpProperties properties) {
            return new FileTransferService() {
                private final AtomicBoolean injected = new AtomicBoolean();

                @Override
                public Path copyVerified(Path source, Path targetRoot, String relativePath,
                                         String expectedSha) throws IOException {
                    if (!injected.get()
                            && Path.of(properties.getHzRoot()).equals(targetRoot)
                            && relativePath.startsWith("june/")) {
                        injected.set(true);
                        throw new IOException("injected transient hz-upload failure");
                    }
                    return super.copyVerified(source, targetRoot, relativePath, expectedSha);
                }
            };
        }
    }
}
