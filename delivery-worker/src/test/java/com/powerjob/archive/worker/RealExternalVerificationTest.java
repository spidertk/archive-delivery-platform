package com.powerjob.archive.worker;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.pipeline.OssNodeConfig;
import com.powerjob.archive.core.pipeline.PipelineDefinition;
import com.powerjob.archive.core.pipeline.PipelineNodeConfig;
import com.powerjob.archive.core.pipeline.PipelineRepository;
import com.powerjob.archive.core.service.FileTransferService;
import com.powerjob.archive.core.service.OssClientFactory;
import com.powerjob.archive.core.service.OssTransferService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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

/** 只有显式真实验收入口启用；所有断言都读取真实 PG、OSS 与 FTP，不以 skipped 或 API 状态代替证据。 */
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
        PipelineDefinition pipeline = currentPipeline(jdbc, null);
        OssNodeConfig source = config(pipeline, "oss.download", true);
        OSS client = clients.create(source);
        try {
            long count = client.listObjectsV2(new ListObjectsV2Request(source.bucket()).withPrefix(source.prefix()).withMaxKeys(100))
                    .getObjectSummaries().stream().filter(object -> object.getKey().endsWith(".tar.gz") && object.getSize() > 0).count();
            assertTrue(count >= 3, "At least three real source archives are required");
        } finally {
            client.shutdown();
        }
        OssNodeConfig target = config(pipeline, "oss.upload", false);
        OSS targetClient = clients.create(target);
        try {
            assertTrue(targetClient.doesBucketExist(target.bucket()), "Target OSS bucket is unavailable");
        } finally {
            targetClient.shutdown();
        }
        List<Map<String, Object>> entities = jdbc.queryForList("SELECT * FROM delivery_entity WHERE enabled=true ORDER BY delivery_entity_id LIMIT 3");
        assertEquals(3, entities.size());
        var ftp = configs.ftp(pipeline.nodes().stream().filter(node -> node.type().equals("ftp.download-verify")).findFirst().orElseThrow());
        for (Map<String, Object> entity : entities) ftpPreflight(entity, ftp);
        System.out.println("REAL PREFLIGHT PASSED: source OSS >=3, target OSS bucket, three FTP accounts/directories");
    }

    @Test void verifyRealDelivery() throws Exception {
        assumeTrue("true".equals(System.getenv("ARCHIVE_REAL_E2E")), "Explicit real OSS/FTP verification only");
        long deliveryId = Long.parseLong(System.getenv("ARCHIVE_VERIFY_DELIVERY_ID"));
        JdbcTemplate jdbc = jdbc();
        assertEquals("DELIVERED", jdbc.queryForObject("SELECT status FROM delivery_submit WHERE delivery_id=?", String.class, deliveryId));
        PipelineDefinition pipeline = currentPipeline(jdbc, deliveryId);
        OssNodeConfig sourceConfig = config(pipeline, "oss.download", true);
        OssNodeConfig targetConfig = config(pipeline, "oss.upload", false);
        var ftpConfig = configs.ftp(pipeline.nodes().stream().filter(node -> node.type().equals("ftp.download-verify")).findFirst().orElseThrow());
        List<Map<String, Object>> tasks = jdbc.queryForList("SELECT t.task_id,t.data_id,t.delivery_entity_id,t.delivery_date,t.new_file_name," +
                "t.size_bytes,t.sha256,e.account_name,e.endpoint_locator::text AS endpoint_locator FROM delivery_task t " +
                "JOIN delivery_entity e ON e.delivery_entity_id=t.delivery_entity_id " +
                "WHERE t.delivery_id=? AND t.execution_status NOT IN('EXPIRED','CANCELLED') ORDER BY t.delivery_entity_id,t.target_seq", deliveryId);
        assertEquals(3, tasks.size());
        assertEquals(3, tasks.stream().filter(row -> "SUCCEEDED".equals(jdbc.queryForObject(
                "SELECT execution_status FROM delivery_task WHERE task_id=?", String.class, row.get("task_id")))).count());
        for (Map<String, Object> task : tasks) {
            long taskId = ((Number) task.get("task_id")).longValue();
            long entityId = ((Number) task.get("delivery_entity_id")).longValue();
            String expectedSha = task.get("sha256").toString();
            long expectedSize = ((Number) task.get("size_bytes")).longValue();
            long sourceTaskId = jdbc.queryForObject("SELECT COALESCE((SELECT old.task_id FROM delivery_task old " +
                    "WHERE old.superseded_by_task_id=?),?)", Long.class, taskId, taskId);
            JsonNode sourceOutput = output(jdbc, sourceTaskId, "oss.download");
            URI sourceUri = URI.create(sourceOutput.required("uri").asText());
            Path sourceFile = temp.resolve("source-" + taskId + ".tar.gz");
            transfers.download(sourceConfig, sourceUri.getHost(), sourceUri.getPath().replaceFirst("^/", ""), sourceFile,
                    sourceOutput.required("sizeBytes").asLong(), sourceOutput.required("sha256").asText());

            JsonNode targetOutput = output(jdbc, taskId, "oss.upload");
            URI targetUri = URI.create(targetOutput.required("uri").asText());
            assertEquals(expectedSha, targetOutput.required("sha256").asText());
            assertEquals(expectedSize, targetOutput.required("sizeBytes").asLong());
            Path targetFile = temp.resolve("target-" + taskId + ".tar.gz");
            transfers.download(targetConfig, targetUri.getHost(), targetUri.getPath().replaceFirst("^/", ""), targetFile, expectedSize, expectedSha);
            assertEquals(expectedSha, jdbc.queryForObject("SELECT artifacts->0->>'sha256' FROM source_data WHERE data_id=?",
                    String.class, task.get("data_id")));

            JsonNode ftpOutput = output(jdbc, taskId, "ftp.download-verify");
            assertEquals(expectedSha, ftpOutput.required("sha256").asText());
            assertEquals(expectedSize, ftpOutput.required("sizeBytes").asLong());
            Path localDownload = Path.of(ftpOutput.required("downloadPath").asText());
            assertTrue(Files.isRegularFile(localDownload), "Worker FTP download is missing for task " + taskId);
            assertEquals(expectedSize, Files.size(localDownload));
            assertEquals(expectedSha, files.sha256(localDownload));
            assertFalse(Files.exists(localDownload.resolveSibling(localDownload.getFileName() + ".partial")));

            JsonNode endpoint = mapper.readTree(task.get("endpoint_locator").toString());
            String remotePath = deliveryId + "/" + task.get("delivery_date") + "/entity-" + entityId + "/" + task.get("new_file_name");
            Path independentDownload = temp.resolve("ftp-" + taskId).resolve(task.get("new_file_name").toString());
            ftpTransfer(endpoint, task.get("account_name").toString(), ftpConfig.passwordFor(task.get("account_name").toString()),
                    remotePath, targetFile, independentDownload, expectedSize, expectedSha);
            assertEquals(expectedSha, files.sha256(independentDownload));
            assertFalse(Files.exists(independentDownload.resolveSibling(independentDownload.getFileName() + ".partial")));
            System.out.println("REAL VERIFIED taskId=" + taskId + " deliveryEntityId=" + entityId
                    + " size=" + expectedSize + " sha256=" + expectedSha);
        }
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM delivery_event WHERE delivery_id=? AND event_type='TASK_RETRIED'", Integer.class, deliveryId) >= 1);
        List<Map<String, Object>> rollovers = jdbc.queryForList("SELECT old.task_id AS old_task,new.task_id AS new_task," +
                "old.delivery_date AS old_date,new.delivery_date AS new_date FROM delivery_task old JOIN delivery_task new " +
                "ON new.task_id=old.superseded_by_task_id WHERE old.delivery_id=? AND old.execution_status='EXPIRED'", deliveryId);
        assertFalse(rollovers.isEmpty(), "At least one real rollover is required");
        for (Map<String, Object> rollover : rollovers) {
            assertEquals(((java.sql.Date) rollover.get("old_date")).toLocalDate().plusDays(1),
                    ((java.sql.Date) rollover.get("new_date")).toLocalDate());
            long oldTask = ((Number) rollover.get("old_task")).longValue();
            long newTask = ((Number) rollover.get("new_task")).longValue();
            assertNotEquals(oldTask, newTask);
            Integer uploadedBeforeExpiry = jdbc.queryForObject("SELECT count(*) FROM delivery_node WHERE task_id=? AND node_type='oss.upload' " +
                    "AND status='SUCCEEDED'", Integer.class, oldTask);
            if (uploadedBeforeExpiry != null && uploadedBeforeExpiry > 0) {
                assertNotEquals(output(jdbc, oldTask, "oss.upload").required("uri").asText(),
                        output(jdbc, newTask, "oss.upload").required("uri").asText());
            }
        }
        System.out.println("REAL E2E PASSED deliveryId=" + deliveryId + " tasks=3 retry>=1 rollover>=1 OSS/FTP SHA verified");
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(required("ARCHIVE_DB_URL"), required("ARCHIVE_DB_USER"), required("ARCHIVE_DB_PASSWORD")));
    }

    private PipelineDefinition currentPipeline(JdbcTemplate jdbc, Long deliveryId) throws Exception {
        String definition = deliveryId == null
                ? jdbc.queryForObject("SELECT definition::text FROM pipeline_version WHERE status='PUBLISHED' ORDER BY pipeline_version_id DESC LIMIT 1", String.class)
                : jdbc.queryForObject("SELECT pv.definition::text FROM delivery_submit s JOIN pipeline_version pv " +
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
                .put("expectedSize", size).put("expectedSha256", sha);
        runPython(request, false);
    }

    private void runPython(JsonNode request, boolean preflight) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of("python3", "/workspace/nodes/python/ftp_download_verify.py"));
        if (preflight) command.add("--preflight");
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
