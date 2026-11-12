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

/** 真实核心验收读取 PG、OSS 与 FTP；持续下载到每包来源合计和最终产物均处于配置大小边界。 */
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
            var selected = client.listObjectsV2(new ListObjectsV2Request(source.bucket()).withPrefix(source.prefix()).withMaxKeys(100))
                    .getObjectSummaries().stream().filter(object -> object.getKey().endsWith(".tar.gz") && object.getSize() > 0
                            && object.getSize() <= pipeline.maxPackageBytes())
                    .sorted(java.util.Comparator.comparing(com.aliyun.oss.model.OSSObjectSummary::getKey)).toList();
            long candidateBytes = selected.stream().mapToLong(com.aliyun.oss.model.OSSObjectSummary::getSize).sum();
            int requiredPackages = expectedTasks();
            assertTrue(selected.size() >= Math.min(2, requiredPackages) && candidateBytes >= requiredPackages * pipeline.minPackageBytes(),
                    "Real source archives must fill the requested packages within the configured bounds: count=" + selected.size()
                            + " bytes=" + candidateBytes + " requiredPackages=" + requiredPackages);
            assertTrue(selected.stream().allMatch(object -> object.getSize() <= pipeline.maxPackageBytes()),
                    "Every selected source must fit maxPackageBytes before a delivery is submitted: " + selected.stream()
                            .map(object -> object.getKey() + "=" + object.getSize()).toList());
            System.out.println("REAL SOURCE CANDIDATES count=" + selected.size() + " bytes=" + candidateBytes
                    + " minPackageBytes=" + pipeline.minPackageBytes() + " maxPackageBytes=" + pipeline.maxPackageBytes());
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
        var ftp = configs.ftp(pipeline.nodes().stream().filter(node -> node.type().equals("ftp.download-verify")).findFirst().orElseThrow());
        List<Map<String, Object>> entities = jdbc.queryForList("SELECT * FROM delivery_entity WHERE enabled=true ORDER BY delivery_entity_id")
                .stream().filter(entity -> ftp.ftpCredential().containsKey(entity.get("account_name").toString())).toList();
        int requiredEntities = production() ? 1 : 3;
        assertEquals(requiredEntities, entities.size(), "Pipeline-configured FTP entity count differs from the active verification profile");
        for (Map<String, Object> entity : entities) ftpPreflight(entity, ftp);
        System.out.println("REAL PREFLIGHT PASSED: source OSS archives meet configured package bounds, target OSS bucket, entities="
                + requiredEntities);
    }

    @Test void verifyRealDelivery() throws Exception {
        assumeTrue("true".equals(System.getenv("ARCHIVE_REAL_E2E")), "Explicit real OSS/FTP verification only");
        long deliveryId = Long.parseLong(System.getenv("ARCHIVE_VERIFY_DELIVERY_ID"));
        JdbcTemplate jdbc = jdbc();
        assertEquals("DELIVERED", jdbc.queryForObject("SELECT status FROM delivery_submit WHERE delivery_id=?", String.class, deliveryId));
        String deliveryBatch = jdbc.queryForObject("SELECT delivery_batch FROM delivery_submit WHERE delivery_id=?", String.class, deliveryId);
        PipelineDefinition pipeline = currentPipeline(jdbc, deliveryId);
        OssNodeConfig sourceConfig = config(pipeline, "oss.download", true);
        OssNodeConfig targetConfig = config(pipeline, "oss.upload", false);
        var ftpConfig = configs.ftp(pipeline.nodes().stream().filter(node -> node.type().equals("ftp.download-verify")).findFirst().orElseThrow());
        List<Map<String, Object>> tasks = jdbc.queryForList("SELECT t.task_id,t.delivery_entity_id,t.entity_code,t.delivery_date,t.new_file_name," +
                "t.size_bytes,t.artifacts::text AS artifacts,t.metadata_ids::text AS metadata_ids,e.account_name,e.endpoint_locator::text AS endpoint_locator " +
                "FROM delivery_task t JOIN delivery_entity e ON e.delivery_entity_id=t.delivery_entity_id " +
                "WHERE t.delivery_id=? AND t.execution_status NOT IN('EXPIRED','CANCELLED') ORDER BY t.new_file_name", deliveryId);
        assertEquals(expectedTasks(), tasks.size(), "Delivery task count differs from the requested targetCount total");
        assertEquals(tasks.size(), tasks.stream().map(task -> task.get("new_file_name").toString()).distinct().count(),
                "Package names must be globally unique within the delivery");
        int totalSources = 0;
        java.util.Set<Long> assignedDataIds = new java.util.HashSet<>();
        for (Map<String, Object> task : tasks) {
            long taskId = ((Number) task.get("task_id")).longValue();
            long entityId = ((Number) task.get("delivery_entity_id")).longValue();
            assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT execution_status FROM delivery_task WHERE task_id=?", String.class, taskId));
            JsonNode artifactPairs = mapper.readTree(task.get("artifacts").toString());
            long sourceTaskId = jdbc.queryForObject("SELECT COALESCE((SELECT (payload->>'oldTaskId')::bigint FROM delivery_event " +
                    "WHERE delivery_id=? AND event_type='TASK_REGENERATED' AND (payload->>'newTaskId')::bigint=?),?)",
                    Long.class, deliveryId, taskId, taskId);
            JsonNode repairOutput = output(jdbc, sourceTaskId, "python.repair");
            String expectedSha = repairOutput.required("sha256").asText();
            long expectedSize = repairOutput.required("sizeBytes").asLong();
            assertTrue(expectedSize >= pipeline.minPackageBytes() && expectedSize <= pipeline.maxPackageBytes(),
                    "Merged artifact must reach minPackageBytes without exceeding maxPackageBytes: " + expectedSize);
            assertEquals(expectedSize, ((Number) task.get("size_bytes")).longValue());
            int sourceCount = jdbc.queryForObject("SELECT count(*) FROM delivery_mapping WHERE task_id=?", Integer.class, sourceTaskId);
            assertEquals(sourceCount, artifactPairs.size());
            assertEquals(sourceCount, jdbc.queryForObject("SELECT count(*) FROM delivery_mapping WHERE task_id=?", Integer.class, taskId));
            totalSources += sourceCount;
            JsonNode metadataIds = mapper.readTree(task.get("metadata_ids").toString());
            assertEquals(sourceCount, metadataIds.size(), "metadataIds size must equal the source count");
            for (int pairIndex = 0; pairIndex < artifactPairs.size(); pairIndex++) {
                JsonNode pair = artifactPairs.required(pairIndex);
                assertEquals(2, pair.size());
                assertFalse(pair.has("deliveryArtifact"));
                long dataId = pair.required("dataId").asLong();
                assertTrue(metadataIds.required(pairIndex).isIntegralNumber());
                assertEquals(dataId, metadataIds.required(pairIndex).asLong());
                assertTrue(assignedDataIds.add(dataId), "A source dataId must be assigned only once: " + dataId);
                Map<String, Object> sourceFact = jdbc.queryForMap("SELECT original_file_name,size_bytes,sha256,request_namespace," +
                        "artifact::text AS artifact,created_at,updated_at FROM source_data WHERE data_id=?", dataId);
                assertEquals(deliveryBatch, sourceFact.get("request_namespace"));
                assertEquals(pair.required("sourceArtifact"), mapper.readTree(sourceFact.get("artifact").toString()),
                        "Task sourceArtifact must exactly equal source_data.artifact");
                assertEquals(pair.required("sourceArtifact").required("originalFileName").asText(), sourceFact.get("original_file_name"));
                assertEquals(pair.required("sourceArtifact").required("sizeBytes").asLong(), ((Number) sourceFact.get("size_bytes")).longValue());
                assertEquals(pair.required("sourceArtifact").required("sha256").asText(), sourceFact.get("sha256"));
                assertNotNull(sourceFact.get("created_at"));
                assertNotNull(sourceFact.get("updated_at"));
            }

            JsonNode sourceOutput = output(jdbc, sourceTaskId, "oss.download");
            assertEquals(sourceCount, sourceOutput.required("sources").size());
            long sourceBytes = 0;
            for (JsonNode source : sourceOutput.required("sources")) {
                sourceBytes += source.required("sizeBytes").asLong();
                URI sourceUri = URI.create(source.required("uri").asText());
                Path sourceFile = temp.resolve("source-" + taskId + "-" + source.required("dataId").asLong() + ".tar.gz");
                transfers.download(sourceConfig, sourceUri.getHost(), sourceUri.getPath().replaceFirst("^/", ""), sourceFile,
                        source.required("sizeBytes").asLong(), source.required("sha256").asText());
                assertEquals(source.required("sha256").asText(), jdbc.queryForObject("SELECT sha256 FROM source_data WHERE data_id=?",
                        String.class, source.required("dataId").asLong()));
            }
            assertTrue(sourceBytes >= pipeline.minPackageBytes() && sourceBytes <= pipeline.maxPackageBytes(),
                    "Assigned source bytes must meet the configured package bounds: " + sourceBytes);
            JsonNode mergeOutput = output(jdbc, sourceTaskId, "package.merge");
            Path mergeWorkspace = Path.of(mergeOutput.required("workspacePath").asText());
            assertTrue(Files.isDirectory(mergeWorkspace), "Merge must retain a directory rather than an intermediate tar");
            assertFalse(Files.exists(mergeWorkspace.resolveSibling("merged-source.tar.gz")));
            assertEquals(task.get("entity_code").toString(), repairOutput.required("entityCode").asText());
            assertEquals(expectedSize, repairOutput.required("sizeBytes").asLong());
            assertEquals(sourceCount, repairOutput.required("metadataCount").asInt());
            assertEquals(metadataIds, repairOutput.required("metadataIds"));
            assertEquals(mergeOutput.required("requestCount").asInt(), repairOutput.required("requestCount").asInt());
            JsonNode mergeManifest = mapper.readTree(Files.readString(Path.of(mergeOutput.required("manifestPath").asText())));
            JsonNode repairManifest = mapper.readTree(Files.readString(Path.of(repairOutput.required("localPath").asText())
                    .resolveSibling("repair-result.json")));
            java.util.Map<String, Integer> memberSources = new java.util.HashMap<>();
            mergeManifest.required("members").forEach(member ->
                    memberSources.put(member.required("memberKey").asText(), member.required("sourceSeq").asInt()));
            int[] sourceRequestCounts = new int[sourceCount];
            java.util.Set<String> requestIds = new java.util.HashSet<>();
            for (JsonNode mapping : repairManifest.required("mappings")) {
                assertFalse(mapping.required("originalPath").asText().startsWith("source-"),
                        "Repair manifest must retain originalPath instead of synthetic member keys");
                int sourceSeq = memberSources.get(mapping.required("memberKey").asText());
                sourceRequestCounts[sourceSeq - 1]++;
                assertTrue(requestIds.add(mapping.required("newRequestId").asText()));
            }
            int mappedRequests = repairManifest.required("requestCount").asInt();
            assertEquals(mappedRequests, requestIds.size());
            for (int count : sourceRequestCounts) assertTrue(count > 0);

            JsonNode targetOutput = output(jdbc, taskId, "oss.upload");
            URI targetUri = URI.create(targetOutput.required("uri").asText());
            assertEquals(expectedSha, targetOutput.required("sha256").asText());
            assertEquals(expectedSize, targetOutput.required("sizeBytes").asLong());
            Path targetFile = temp.resolve("target-" + taskId + ".tar.gz");
            transfers.download(targetConfig, targetUri.getHost(), targetUri.getPath().replaceFirst("^/", ""), targetFile, expectedSize, expectedSha);

            JsonNode ftpOutput = output(jdbc, taskId, "ftp.download-verify");
            assertEquals(expectedSha, ftpOutput.required("sha256").asText());
            assertEquals(expectedSize, ftpOutput.required("sizeBytes").asLong());
            Path localDownload = Path.of(ftpOutput.required("downloadPath").asText());
            assertTrue(Files.isRegularFile(localDownload), "Worker FTP download is missing for task " + taskId);
            assertEquals(expectedSize, Files.size(localDownload));
            assertEquals(expectedSha, files.sha256(localDownload));
            assertFalse(Files.exists(localDownload.resolveSibling(localDownload.getFileName() + ".partial")));

            JsonNode endpoint = mapper.readTree(task.get("endpoint_locator").toString());
            String remotePath = URI.create(ftpOutput.required("uri").asText()).getPath();
            String rootPath = endpoint.path("rootPath").asText("/");
            if (!"/".equals(rootPath) && remotePath.startsWith(rootPath)) remotePath = remotePath.substring(rootPath.length());
            remotePath = remotePath.replaceFirst("^/", "");
            Path independentDownload = temp.resolve("ftp-" + taskId).resolve(task.get("new_file_name").toString());
            ftpTransfer(endpoint, task.get("account_name").toString(), ftpConfig.passwordFor(task.get("account_name").toString()),
                    remotePath, targetFile, independentDownload, expectedSize, expectedSha);
            assertEquals(expectedSha, files.sha256(independentDownload));
            assertFalse(Files.exists(independentDownload.resolveSibling(independentDownload.getFileName() + ".partial")));

            if (sourceTaskId != taskId) {
                assertEquals("EXPIRED", jdbc.queryForObject("SELECT execution_status FROM delivery_task WHERE task_id=?", String.class, sourceTaskId));
                java.sql.Date oldDate = jdbc.queryForObject("SELECT delivery_date FROM delivery_task WHERE task_id=?", java.sql.Date.class, sourceTaskId);
                java.sql.Date newDate = jdbc.queryForObject("SELECT delivery_date FROM delivery_task WHERE task_id=?", java.sql.Date.class, taskId);
                assertEquals(oldDate.toLocalDate().plusDays(1), newDate.toLocalDate());
            }
            System.out.println("REAL VERIFIED deliveryId=" + deliveryId + " taskId=" + taskId + " deliveryEntityId=" + entityId
                    + " sources=" + sourceCount + " metadata=" + metadataIds.size() + " requests=" + mappedRequests
                    + " size=" + expectedSize + " sha256=" + expectedSha);
        }
        assertTrue(production() ? totalSources >= expectedTasks() : totalSources > 3,
                "The real case must download enough sources for every requested package");
        assertEquals(totalSources, assignedDataIds.size(), "Every real OSS source must be assigned exactly once");
        if (!production()) {
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM delivery_event WHERE delivery_id=? AND event_type='TASK_RETRIED'", Integer.class, deliveryId) >= 1);
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM delivery_event WHERE delivery_id=? AND event_type='DELIVERY_PAUSED'", Integer.class, deliveryId) >= 1);
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM delivery_event WHERE delivery_id=? AND event_type='DELIVERY_RESUMED'", Integer.class, deliveryId) >= 1);
        }
        System.out.println("REAL E2E PASSED deliveryId=" + deliveryId + " tasks=" + tasks.size() + " sources=" + totalSources
                + " packageBounds=" + pipeline.minPackageBytes() + "-" + pipeline.maxPackageBytes()
                + " profile=" + (production() ? "production" : "local"));
    }

    private static boolean production() {
        return "production".equals(System.getenv("ARCHIVE_VERIFICATION_PROFILE"));
    }

    private static int expectedTasks() {
        return Integer.parseInt(System.getenv().getOrDefault("ARCHIVE_VERIFY_EXPECTED_TASKS", "2"));
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
