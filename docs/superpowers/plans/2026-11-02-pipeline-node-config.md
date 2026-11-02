# Pipeline 节点化配置与版本管理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 OSS/FTP 真实连接配置迁入可版本化 Pipeline 节点，删除 definition_snapshot，并提供安全的草稿、上线、预检和真实验收链路。

**Architecture:** Pipeline 版本是执行时唯一配置来源；run/source/output 只保存版本 ID。草稿可修改，上线不可变并原子切换数据源 binding。节点通过类型化配置显式调用 OSS/SSH 服务，API 和日志始终脱敏。

**Tech Stack:** Java 17、Spring Boot 2.7、JdbcTemplate、Jackson、PostgreSQL、JUnit 5、MockMvc、Docker Compose、Bash、PowerJob 5.1.7。

**当前进度：** Task 1–7 全部完成。Pipeline v4 已上线，run 6 的真实 10 包、HZ 故障重试、过期租约 resume、HZ/SG/FTP 全量核验和 5,999 个历史 FTP 文件基线比较通过。最终 Maven 全量回归、Python、Vue、真实预检、敏感信息、shell 语法和差异检查全部通过，所有步骤已按证据勾选。

---

### Task 1: 类型化 OSS/FTP 节点配置

**Files:**
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/pipeline/OssNodeConfig.java`
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/pipeline/FtpNodeConfig.java`
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/pipeline/PipelineNodeConfig.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/pipeline/PipelineNodeConfigTest.java`

- [x] **Step 1: 写 OSS/FTP 解析与安全校验的失败测试**

```java
class PipelineNodeConfigTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PipelineNodeConfig configs = new PipelineNodeConfig(mapper);

    @Test
    void readsOssAndFtpFromTheirOwnNodes() {
        NodeDefinition ossNode = node("hzUpload", "oss.hz-upload",
                "{\"endpoint\":\"https://oss-cn-hangzhou.aliyuncs.com\",\"bucket\":\"hz-bucket\","
                        + "\"accessKeyId\":\"id\",\"accessKeySecret\":\"secret\"}");
        OssNodeConfig oss = configs.oss(ossNode, false);
        assertThat(oss.bucket()).isEqualTo("hz-bucket");
        assertThat(oss.prefix()).isEmpty();

        NodeDefinition ftpNode = node("publish", "ssh.publish",
                "{\"lines\":{\"1\":{\"host\":\"sg-1\",\"user\":\"root\","
                        + "\"key\":\"/run/secrets/line-1-id_ed25519\","
                        + "\"remoteRoot\":\"/srv/maas-sftp/maasData/archive\","
                        + "\"downloadScript\":\"/opt/archive-delivery/bin/sg_download.sh\","
                        + "\"publishScript\":\"/opt/archive-delivery/bin/publish_package.sh\"}}}");
        assertThat(configs.ftp(ftpNode).line(1).host()).isEqualTo("sg-1");
    }

    @Test
    void rejectsMissingSecretsUnsafePathsAndClean() {
        NodeDefinition badOss = node("hzUpload", "oss.hz-upload",
                "{\"endpoint\":\"\",\"bucket\":\"oss://bad\",\"accessKeyId\":\"\",\"accessKeySecret\":\"\"}");
        assertThatThrownBy(() -> configs.oss(badOss, false)).isInstanceOf(IllegalArgumentException.class);
    }

    private NodeDefinition node(String id, String type, String json) {
        return new NodeDefinition(id, "OUTPUT", type,
                mapper.convertValue(mapper.readTree(json), new com.fasterxml.jackson.core.type.TypeReference<>() {}));
    }
}
```

测试类补充 `throws Exception` 或方法级 `SneakyThrows` 不使用；将两个测试方法声明为 `throws Exception`，helper 也声明 `throws Exception`。

- [x] **Step 2: 运行测试并确认编译失败**

Run: `./scripts/mvn.sh -pl delivery-core -Dtest=PipelineNodeConfigTest test`
Expected: FAIL，提示 `PipelineNodeConfig`、`OssNodeConfig`、`FtpNodeConfig` 不存在。

- [x] **Step 3: 实现 OSS 值对象**

```java
package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OssNodeConfig(String endpoint, String bucket, String prefix,
                            String accessKeyId, String accessKeySecret) {
    public void validate(boolean prefixRequired) {
        if (blank(endpoint) || blank(bucket) || blank(accessKeyId) || blank(accessKeySecret)) {
            throw new IllegalArgumentException("OSS endpoint/bucket/credentials are required");
        }
        if (bucket.contains("oss://")) throw new IllegalArgumentException("OSS bucket must not contain oss://");
        if (prefixRequired && blank(prefix)) throw new IllegalArgumentException("Source OSS prefix is required");
        if (prefix != null && java.util.List.of(prefix.split("/", -1)).contains("..")) {
            throw new IllegalArgumentException("OSS prefix must not contain ..");
        }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
```

- [x] **Step 4: 实现 FTP 值对象与三线读取**

```java
package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FtpNodeConfig(Map<String, Line> lines) {
    public static final String REMOTE_ROOT = "/srv/maas-sftp/maasData/archive";
    public Line line(long lineId) {
        Line line = lines == null ? null : lines.get(Long.toString(lineId));
        if (line == null) throw new IllegalArgumentException("FTP line " + lineId + " is not configured");
        line.validate();
        return line;
    }
    public void validate() { for (long id = 1; id <= 3; id++) line(id); }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(String host, String user, String key, String jumpHost, String jumpUser, String jumpKey,
                       String remoteRoot, String downloadScript, String publishScript) {
        void validate() {
            if (blank(host) || blank(user) || blank(key)) throw new IllegalArgumentException("FTP host/user/key are required");
            if (!REMOTE_ROOT.equals(remoteRoot)) throw new IllegalArgumentException("FTP remoteRoot is fixed");
            if (blank(downloadScript) || blank(publishScript)) throw new IllegalArgumentException("FTP scripts are required");
            if (!blank(jumpHost) && (blank(jumpUser) || blank(jumpKey))) {
                throw new IllegalArgumentException("FTP jumpUser/jumpKey are required with jumpHost");
            }
        }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
```

- [x] **Step 5: 实现统一转换入口并运行测试**

```java
package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class PipelineNodeConfig {
    private final ObjectMapper mapper;
    public PipelineNodeConfig(ObjectMapper mapper) { this.mapper = mapper; }
    public OssNodeConfig oss(NodeDefinition node, boolean prefixRequired) {
        OssNodeConfig config = mapper.convertValue(node.config(), OssNodeConfig.class);
        config.validate(prefixRequired);
        return config;
    }
    public FtpNodeConfig ftp(NodeDefinition node) {
        FtpNodeConfig config = mapper.convertValue(node.config(), FtpNodeConfig.class);
        config.validate();
        return config;
    }
}
```

Run: `./scripts/mvn.sh -pl delivery-core -Dtest=PipelineNodeConfigTest test`
Expected: PASS。

- [x] **Step 6: 提交**

```bash
git add delivery-core && git commit -m "feat: add typed pipeline node connection config"
```

### Task 2: 草稿、脱敏更新与上线 API

**Files:**
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/pipeline/PipelineAdminService.java`
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/pipeline/PipelineVersionConflictException.java`
- Create: `delivery-core/src/main/resources/pipelines/archive-delivery-v3.json`
- Create: `delivery-server/src/main/java/com/powerjob/archive/server/api/PipelineController.java`
- Test: `delivery-server/src/test/java/com/powerjob/archive/server/api/PipelineControllerTest.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/pipeline/PipelineDefinition.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/pipeline/ProductionPipelineTest.java`

- [x] **Step 1: 写版本生命周期和脱敏失败测试**

使用 `RunControllerTest` 的 Testcontainers 基架创建 `PipelineControllerTest`。从 v2 复制草稿，写入带测试连接配置的完整定义，再验证脱敏、上线和不可变：

```java
String draft = mvc.perform(post("/api/v1/pipelines/archive-delivery/versions")
                .contentType("application/json").content("{\"baseVersionId\":2}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
        .andReturn().getResponse().getContentAsString();
long draftId = ((Number) com.jayway.jsonpath.JsonPath.read(draft, "$.id")).longValue();
mvc.perform(put("/api/v1/pipeline-versions/" + draftId + "/definition")
                .contentType("application/json").content(validDefinition()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.definition.nodes[?(@.id=='hzUpload')].config.accessKeySecret")
        .value(org.hamcrest.Matchers.contains("********")));
mvc.perform(get("/api/v1/pipeline-versions/" + draftId))
        .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("test-secret"))));
mvc.perform(post("/api/v1/pipeline-versions/" + draftId + "/publish")).andExpect(status().isOk());
mvc.perform(put("/api/v1/pipeline-versions/" + draftId + "/definition")
                .contentType("application/json").content(validDefinition()))
        .andExpect(status().isConflict());
assertThat(jdbc.queryForObject("SELECT pipeline_version_id FROM source_pipeline_binding WHERE effective_to IS NULL", Long.class))
        .isEqualTo(draftId);
```

`validDefinition()` 读取 `../delivery-core/src/main/resources/pipelines/archive-delivery-v3.json`，用下面的代码填充测试值，避免把真实密钥写入测试：

```java
private static String validDefinition() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode root = (ObjectNode) mapper.readTree(Path.of("../delivery-core/src/main/resources/pipelines/archive-delivery-v3.json"));
    for (JsonNode node : root.required("nodes")) {
        String id = node.required("id").asText();
        if (Set.of("download", "hzUpload", "sgVerify").contains(id)) {
            ObjectNode config = ((ObjectNode) node).putObject("config");
            config.put("endpoint", "https://oss-cn-hangzhou.aliyuncs.com").put("bucket", id + "-bucket")
                    .put("accessKeyId", "test-id").put("accessKeySecret", "test-secret");
            if (id.equals("download")) config.put("prefix", "source/");
        }
        if (Set.of("sgDownload", "publish").contains(id)) {
            ObjectNode lines = ((ObjectNode) node).putObject("config").putObject("lines");
            for (long line = 1; line <= 3; line++) {
                lines.putObject(Long.toString(line)).put("host", "sg-" + line).put("user", "root")
                        .put("key", "/run/secrets/line-" + line + "-id_ed25519")
                        .put("remoteRoot", "/srv/maas-sftp/maasData/archive")
                        .put("downloadScript", "/opt/archive-delivery/bin/sg_download.sh")
                        .put("publishScript", "/opt/archive-delivery/bin/publish_package.sh");
            }
        }
    }
    return mapper.writeValueAsString(root);
}
```

- [x] **Step 2: 运行 API 测试并确认 404**

Run: `./scripts/mvn.sh -pl delivery-server -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=PipelineControllerTest test`
Expected: FAIL，版本接口 404。

- [x] **Step 3: 扩展 PipelineDefinition 上线校验**

在 `validateProduction()` 保留根配置检查后增加：

```java
PipelineNodeConfig configs = new PipelineNodeConfig(new com.fasterxml.jackson.databind.ObjectMapper());
configs.oss(node("download"), true);
configs.oss(node("hzUpload"), false);
configs.oss(node("sgVerify"), false);
configs.ftp(node("sgDownload"));
configs.ftp(node("publish"));
```

`PipelineNodeConfig` 不依赖 Spring，因此直接创建不会引入环境配置。`ProductionPipelineTest` 改为读取 v3 并用 `PipelineControllerTest.validDefinition()` 相同的测试值填充后再调用 `validateProduction()`，保留危险策略拒绝用例。

创建 `archive-delivery-v3.json`，真实密钥保持为空，使草稿无法直接上线：

```json
{
  "pipelineCode":"archive-delivery",
  "version":3,
  "config":{"crrWaitSeconds":180,"autoRollover":false,"ftpCleanBeforeDownload":false},
  "nodes":[
    {"id":"download","scope":"SOURCE","type":"archive.download","config":{"endpoint":"","bucket":"","prefix":"","accessKeyId":"","accessKeySecret":""}},
    {"id":"repair","scope":"SOURCE","type":"python.repair","config":{"ruleSet":"fix-archive-legacy-v1"}},
    {"id":"qualify","scope":"SOURCE","type":"manifest.qualify","config":{}},
    {"id":"group","scope":"SOURCE","type":"output.group","config":{}},
    {"id":"hzUpload","scope":"OUTPUT","type":"oss.hz-upload","config":{"endpoint":"","bucket":"","accessKeyId":"","accessKeySecret":"","contentMd5":true}},
    {"id":"sgVerify","scope":"OUTPUT","type":"oss.sg-verify","config":{"endpoint":"","bucket":"","accessKeyId":"","accessKeySecret":"","pollSeconds":30,"maxWaitSeconds":7200}},
    {"id":"sgDownload","scope":"OUTPUT","type":"ssh.sg-download","config":{"commandTimeoutSeconds":900,"lines":{}}},
    {"id":"publish","scope":"OUTPUT","type":"ssh.publish","config":{"commandTimeoutSeconds":60,"lines":{}}},
    {"id":"receipt","scope":"OUTPUT","type":"delivery.receipt","config":{}}
  ]
}
```

- [x] **Step 4: 实现 PipelineAdminService**

实现以下公开签名，所有修改使用 `TransactionTemplate`：

```java
public List<VersionView> list(String pipelineCode)
public VersionView get(long id)
public VersionView createDraft(String pipelineCode, long baseVersionId)
public VersionView updateDraft(long id, JsonNode definition)
public VersionView publish(long id)
public JsonNode rawDefinition(long id)
```

`VersionView` 包含 `id/pipelineCode/version/status/definitionSha256/definition`。`rawDefinition` 仅用于执行与内部合并，不经过 Controller。`get/list/createDraft/updateDraft/publish` 返回前递归将 `accessKeyId`、`accessKeySecret` 替换为 `********`。

`updateDraft` 先确认 `status='DRAFT'`，再递归合并：新 JSON 中密钥字段缺失或值为 `********` 时复制旧定义同路径值；其他字段以新 JSON 为准。`publish` 调用 `pipelines.read(raw).validateProduction()`，然后更新状态、SHA、`published_at`，并在同一事务关闭旧 binding、插入新 binding。已上线修改抛出 `PipelineVersionConflictException`。

SHA 使用 `objectMapper.writeValueAsBytes(definition)` 的 SHA-256；数据库唯一约束继续防止重复版本号。

- [x] **Step 5: 实现 Controller 与冲突响应**

```java
@RestController
@RequestMapping("/api/v1")
public class PipelineController {
    private final PipelineAdminService pipelines;
    public PipelineController(PipelineAdminService pipelines) { this.pipelines = pipelines; }

    @GetMapping("/pipelines/{code}/versions") public List<PipelineAdminService.VersionView> list(@PathVariable String code) { return pipelines.list(code); }
    @GetMapping("/pipeline-versions/{id}") public PipelineAdminService.VersionView get(@PathVariable long id) { return pipelines.get(id); }
    @PostMapping("/pipelines/{code}/versions") @ResponseStatus(HttpStatus.CREATED)
    public PipelineAdminService.VersionView draft(@PathVariable String code, @RequestBody DraftRequest request) { return pipelines.createDraft(code, request.baseVersionId()); }
    @PutMapping("/pipeline-versions/{id}/definition") public PipelineAdminService.VersionView update(@PathVariable long id, @RequestBody JsonNode definition) { return pipelines.updateDraft(id, definition); }
    @PostMapping("/pipeline-versions/{id}/publish") public PipelineAdminService.VersionView publish(@PathVariable long id) { return pipelines.publish(id); }

    @ExceptionHandler(PipelineVersionConflictException.class)
    ResponseEntity<Map<String, String>> conflict(RuntimeException error) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", error.getMessage())); }
    public record DraftRequest(long baseVersionId) {}
}
```

Controller 必须添加 Jackson、Spring Web 和 `java.util` imports；`PipelineVersionConflictException` 创建在 core pipeline 包中。

- [x] **Step 6: 运行测试并提交**

Run: `./scripts/mvn.sh -pl delivery-server -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=PipelineControllerTest,PipelineNodeConfigTest test`
Expected: PASS，响应和日志无 `test-secret`。

```bash
git add delivery-core delivery-server && git commit -m "feat: manage draft and published pipeline versions"
```

### Task 3: 删除 definition_snapshot，统一版本读取

**Files:**
- Create: `delivery-core/src/main/resources/db/migration/V5__pipeline_version_config.sql`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/domain/SourcePackage.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/domain/OutputPackage.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/repository/SourcePackageRepository.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/repository/OutputPackageRepository.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/service/MvpSeedService.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/pipeline/NodeContext.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/pipeline/PipelineEngine.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/repository/DeliveryRunRepositoryTest.java`

- [x] **Step 1: 写关联版本定义的失败测试**

在 repository 集成测试创建 source 后断言：

```java
var source = sources.findById(sourceId).orElseThrow();
assertThat(source.pipelineVersionId()).isEqualTo(2);
assertThat(source.pipelineDefinition().required("pipelineCode").asText()).isEqualTo("archive-delivery");
assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns " +
        "WHERE table_name='source_package' AND column_name='definition_snapshot'", Integer.class)).isZero();
```

对 output 的 `findById/claimNext` 增加同样断言，确认 `pipelineDefinition` 来自 `pipeline_version` 而不是 source 列。

- [x] **Step 2: 运行测试并确认编译/查询失败**

Run: `./scripts/mvn.sh -pl delivery-core -Dtest=DeliveryRunRepositoryTest test`
Expected: FAIL，`pipelineDefinition` 不存在且 snapshot 列仍存在。

- [x] **Step 3: 写 V5 迁移和 v3 草稿资源**

`V5__pipeline_version_config.sql` 复制 v2 定义作为不可直接上线的 v3 草稿，不修改 v2 active binding；真实配置在 Task 6 通过 API 写入并重新计算 SHA：

```sql
ALTER TABLE pipeline_version ALTER COLUMN published_at DROP NOT NULL;
INSERT INTO pipeline_version(id,pipeline_id,version_no,status,definition,definition_sha256,published_at)
SELECT 3,pipeline_id,3,'DRAFT',definition,definition_sha256,NULL FROM pipeline_version WHERE id=2;
ALTER TABLE source_package DROP COLUMN definition_snapshot;
SELECT setval('pipeline_version_id_seq',(SELECT max(id) FROM pipeline_version),true);
```

Task 2 创建的 v3 JSON 根配置保持 `180/false/false`，节点 ID/type/scope 不变；OSS/FTP 字段为空字符串，只作为不可上线的草稿模板，真实密钥由本地初始化 API 写入。

- [x] **Step 4: 改领域对象与仓库 JOIN**

两个领域 record 将 `JsonNode pipelineSnapshot` 更名为 `JsonNode pipelineDefinition`。所有 source 查询使用：

```sql
SELECT p.*,pv.definition AS pipeline_definition
FROM source_package p JOIN pipeline_version pv ON pv.id=p.pipeline_version_id
```

`upsertDiscovered` 的 INSERT 列删除 `definition_snapshot`，SELECT 删除 `pv.definition`，保留 `r.pipeline_version_id`。`upsertPlanned` 改为从参数接收 `pipelineVersionId`，不再接收 snapshot 字符串；`MvpSeedService` 和 `SourceClaimConcurrencyTest` 同步传入明确的版本 ID。

Output 查询使用：

```sql
SELECT op.*,pv.definition AS pipeline_definition
FROM output_package op JOIN pipeline_version pv ON pv.id=op.pipeline_version_id
```

claim CTE 在最外层 JOIN 版本表。两个 RowMapper 均读取 `rs.getString("pipeline_definition")`。

- [x] **Step 5: 改 PipelineEngine 和 NodeContext**

`NodeContext` 增加 `PipelineDefinition pipeline`：

```java
public record NodeContext(SourcePackage source, OutputPackage output, NodeDefinition node,
                          PipelineDefinition pipeline, long runId, long fence, String workerId) {
    public long packageId() { return source != null ? source.id() : output.id(); }
}
```

Engine 每个执行方法先构造：

```java
PipelineDefinition pipeline = pipelines.read(source.pipelineDefinition());
NodeDefinition node = pipeline.node(source.currentNode());
NodeContext context = new NodeContext(source, null, node, pipeline, runId, source.fence(), workerId);
```

Output 使用同样的 `output.pipelineDefinition()`。把所有节点中的根配置读取改为 `context.pipeline().intConfig(...)` 或 `booleanConfig(...)`，所有测试构造器同步传入测试 PipelineDefinition。

- [x] **Step 6: 运行 core 回归并提交**

Run: `./scripts/mvn.sh -pl delivery-core test`
Expected: PASS，无 `definition_snapshot` 或 `pipelineSnapshot` 引用。

```bash
git add delivery-core && git commit -m "refactor: read pipeline definitions by version"
```

### Task 4: OSS 发现、下载、上传与核验使用节点配置

**Files:**
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/service/OssClientFactory.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/service/SourceDownloader.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/service/OssSourceDownloader.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/service/OssTransferService.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/service/OssDiscoveryService.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/service/RunSubmissionService.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/node/DownloadNode.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/node/OssHzUploadNode.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/node/OssSgVerifyNode.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/config/MvpProperties.java`
- Delete: `delivery-core/src/main/java/com/powerjob/archive/core/config/OssDeliveryProperties.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/service/OssTransferServiceTest.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/service/OssDiscoveryIntegrationTest.java`

- [x] **Step 1: 写无环境回退的失败测试**

修改 `OssTransferServiceTest`，构造两个不同 bucket 的 `OssNodeConfig`，调用：

```java
service.uploadHz(hzConfig, artifact, key, sha, 1L, 2L);
assertThat(service.hzUri(hzConfig, key)).isEqualTo("oss://hz-bucket/" + key);
service.headSg(sgConfig, key);
```

Mock 客户端断言只收到参数中的 endpoint/bucket/credentials。另写 discovery 测试：`delivery_run.pipeline_version_id` 指向不同 download 配置时，list 请求使用该版本 bucket/prefix，而不是 `MvpProperties`。

- [x] **Step 2: 运行定向测试并确认签名不匹配**

Run: `./scripts/mvn.sh -pl delivery-core -Dtest=OssTransferServiceTest,OssDiscoveryIntegrationTest test`
Expected: FAIL，新方法尚不存在。

- [x] **Step 3: 改 OSS 客户端与下载器**

```java
public OSS create(OssNodeConfig config) {
    ClientBuilderConfiguration settings = new ClientBuilderConfiguration();
    settings.setConnectionTimeout(10_000);
    settings.setSocketTimeout(60_000);
    settings.setMaxErrorRetry(2);
    return new OSSClientBuilder().build(config.endpoint(), config.accessKeyId(), config.accessKeySecret(), settings);
}
```

`SourceDownloader` 改为 `download(SourcePackage source, OssNodeConfig config, Path destination)`；实现类用该 config 创建客户端。`DownloadNode` 调用 `configs.oss(context.node(), true)` 并显式传入下载器。

- [x] **Step 4: 改上传与 SG 服务及节点**

`OssTransferService` 删除 properties 字段，方法签名统一为：

```java
uploadHz(OssNodeConfig config, Path artifact, String key, String expectedSha, long runId, long packageId)
hzUri(OssNodeConfig config, String key)
headSg(OssNodeConfig config, String key)
sgUri(OssNodeConfig config, String key)
```

hzUpload 从当前 node 读取 config；sgVerify 从当前 node 读取 config；bucket 和 URI 均从同一 config 生成。CRR 根配置从 `context.pipeline()` 读取。

- [x] **Step 5: 改发现与提交一致性**

`OssDiscoveryService` 查询：

```sql
SELECT r.bucket_name,r.object_prefix,pv.definition
FROM delivery_run r JOIN pipeline_version pv ON pv.id=r.pipeline_version_id WHERE r.id=?
```

用 `PipelineRepository.read` 和 `PipelineNodeConfig.oss(definition.node("download"), true)` 创建客户端。list 使用 config.bucket/prefix，并断言与 run 的 bucket/prefix 相同。`RunSubmissionService.currentBinding` 同时读取 definition，提交时验证 ossUri 与 download config 一致，不一致返回 400，避免运行后才发现错配。

删除 `MvpProperties` 五个 `ossSource*` 字段和 getter/setter，删除 `OssDeliveryProperties` 与对应环境校验。

- [x] **Step 6: 运行 OSS/core 测试并提交**

Run: `./scripts/mvn.sh -pl delivery-core test`
Expected: PASS，无 OSS 环境属性回退。

```bash
git add delivery-core && git commit -m "feat: execute oss nodes with versioned config"
```

### Task 5: 三线 SSH 使用当前节点配置

**Files:**
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/service/SshCommandRunner.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/node/SshSgDownloadNode.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/node/SshPublishNode.java`
- Delete: `delivery-core/src/main/java/com/powerjob/archive/core/config/FtpDeliveryProperties.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/service/SshCommandRunnerTest.java`

- [x] **Step 1: 写按线配置的失败测试**

将原 properties fixture 改为两个 `FtpNodeConfig.Line`，执行：

```java
runner.run(line1, line1.downloadScript(), List.of("oss://bucket/key"), Duration.ofSeconds(5));
runner.run(line2, line2.publishScript(), List.of("/staging", "pkg.tar.gz"), Duration.ofSeconds(5));
```

用 fake `ssh` 分别断言 line1/line2 的 `-i`、jump host、目标 host；脚本不在对应 line 的固定值中时抛出 `IllegalArgumentException`。保留原有 host/user/key/argument 安全测试。

- [x] **Step 2: 运行测试并确认编译失败**

Run: `./scripts/mvn.sh -pl delivery-core -Dtest=SshCommandRunnerTest test`
Expected: FAIL，`run(FtpNodeConfig.Line,...)` 不存在。

- [x] **Step 3: 改 SshCommandRunner**

公开签名改为：

```java
public String run(FtpNodeConfig.Line line, String script, List<String> arguments, Duration timeout)
```

方法内部不再按 lineId 查询 properties；固定脚本校验、host/user/key 校验、ProxyCommand、超时和 exit 65/66 处理保持原逻辑。原有静态白名单方法保持不变。

- [x] **Step 4: 改两个 SSH 节点**

两个节点注入 `PipelineNodeConfig`，执行：

```java
FtpNodeConfig.Line line = configs.ftp(context.node()).line(context.output().productionLineId());
ssh.run(line, line.downloadScript(), arguments, Duration.ofSeconds(timeout));
```

publish 使用 `line.publishScript()`。远程目录、staging 和最终路径都由该 line 生成；根级 `ftpCleanBeforeDownload` 从 `context.pipeline()` 读取。删除 FTP properties 和启动校验器。

- [x] **Step 5: 运行 core 全量并提交**

Run: `./scripts/mvn.sh -pl delivery-core test`
Expected: PASS，无 `FtpDeliveryProperties`、`ProductionConfigurationValidator` 或 FTP 环境回退。

```bash
git add delivery-core && git commit -m "feat: execute ssh nodes with line version config"
```

### Task 6: 本地 Pipeline 初始化、Compose 与有界预检

**Files:**
- Create: `scripts/configure-pipeline.sh`
- Create: `scripts/ftp-inspect.sh`
- Modify: `scripts/preflight.sh`
- Modify: `scripts/real-e2e-test.sh`
- Modify: `scripts/mvp-up.sh`
- Modify: `deploy/docker-compose.yml`
- Modify: `deploy/env.example`
- Modify: `.gitignore`
- Modify: `delivery-core/src/test/java/com/powerjob/archive/core/service/ProductionPreflightTest.java`
- Modify: `delivery-worker/src/test/java/com/powerjob/archive/worker/RealProductionEndToEndTest.java`

- [x] **Step 1: 写本地初始化脚本的 shell 检查与 API 失败测试**

`configure-pipeline.sh` 要求传入一个本地 JSON 文件路径，拒绝空文件和 `replace-` 值；先查询版本列表并选择最大 version id，若为 DRAFT则 PUT，若已 PUBLISHED则从该最新版本复制新草稿再 PUT，最后 POST publish。输出只包含 version id/status，不使用 `set -x`，不 echo JSON。

Run: `bash -n scripts/configure-pipeline.sh`
Expected: 语法 PASS；无参数和空文件均非零退出。

- [x] **Step 2: 实现初始化脚本与敏感文件忽略**

脚本核心请求：

```bash
versions_json="$(curl -fsS "$API/api/v1/pipelines/archive-delivery/versions")"
version_json="$(python3 -c 'import json,sys; rows=json.load(sys.stdin); print(json.dumps(max(rows,key=lambda row:row["id"])))' <<<"$versions_json")"
base_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$version_json")"
status="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])' <<<"$version_json")"
if [[ "$status" == "PUBLISHED" ]]; then
  version_json="$(curl -fsS -H 'Content-Type: application/json' -d "{\"baseVersionId\":$base_id}" "$API/api/v1/pipelines/archive-delivery/versions")"
fi
version_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$version_json")"
curl -fsS -X PUT -H 'Content-Type: application/json' --data-binary "@$CONFIG_FILE" "$API/api/v1/pipeline-versions/$version_id/definition" >/dev/null
curl -fsS -X POST "$API/api/v1/pipeline-versions/$version_id/publish" >/dev/null
printf 'Published Pipeline version %s\n' "$version_id"
```

将 `deploy/pipeline-v3.local.json` 加入 `.gitignore`。本地真实文件包含设计规定的节点字段和三条线 `/run/secrets/line-N-id_ed25519`，不得提交。

- [x] **Step 3: 删除 Compose OSS/FTP 环境变量并固定只读挂载**

删除 `OSS_SOURCE_*`、`OSS_HZ_*`、`OSS_SG_*`、`FTP_LINE_*` environment 项。每个 Worker 只挂载该线密钥：

```yaml
- ${HOME}/.ssh/archive-delivery-mvp/line-1-id_ed25519:/run/secrets/line-1-id_ed25519:ro
```

line2/3 同理。`${HOME}` 仅描述宿主机密钥位置，不提供业务连接配置。Worker 保持 `ARCHIVE_DEBUG_LOOP=false` 和 PowerJob 配置。`env.example` 只保留数据库、PowerJob 与本地运行配置。

- [x] **Step 4: 改预检从绑定版本读取配置**

`ProductionPreflightTest` 通过 JDBC 查询 active binding 的 `pv.definition`，用 `PipelineRepository/PipelineNodeConfig` 创建 OSS 客户端，执行源 list、HZ 小对象写/读/删、SG HEAD；从 publish 节点读取三条 FTP line 并调用 `SshCommandRunner` 执行固定只读命令。测试仅在 `ARCHIVE_PREFLIGHT=true` 时启用。

`preflight.sh` 只 source 数据库/运行环境，不再检查 OSS/FTP 环境变量；将三线宿主机密钥只读挂载到预检容器 `/run/secrets`，连接 PostgreSQL 后运行该测试。任何缺配置、权限或网络失败立即非零退出，不进入 real-e2e。

- [x] **Step 5: 改真实 E2E 不再读取 FTP 环境变量**

新增 `scripts/ftp-inspect.sh`，参数为 `before|after` 和输出目录。脚本通过 Compose `psql` 读取 active 版本的 publish 节点三线配置，把容器密钥路径 `/run/secrets/line-N-id_ed25519` 映射到 `${HOME}/.ssh/archive-delivery-mvp/line-N-id_ed25519`，用节点中的 host/user/jump 执行固定 `find` 只读命令，输出相对路径、size、mtime；不得打印 OSS 配置。

`real-e2e-test.sh` 先调用 `configure-pipeline.sh deploy/pipeline-v3.local.json`，再调用 `ftp-inspect.sh before "$WORK"` 和 `ftp-inspect.sh after "$WORK"`。根策略检查当前 active 版本，不断言固定 v2。`RealProductionEndToEndTest` 同样通过 run 的 `pipeline_version_id` 读取 HZ/SG/FTP 配置，删除 `System.getenv` 的 OSS/FTP 读取。提交、观察、SHA 和历史文件 size/mtime 对比逻辑保持不变。

Run: `bash -n scripts/configure-pipeline.sh scripts/ftp-inspect.sh scripts/preflight.sh scripts/real-e2e-test.sh scripts/mvp-up.sh`
Expected: PASS。

- [x] **Step 6: 构建镜像并验证三 Worker 注册**

Run:

```bash
./scripts/mvp-up.sh
./scripts/bootstrap-powerjob.sh
docker compose -f deploy/docker-compose.yml --env-file deploy/.env ps -a
```

Expected: 7 服务 healthy/running，Worker 不再因 OSS/FTP 环境缺失退出；PowerJob 三项任务有真实 instance，debug loop 未启动。

- [x] **Step 7: 提交**

```bash
git add .gitignore deploy delivery-core delivery-worker scripts && git commit -m "feat: bootstrap and preflight versioned pipeline config"
```

### Task 7: 文档、全量回归与真实最终验收

**Files:**
- Modify: `docs/install.md`
- Modify: `docs/deploy.md`
- Modify: `docs/mvp-runbook.md`
- Modify: `docs/superpowers/plans/2026-11-01-archive-delivery-local-production-mvp.md`
- Modify: `docs/superpowers/plans/2026-11-02-pipeline-node-config.md`

- [x] **Step 1: 更新文档和验收口径**

文档说明草稿/上线 API、本地 JSON 不入 Git、密钥只存受控版本表、版本切换不影响既有 run、SSH 只读挂载及故障排查。删除 OSS/FTP 环境变量教程。MVP 最终验收改为“API 明文响应、日志、manifest、看板无密钥”，不再要求“无密钥入库”。

- [x] **Step 2: 运行全部非生产测试**

Run:

```bash
python3 -m unittest nodes/python/test_archive_repair.py -v
./scripts/mvp-test.sh
cd web && npm ci && npm run test && npm run build
cd .. && git diff --check
```

Expected: 全 PASS；RealProductionEndToEndTest 在非生产模式 skipped 不计为真实证据。

- [x] **Step 3: 执行一次真实有界预检**

Run: `./scripts/preflight.sh`
Expected: 源 OSS、HZ 测试写、SG HEAD、三线 SSH/ossutil 全 PASS；失败则记录实际错误并停止，不循环重试。

- [x] **Step 4: 执行真实 10 包验收**

Run: `./scripts/real-e2e-test.sh`
Expected: PowerJob 实际调度，LINE-1/2/3 为 4/3/3，10/10 DELIVERED，HZ/SG/FTP SHA 一致，历史 FTP 文件未改变，无 debug loop 或直接 PipelineEngine 替代。

- [x] **Step 5: 验证密钥不泄露和最终工作区**

Run:

```bash
./scripts/verify-delivery.sh 1
git diff --check
git status --short
```

另外查询版本列表、包详情、timeline 和应用日志，断言不包含真实 AK/SK；数据库 `pipeline_version.definition` 包含真实配置是本次明确允许的行为。不得提交 `deploy/.env`、`deploy/pipeline-v3.local.json`、私钥、日志或测试包。

- [x] **Step 6: 更新两项计划并提交最终改动**

只在实际证据具备时勾选 Task 12 Step 4 和 Task 15 Step 2–5/7；环境仍阻塞时保持未勾选并记录恢复入口。提交：

```bash
git add docs scripts delivery-core delivery-server delivery-worker deploy .gitignore
git commit -m "feat: complete versioned pipeline configuration"
```
