package com.powerjob.archive.worker;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.pipeline.OssNodeConfig;
import com.powerjob.archive.core.pipeline.PipelineDefinition;
import com.powerjob.archive.core.pipeline.PipelineNodeConfig;
import com.powerjob.archive.core.pipeline.PipelineRepository;
import com.powerjob.archive.core.service.BatchPlanningService;
import com.powerjob.archive.core.service.FileTransferService;
import com.powerjob.archive.core.service.OssClientFactory;
import com.powerjob.archive.core.service.OssTransferService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RealExternalVerificationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final FileTransferService files = new FileTransferService();
    private final OssClientFactory clients = new OssClientFactory();
    private final OssTransferService transfers = new OssTransferService(clients, files);
    private final PipelineNodeConfig configs = new PipelineNodeConfig(mapper);
    @TempDir Path temp;

    @Test void preflight() throws Exception {
        assumeTrue("true".equals(System.getenv("ARCHIVE_PREFLIGHT")), "Explicit real preflight only");
        JdbcTemplate jdbc = jdbc();
        PipelineDefinition batchPipeline = publishedPipeline(jdbc, "archive-batch");
        PipelineDefinition deliveryPipeline = publishedPipeline(jdbc, "archive-delivery");
        BatchPlanningService.Limits limits = BatchPlanningService.limits(batchPipeline);
        OssNodeConfig source = config(batchPipeline, "oss.download", true);
        URI sourceUri = URI.create(required("ARCHIVE_VERIFY_SOURCE_URI"));
        OSS client = clients.create(source);
        long count = 0;
        long bytes = 0;
        try {
            String continuation = null;
            do {
                var page = client.listObjectsV2(new ListObjectsV2Request(sourceUri.getHost())
                        .withPrefix(sourceUri.getPath().replaceFirst("^/", "")).withMaxKeys(1000).withContinuationToken(continuation));
                for (var object : page.getObjectSummaries()) {
                    if (!object.getKey().endsWith(".tar.gz") || object.getSize() <= 0
                            || object.getSize() > limits.maxPackageBytes()) continue;
                    count++;
                    bytes += object.getSize();
                }
                continuation = page.getNextContinuationToken();
            } while (continuation != null && !continuation.isBlank());
        } finally {
            client.shutdown();
        }
        assertTrue(count > 0 && bytes > 0, "Real OSS source inventory is empty");
        System.out.println("REAL SOURCE CANDIDATES count=" + count + " bytes=" + bytes
                + " maxPackageBytes=" + limits.maxPackageBytes());
        OssNodeConfig target = config(deliveryPipeline, "oss.upload", false);
        OSS targetClient = clients.create(target);
        try {
            assertTrue(targetClient.doesBucketExist(target.bucket()), "Target OSS bucket is unavailable");
        } finally {
            targetClient.shutdown();
        }
        var ftp = configs.ftp(deliveryPipeline.nodes().stream().filter(node -> node.type().equals("ftp.download-verify")).findFirst().orElseThrow());
        List<Map<String, Object>> entities = jdbc.queryForList("SELECT * FROM delivery_entity WHERE enabled=true ORDER BY delivery_entity_id")
                .stream().filter(entity -> ftp.ftpCredential().containsKey(entity.get("account_name").toString())).toList();
        assertEquals(expectedEntities(), entities.size(), "Pipeline-configured FTP entity count differs from verification profile");
        for (Map<String, Object> entity : entities) ftpPreflight(entity, ftp);
        System.out.println("REAL PREFLIGHT PASSED: batch and delivery Pipelines, source/target OSS and FTP are available");
    }

    @Test void sourceInventory() throws Exception {
        assumeTrue("true".equals(System.getenv("ARCHIVE_INVENTORY")), "Explicit real source inventory only");
        JdbcTemplate jdbc = jdbc();
        PipelineDefinition pipeline = publishedPipeline(jdbc, "archive-batch");
        BatchPlanningService.Limits limits = BatchPlanningService.limits(pipeline);
        OssNodeConfig source = config(pipeline, "oss.download", true);
        URI sourceUri = URI.create(required("ARCHIVE_VERIFY_SOURCE_URI"));
        OSS client = clients.create(source);
        long count = 0;
        long bytes = 0;
        try (var output = new java.io.PrintWriter(Files.newBufferedWriter(Path.of(required("ARCHIVE_VERIFY_INVENTORY_PATH"))))) {
            String continuation = null;
            do {
                var page = client.listObjectsV2(new ListObjectsV2Request(sourceUri.getHost())
                        .withPrefix(sourceUri.getPath().replaceFirst("^/", "")).withMaxKeys(1000).withContinuationToken(continuation));
                for (var object : page.getObjectSummaries()) {
                    if (!object.getKey().endsWith(".tar.gz") || object.getSize() <= 0) continue;
                    output.println(object.getSize() + "\t" + object.getKey());
                    count++;
                    if (object.getSize() <= limits.maxPackageBytes()) bytes += object.getSize();
                }
                continuation = page.getNextContinuationToken();
            } while (continuation != null && !continuation.isBlank());
        } finally {
            client.shutdown();
        }
        System.out.println("REAL SOURCE INVENTORY count=" + count + " fitBytes=" + bytes);
    }

    @Test void verifyRealDelivery() throws Exception {
        assumeTrue("true".equals(System.getenv("ARCHIVE_REAL_E2E")), "Explicit real OSS/FTP verification only");
        long deliveryId = Long.parseLong(required("ARCHIVE_VERIFY_DELIVERY_ID"));
        JdbcTemplate jdbc = jdbc();
        assertEquals("DELIVERED", jdbc.queryForObject("SELECT status FROM delivery_submit WHERE delivery_id=?", String.class, deliveryId));
        Map<String, Object> submit = jdbc.queryForMap("SELECT * FROM delivery_submit WHERE delivery_id=?", deliveryId);
        String deliveryBatch = submit.get("delivery_batch").toString();
        JsonNode extension = mapper.readTree(submit.get("batch_extension").toString());
        long maxBytes = extension.required("maxPackageBytes").asLong();
        PipelineDefinition deliveryPipeline = pipelineForDelivery(jdbc, deliveryId);
        PipelineDefinition batchPipeline = publishedPipeline(jdbc, "archive-batch");
        OssNodeConfig sourceConfig = config(batchPipeline, "oss.download", true);
        OssNodeConfig targetConfig = config(deliveryPipeline, "oss.upload", false);
        var ftpConfig = configs.ftp(deliveryPipeline.nodes().stream().filter(node -> node.type().equals("ftp.download-verify")).findFirst().orElseThrow());
        List<Map<String, Object>> tasks = jdbc.queryForList("SELECT t.task_id,t.delivery_entity_id,t.entity_code,t.delivery_date,t.new_file_name," +
                "t.size_bytes,t.artifacts::text AS artifacts,e.account_name,e.endpoint_locator::text AS endpoint_locator " +
                "FROM delivery_task t JOIN delivery_entity e ON e.delivery_entity_id=t.delivery_entity_id " +
                "WHERE t.delivery_id=? AND t.execution_status NOT IN('EXPIRED','CANCELLED') ORDER BY t.new_file_name", deliveryId);
        assertEquals(expectedTasks(), tasks.size(), "Delivery task count differs from targetCount");
        assertEquals(tasks.size(), tasks.stream().map(task -> task.get("new_file_name").toString()).distinct().count());
        var totalSources = new java.util.concurrent.atomic.AtomicInteger();
        var assignedUris = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        tasks.parallelStream().forEach(task -> {
            try {
                long taskId = ((Number) task.get("task_id")).longValue();
                String newFileName = task.get("new_file_name").toString();
                assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT execution_status FROM delivery_task WHERE task_id=?", String.class, taskId));
                long sourceTaskId = jdbc.queryForObject("SELECT COALESCE((SELECT (payload->>'oldTaskId')::bigint FROM delivery_event " +
                        "WHERE delivery_id=? AND event_type='TASK_REGENERATED' AND (payload->>'newTaskId')::bigint=?),?)",
                        Long.class, deliveryId, taskId, taskId);
                JsonNode artifacts = mapper.readTree(task.get("artifacts").toString());
                int sourceCount = jdbc.queryForObject("SELECT count(*) FROM delivery_batch WHERE delivery_id=? AND new_file_name=?",
                        Integer.class, deliveryId, newFileName);
                assertEquals(sourceCount, artifacts.size());
                totalSources.addAndGet(sourceCount);
                for (JsonNode pair : artifacts) {
                    assertEquals(2, pair.size());
                    long dataId = pair.required("dataId").asLong();
                    Map<String, Object> sourceFact = jdbc.queryForMap("SELECT original_file_name,size_bytes,sha256,request_namespace," +
                            "unique_key,artifact::text AS artifact,created_at,updated_at FROM source_data WHERE data_id=?", dataId);
                    assertEquals(deliveryBatch, sourceFact.get("request_namespace"));
                    assertEquals(pair.required("sourceArtifact"), mapper.readTree(sourceFact.get("artifact").toString()));
                    assertEquals(sourceFact.get("sha256"), sourceFact.get("unique_key"));
                }
                JsonNode repairOutput = output(jdbc, sourceTaskId, "python.repair");
                long expectedSize = repairOutput.required("sizeBytes").asLong();
                String expectedSha = repairOutput.required("sha256").asText();
                assertTrue(expectedSize > 0 && expectedSize <= maxBytes);
                assertEquals(expectedSize, ((Number) task.get("size_bytes")).longValue());
                assertEquals(sourceCount, repairOutput.required("sourceCount").asInt());
                JsonNode sourceOutput = output(jdbc, sourceTaskId, "oss.download");
                assertEquals(sourceCount, sourceOutput.required("sources").size());
                long sourceBytes = 0;
                for (JsonNode source : sourceOutput.required("sources")) {
                    sourceBytes += source.required("sizeBytes").asLong();
                    URI sourceUri = URI.create(source.required("uri").asText());
                    assertTrue(assignedUris.add(sourceUri.toString()), "Source OSS URI delivered twice: " + sourceUri);
                    Path sourceFile = temp.resolve("source-" + taskId + "-" + source.required("dataId").asLong() + ".tar.gz");
                    transfers.download(sourceConfig, sourceUri.getHost(), sourceUri.getPath().replaceFirst("^/", ""), sourceFile,
                            source.required("sizeBytes").asLong(), source.required("sha256").asText());
                    Files.deleteIfExists(sourceFile);
                }
                assertTrue(sourceBytes > 0 && sourceBytes <= maxBytes);
                JsonNode mergeOutput = output(jdbc, sourceTaskId, "package.merge");
                assertEquals(mergeOutput.required("requestCount").asInt(), repairOutput.required("requestCount").asInt());
                assertTrue(Files.isRegularFile(Path.of(mergeOutput.required("manifestPath").asText())), "Merge manifest is required");
                assertEquals(task.get("entity_code").toString(), repairOutput.required("entityCode").asText());

                JsonNode targetOutput = output(jdbc, taskId, "oss.upload");
                URI targetUri = URI.create(targetOutput.required("uri").asText());
                assertEquals(expectedSha, targetOutput.required("sha256").asText());
                Path targetFile = temp.resolve("target-" + taskId + ".tar.gz");
                transfers.download(targetConfig, targetUri.getHost(), targetUri.getPath().replaceFirst("^/", ""), targetFile, expectedSize, expectedSha);
                JsonNode ftpOutput = output(jdbc, taskId, "ftp.download-verify");
                assertEquals(expectedSha, ftpOutput.required("sha256").asText());
                assertEquals(expectedSize, ftpOutput.required("sizeBytes").asLong());
                JsonNode endpoint = mapper.readTree(task.get("endpoint_locator").toString());
                String remotePath = URI.create(ftpOutput.required("uri").asText()).getPath();
                String rootPath = endpoint.path("rootPath").asText("/");
                if (!"/".equals(rootPath) && remotePath.startsWith(rootPath)) remotePath = remotePath.substring(rootPath.length());
                remotePath = remotePath.replaceFirst("^/", "");
                Path independentDownload = temp.resolve("ftp-" + taskId).resolve(newFileName);
                ftpTransfer(endpoint, task.get("account_name").toString(), ftpConfig.passwordFor(task.get("account_name").toString()),
                        remotePath, targetFile, independentDownload, expectedSize, expectedSha);
                assertFalse(Files.exists(independentDownload.resolveSibling(newFileName + ".partial")));
                Files.deleteIfExists(targetFile);
                Files.deleteIfExists(independentDownload);
                System.out.println("REAL VERIFIED deliveryId=" + deliveryId + " taskId=" + taskId + " sources=" + sourceCount
                        + " size=" + expectedSize + " sha256=" + expectedSha);
            } catch (Exception error) {
                throw new IllegalStateException("Task verification failed: " + task.get("task_id"), error);
            }
        });
        assertEquals(totalSources.get(), assignedUris.size(), "Every original OSS URI must be assigned exactly once");
        System.out.println("REAL E2E PASSED deliveryId=" + deliveryId + " tasks=" + tasks.size() + " sources=" + totalSources
                + " maxPackageBytes=" + maxBytes);
    }

    private static int expectedTasks() {
        return Integer.parseInt(System.getenv().getOrDefault("ARCHIVE_VERIFY_EXPECTED_TASKS", "1"));
    }

    private static int expectedEntities() {
        return Integer.parseInt(System.getenv().getOrDefault("ARCHIVE_VERIFY_EXPECTED_ENTITIES", "1"));
    }

    private JdbcTemplate jdbc() {
        // 并行外部复核共享有界连接池，避免逐条查询反复建连压垮 PostgreSQL。
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(required("ARCHIVE_DB_URL"));
        dataSource.setUsername(required("ARCHIVE_DB_USER"));
        dataSource.setPassword(required("ARCHIVE_DB_PASSWORD"));
        dataSource.setMaximumPoolSize(4);
        dataSource.setConnectionTimeout(Duration.ofSeconds(30).toMillis());
        return new JdbcTemplate(dataSource);
    }

    private PipelineDefinition publishedPipeline(JdbcTemplate jdbc, String code) throws Exception {
        String definition = jdbc.queryForObject("SELECT pv.definition::text FROM pipeline_version pv JOIN pipeline p ON p.pipeline_id=pv.pipeline_id " +
                "WHERE p.pipeline_code=? AND pv.status='PUBLISHED' ORDER BY pv.version_no DESC LIMIT 1", String.class, code);
        return new PipelineRepository(mapper).read(mapper.readTree(definition));
    }

    private PipelineDefinition pipelineForDelivery(JdbcTemplate jdbc, long deliveryId) throws Exception {
        String definition = jdbc.queryForObject("SELECT pv.definition::text FROM delivery_submit s JOIN pipeline_version pv " +
                "ON pv.pipeline_version_id=s.pipeline_version_id WHERE s.delivery_id=?", String.class, deliveryId);
        return new PipelineRepository(mapper).read(mapper.readTree(definition));
    }

    private OssNodeConfig config(PipelineDefinition pipeline, String type, boolean source) {
        return configs.oss(pipeline.nodes().stream().filter(node -> node.type().equals(type)).findFirst().orElseThrow(), source);
    }

    private JsonNode output(JdbcTemplate jdbc, long taskId, String type) throws Exception {
        String json = jdbc.queryForObject("SELECT output_locator::text FROM delivery_node WHERE task_id=? AND node_type=? " +
                "AND status='SUCCEEDED' ORDER BY attempt DESC LIMIT 1", String.class, taskId, type);
        assertNotNull(json, "Missing succeeded " + type + " delivery node for task " + taskId);
        return mapper.readTree(json);
    }

    private void ftpPreflight(Map<String, Object> entity, com.powerjob.archive.core.pipeline.FtpNodeConfig config) throws Exception {
        JsonNode endpoint = mapper.readTree(entity.get("endpoint_locator").toString());
        var request = mapper.createObjectNode().put("host", endpoint.required("host").asText()).put("port", endpoint.path("port").asInt(21))
                .put("username", entity.get("account_name").toString()).put("password", config.passwordFor(entity.get("account_name").toString()))
                .put("rootPath", endpoint.path("rootPath").asText("/"));
        runPython(request, true);
    }

    private void ftpTransfer(JsonNode endpoint, String account, String password, String remotePath,
                             Path source, Path download, long size, String sha) throws Exception {
        var request = mapper.createObjectNode().put("host", endpoint.required("host").asText()).put("port", endpoint.path("port").asInt(21))
                .put("username", account).put("password", password).put("rootPath", endpoint.path("rootPath").asText("/"))
                .put("remotePath", remotePath).put("sourcePath", source.toString()).put("downloadPath", download.toString())
                .put("expectedSize", size).put("expectedSha256", sha)
                // 独立验收只读已有 FTP 文件，不执行日期保留清理。
                .put("cleanupOtherDeliveryDates", false).put("deliveryDate", "")
                .put("retentionRoot", temp.resolve("retention").toString());
        runPython(request, false);
    }

    private void runPython(JsonNode request, boolean preflight) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of("python3", "/workspace/nodes/python/ftp_download_verify.py"));
        if (preflight) command.add("--preflight");
        else command.add("--verify-only");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        assertTrue(process.waitFor(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS), "FTP verification timed out");
        String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String password = request.required("password").asText();
        assertFalse(result.contains(password));
        assertEquals(0, process.exitValue(), "FTP verification failed: " + result.replace(password, "***"));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertNotNull(value, name + " is required");
        return value;
    }
}
