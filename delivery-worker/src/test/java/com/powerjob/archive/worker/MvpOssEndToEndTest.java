package com.powerjob.archive.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.service.MvpSeedService;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tech.powerjob.worker.core.processor.TaskContext;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 OSS 输入的端到端验收：从 junflow-ysai-source-hz-20260906 的 delivery_plan_0005 前缀
 * 按 Key 排序取前 10 个 .tar.gz（前 5 个 LINE-1，后 5 个 LINE-2），真实下载、repair、
 * 分组、杭州/新加坡本地适配器传输、FTP 原子发布与回执，直到 10 个输出包全部 DELIVERED。
 *
 * <p>与 MvpEndToEndTest（同样本复制 10 份 + Testcontainers）不同，本测试访问真实 OSS，
 * 并使用本地 Docker PostgreSQL（compose 的 deploy-postgres-1）。每次运行前会
 * Flyway clean + migrate 重置 archive_delivery schema，因此只适用于可丢弃的本地开发库。
 *
 * <p>如何运行 / Debug（IntelliJ IDEA）：
 * <ol>
 *   <li>Project SDK 必须是 JDK 17；本机默认 java 是 11，请在 IDEA 里 Download JDK 选 Temurin 17。</li>
 *   <li>启动数据库：{@code docker compose -f deploy/docker-compose.yml up -d postgres}；
 *       本机直连默认 {@code jdbc:postgresql://localhost:55432/archive_delivery}（archive/archive_local），
 *       需要覆盖时在 Run Configuration → Environment variables 里加 ARCHIVE_DB_URL/ARCHIVE_DB_USER/ARCHIVE_DB_PASSWORD。</li>
 *   <li>Environment variables 必须加：ARCHIVE_TEST_LOCAL_PG=true、OSS_SOURCE_ACCESS_KEY_ID、
 *       OSS_SOURCE_ACCESS_KEY_SECRET（值在 deploy/.env；可选 OSS_SOURCE_ENDPOINT，
 *       默认 https://oss-cn-hangzhou.aliyuncs.com）。缺任一项测试会被
 *       {@code @EnabledIfEnvironmentVariable} 静默跳过（不是失败），这是最常见的“跑不起来”原因。</li>
 *   <li>本机 PATH 里要有 python3（repair 节点会真实拉起子进程）。</li>
 *   <li>直接对测试方法点 Debug。断点建议：PipelineEngine.executeSource/executeOutput（看节点调度）、
 *       OssSourceDownloader.download（看真实 OSS 拉流）、PythonRepairNode.run（看 repair 子进程）、
 *       本类的 assertPublishedTarLineage（看逐包血缘校验）。tickUntil 会循环推进，断点会反复命中，
 *       只想看最终态就把断点打在 assertThat 之后。</li>
 *   <li>运行时长约 5–8 分钟（真实下载约 420MB），工作目录在测试结束后打印在控制台，可手动清理。</li>
 * </ol>
 * 警告：{@code @BeforeAll} 每次都会 Flyway clean + migrate 重置 archive_delivery schema，
 * 不要把 ARCHIVE_DB_URL 指向不可清空的数据库。
 */
@SpringBootTest(properties = {
        "archive.discovery-mode=oss",
        "archive.debug-loop=false",
        "powerjob.worker.enabled=false",
        "spring.flyway.enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "ARCHIVE_TEST_LOCAL_PG", matches = "true")
@EnabledIfEnvironmentVariable(named = "OSS_SOURCE_ACCESS_KEY_ID", matches = ".+")
class MvpOssEndToEndTest {
    private static final Path PROJECT = Path.of(System.getProperty("user.dir")).getParent();
    private static final Path TEMP = createTemp();
    private static final String PREFIX = "_derived/ysai-sftp-20261027/delivery_plan_0005/";
    private static final Pattern NEW_ID = Pattern.compile("req_20261030_\\d{9}");
    private static final Pattern ORIGINAL_ID = Pattern.compile("req_20261027_\\d+");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired DataSource dataSource;
    @Autowired MvpSeedService seeds;
    @Autowired PackageTickProcessor processor;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("archive.work-root", () -> TEMP.resolve("work").toString());
        registry.add("archive.hz-root", () -> TEMP.resolve("hz").toString());
        registry.add("archive.sg-root", () -> TEMP.resolve("sg").toString());
        registry.add("archive.ftp-root", () -> TEMP.resolve("ftp").toString());
        registry.add("archive.python-command", () -> "python3");
        registry.add("archive.python-script", () -> PROJECT.resolve("nodes/python/archive_repair.py").toString());
    }

    @BeforeAll
    void resetSchema() {
        flyway().clean();
        flyway().migrate();
    }

    @AfterAll
    static void reportWorkDir() {
        System.out.println("MvpOssEndToEndTest work dir kept for inspection: " + TEMP);
    }

    @Test
    void deliversTenRealOssPackagesWithFullLineage() throws Exception {
        assertThat(seeds.seed()).hasSize(10);
        assertThat(seeds.seed()).hasSize(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_package", Integer.class)).isEqualTo(10);

        assertThat(keysOfLine(1L)).containsExactlyElementsOf(expectedKeys("00001", "00002", "00003", "00004", "00005"));
        assertThat(keysOfLine(2L)).containsExactlyElementsOf(expectedKeys("00006", "00007", "00008", "00009", "00010"));

        tickUntil(() -> countDelivered() == 10, 300);

        assertThat(countDelivered()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM production_group WHERE status='SEALED' " +
                "AND target_count=5 AND assigned_count=5", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT group_seq) FROM output_package " +
                "WHERE production_line_id=1", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT group_seq) FROM output_package " +
                "WHERE production_line_id=2", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_package WHERE status='REPAIRED' " +
                "AND run_status='SUCCEEDED' AND source_sha256 IS NOT NULL", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM output_package WHERE archive_date=DATE '2026-10-30' " +
                "AND delivery_date=DATE '2026-10-30' " +
                "AND cutoff_at='2026-10-31 02:00:00+08'::timestamptz", Integer.class)).isEqualTo(10);

        assertNodeRunsClean();
        assertLineageComplete();
        assertNumberingPartition();
        assertArtifactsPublished();
        assertNoPartialFilesLeft();
        assertPublishedTarLineage();
    }

    private void assertNodeRunsClean() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM package_node_run WHERE status='SUCCEEDED'", Integer.class))
                .isEqualTo(90);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM package_node_run WHERE status<>'SUCCEEDED'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM package_event", Integer.class)).isEqualTo(90);
        List<String> nodes = jdbc.queryForList(
                "SELECT node_id FROM package_node_run GROUP BY node_id ORDER BY min(id)", String.class);
        assertThat(nodes).containsExactly(
                "download", "repair", "qualify", "group", "hzUpload", "sgVerify", "sgDownload", "publish", "receipt");
        for (String node : nodes) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM package_node_run WHERE node_id=? AND status='SUCCEEDED'",
                    Integer.class, node)).isEqualTo(10);
        }
    }

    private void assertLineageComplete() {
        int members = jdbc.queryForObject("SELECT count(*) FROM source_member", Integer.class);
        int mappings = jdbc.queryForObject("SELECT count(*) FROM request_mapping", Integer.class);
        assertThat(members).isGreaterThanOrEqualTo(10);
        assertThat(mappings).isEqualTo(members);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT new_request_id) FROM request_mapping", Integer.class))
                .isEqualTo(mappings);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM request_mapping WHERE output_package_id IS NULL " +
                "OR original_request_id IS NULL OR new_request_id IS NULL", Integer.class)).isZero();

        List<String> newIds = jdbc.queryForList("SELECT new_request_id FROM request_mapping", String.class);
        assertThat(newIds).allMatch(id -> NEW_ID.matcher(id).matches());
        List<String> originalIds = jdbc.queryForList("SELECT original_request_id FROM request_mapping", String.class);
        assertThat(originalIds).allMatch(id -> ORIGINAL_ID.matcher(id).matches());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_package sp WHERE " +
                "(SELECT count(*) FROM source_member sm WHERE sm.source_package_id=sp.id) <> " +
                "(SELECT count(*) FROM request_mapping rm JOIN source_member sm2 ON sm2.id=rm.source_member_id " +
                "WHERE sm2.source_package_id=sp.id)", Integer.class)).isZero();
    }

    private void assertNumberingPartition() {
        List<long[]> ranges = jdbc.query("SELECT start_value,count_value FROM number_reservation ORDER BY start_value",
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)});
        assertThat(ranges).hasSize(10);
        assertThat(ranges.get(0)[0]).isEqualTo(1L);
        long expectedStart = 1;
        long total = 0;
        for (long[] range : ranges) {
            assertThat(range[0]).isEqualTo(expectedStart);
            expectedStart += range[1];
            total += range[1];
        }
        assertThat(total).isEqualTo(jdbc.queryForObject("SELECT count(*) FROM request_mapping", Long.class));
    }

    private void assertArtifactsPublished() throws Exception {
        List<Map<String, Object>> artifacts = jdbc.queryForList(
                "SELECT op.id,op.production_line_id,op.published_path,op.receipt_id,oa.sha256,oa.new_file_name " +
                "FROM output_artifact oa JOIN output_package op ON op.id=oa.output_package_id " +
                "WHERE oa.artifact_seq=1 ORDER BY op.id");
        assertThat(artifacts).hasSize(10);
        for (Map<String, Object> artifact : artifacts) {
            long outputId = ((Number) artifact.get("id")).longValue();
            String provider = ((Number) artifact.get("production_line_id")).longValue() == 1L ? "june" : "june-1";
            Path published = Path.of((String) artifact.get("published_path"));
            assertThat(published).exists();
            assertThat(published.toString()).endsWith(provider + "/2026-10-30/" + artifact.get("new_file_name"));
            String sha = sha256(published);
            assertThat(artifact.get("sha256")).isEqualTo(sha);
            assertThat(artifact.get("receipt_id")).isEqualTo(outputId + ":" + sha);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM output_package WHERE id=? AND hz_path IS NOT NULL " +
                    "AND sg_source_path IS NOT NULL AND sg_download_path IS NOT NULL AND delivered_at IS NOT NULL",
                    Integer.class, outputId)).isEqualTo(1);
        }
    }

    private void assertNoPartialFilesLeft() throws Exception {
        for (String root : List.of("hz", "sg", "ftp")) {
            Path rootPath = TEMP.resolve(root);
            if (!Files.exists(rootPath)) {
                continue;
            }
            try (var walk = Files.walk(rootPath)) {
                List<Path> partial = walk.filter(path -> path.getFileName().toString().endsWith(".partial")).toList();
                assertThat(partial).as("no .partial left under %s", root).isEmpty();
            }
        }
    }

    private void assertPublishedTarLineage() throws Exception {
        List<Map<String, Object>> packages = jdbc.queryForList(
                "SELECT op.id,op.published_path,l.provider_code FROM output_package op " +
                "JOIN production_line l ON l.id=op.production_line_id ORDER BY op.id");
        for (Map<String, Object> output : packages) {
            long outputId = ((Number) output.get("id")).longValue();
            String provider = (String) output.get("provider_code");
            Map<String, String> mappings = new HashMap<>();
            for (Map<String, Object> row : jdbc.queryForList("SELECT rm.new_request_id,sm.original_request_id " +
                    "FROM request_mapping rm JOIN source_member sm ON sm.id=rm.source_member_id " +
                    "WHERE rm.output_package_id=?", outputId)) {
                mappings.put((String) row.get("new_request_id"), (String) row.get("original_request_id"));
            }
            assertThat(mappings).isNotEmpty();

            Map<String, JsonNode> metadataByRequest = new HashMap<>();
            Map<String, Set<String>> filesByRequest = new HashMap<>();
            try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(
                    Files.newInputStream(Path.of((String) output.get("published_path")))))) {
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
            assertThat(metadataByRequest.keySet()).as("tar requests of output %s", outputId)
                    .containsExactlyInAnyOrderElementsOf(mappings.keySet());
            for (Map.Entry<String, JsonNode> request : metadataByRequest.entrySet()) {
                String newId = request.getKey();
                JsonNode metadata = request.getValue();
                Set<String> files = filesByRequest.get(newId);
                assertThat(metadata.required("requestId").asText()).isEqualTo(newId);
                assertThat(metadata.required("providerCode").asText()).isEqualTo(provider);
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
    }

    private List<String> keysOfLine(long lineId) {
        return jdbc.queryForList("SELECT object_key FROM source_package WHERE production_line_id=? ORDER BY object_key",
                String.class, lineId);
    }

    private static List<String> expectedKeys(String... batches) {
        List<String> keys = new ArrayList<>();
        for (String batch : batches) {
            keys.add(PREFIX + "batch_" + batch + ".tar.gz");
        }
        return keys;
    }

    private long countDelivered() {
        return jdbc.queryForObject("SELECT count(*) FROM output_package WHERE status='DELIVERED'", Long.class);
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
            return Files.createTempDirectory("archive-mvp-oss-e2e");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
