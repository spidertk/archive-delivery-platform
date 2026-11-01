# Archive Delivery MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 不使用前端，以 PostgreSQL、PowerJob Processor、Java 节点和 Python repair 跑通一个真实样本 tar 从发现到 DELIVERED 的完整 Pipeline，并保留可验证血缘。

**Architecture:** `delivery-core` 保存状态机、Pipeline SPI、JDBC 仓储和本地传输节点；`delivery-worker` 提供 PowerJob `BroadcastProcessor` 与命令行调试入口。MVP 用三个本地目录模拟杭州 OSS、新加坡 OSS 和 FTP，但节点状态、SHA 核验、原子发布和数据库提交均走真实代码；真实外部服务不在本次验收范围内。

**Tech Stack:** Java 17、Spring Boot 2.7.18、PowerJob Worker 5.1.7、PostgreSQL 14+、Flyway、JdbcTemplate、Jackson、JUnit 5、Testcontainers、Python 3.10+ 标准库、Docker Compose。

## 2026-10-31 范围修订（优先于下文单样本描述）

最终 MVP 使用真实只读输入 `oss://junflow-ysai-source-hz-20260906/_derived/ysai-sftp-20261027/delivery_plan_0005/`，按对象 Key 排序取前 10 个 `.tar.gz`。前 5 个分配给 `LINE-1/june`，后 5 个分配给 `LINE-2/june-1`，每条生产线各一个 5 包大组。`MvpEndToEndTest` 用同一样本复制为 10 个不同对象名验证数据库、血缘和完整 Pipeline，不将该测试视为真实 OSS 验收。

真实输入 OSS 需要 List/Get 权限；杭州 OSS、新加坡 OSS 和 FTP 仍使用本地目录适配器。下文早期单样本配置、30 次 Tick 和“真实外部服务不在范围内”仅保留为设计演进记录，以本修订、`docs/mvp-runbook.md` 和实际代码为准。

---

## MVP 边界与文件结构

样本：`old/sample/batch_00002.tar.gz`，包含 4 个 `req_*` 目录。流程严格参考 `批量修复工作流.md`：源包下载 → repair → 杭州 OSS → 新加坡 OSS 核验 → FTP 下载/发布 → 回写交付。本地适配器只替换网络传输，不改变节点顺序。

```text
archive-delivery-platform/
├── pom.xml
├── delivery-core/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/powerjob/archive/core/
│       │   ├── config/MvpProperties.java
│       │   ├── domain/
│       │   ├── pipeline/
│       │   ├── repository/
│       │   ├── service/
│       │   └── node/
│       ├── main/resources/
│       │   ├── db/migration/V1__mvp_schema.sql
│       │   ├── db/migration/V2__mvp_pipeline.sql
│       │   └── pipelines/archive-delivery-v1.json
│       └── test/java/com/powerjob/archive/core/
├── delivery-worker/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/powerjob/archive/worker/
│       │   ├── ArchiveWorkerApplication.java
│       │   ├── PackageTickProcessor.java
│       │   └── MvpDebugRunner.java
│       ├── main/resources/application.yml
│       └── test/java/com/powerjob/archive/worker/
├── nodes/python/archive_repair.py
├── nodes/python/test_archive_repair.py
├── deploy/docker-compose.yml
├── deploy/Dockerfile.worker
├── scripts/mvp-up.sh
├── scripts/mvp-test.sh
└── docs/mvp-runbook.md
```

### Task 1: Maven、Java 17 容器和 PostgreSQL 配置

**Files:**
- Create: `pom.xml`
- Create: `delivery-core/pom.xml`
- Create: `delivery-worker/pom.xml`
- Create: `deploy/docker-compose.yml`
- Create: `delivery-worker/src/main/resources/application.yml`

- [ ] **Step 1: 创建根 POM 和模块 POM**

根项目锁定 Java 17、Spring Boot 2.7.18、PowerJob 5.1.7、Testcontainers 1.19.8。core 依赖 Spring JDBC、Flyway、PostgreSQL、Jackson 和 Commons Compress；worker 依赖 core 与 `tech.powerjob:powerjob-worker-spring-boot-starter:5.1.7`。测试依赖 `spring-boot-starter-test`、`org.testcontainers:postgresql` 和 `org.testcontainers:junit-jupiter`。

- [ ] **Step 2: 创建 PostgreSQL Compose 配置**

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: archive_delivery
      POSTGRES_USER: archive
      POSTGRES_PASSWORD: archive_local
    ports:
      - "55432:5432"
    volumes:
      - archive_pg:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U archive -d archive_delivery"]
      interval: 2s
      timeout: 2s
      retries: 30
volumes:
  archive_pg:
```

本地密码只用于开发容器，不作为生产配置。

- [ ] **Step 3: 创建应用配置**

```yaml
spring:
  datasource:
    url: ${ARCHIVE_DB_URL:jdbc:postgresql://localhost:55432/archive_delivery}
    username: ${ARCHIVE_DB_USER:archive}
    password: ${ARCHIVE_DB_PASSWORD:archive_local}
  flyway:
    enabled: true
    schemas: archive_delivery
    default-schema: archive_delivery
powerjob:
  worker:
    enabled: ${POWERJOB_ENABLED:false}
    app-name: ${POWERJOB_APP_NAME:archive-june}
    server-address: ${POWERJOB_SERVER_ADDRESS:localhost:7700}
    protocol: http
    port: 27777
    store-strategy: disk
    allow-lazy-connect-server: true
archive:
  worker-id: ${HOSTNAME:local-worker}
  production-line: LINE-1
  provider-code: june
  archive-date: 2026-10-30
  source-file: /samples/batch_00002.tar.gz
  work-root: /data/work
  hz-root: /data/hz-oss
  sg-root: /data/sg-oss
  ftp-root: /data/ftp
  python-command: python3
  python-script: /app/nodes/python/archive_repair.py
  max-ticks: 30
logging:
  level:
    com.powerjob.archive: INFO
```

- [ ] **Step 4: 验证依赖解析**

本机当前没有 Java 17 和 Maven，统一使用容器：

```bash
cd archive-delivery-platform
docker run --rm -v "$PWD":/workspace -v "$HOME/.m2":/root/.m2 \
  -w /workspace maven:3.9-eclipse-temurin-17 \
  mvn -q -DskipTests package
```

预期：两个模块均 BUILD SUCCESS；若 PowerJob 依赖仲裁失败，固定 `powerjob-common` 和 remote 模块同为 5.1.7 后重新执行，不修改 PowerJob 源码。

### Task 2: PostgreSQL 核心模型、关系和种子 Pipeline

**Files:**
- Create: `delivery-core/src/main/resources/db/migration/V1__mvp_schema.sql`
- Create: `delivery-core/src/main/resources/db/migration/V2__mvp_pipeline.sql`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/repository/SchemaIntegrationTest.java`

- [ ] **Step 1: 编写 schema 集成测试**

测试启动 PostgreSQL Testcontainer 并执行 Flyway，然后断言以下关系存在：

```java
assertThat(jdbc.queryForObject(
    "SELECT count(*) FROM information_schema.tables " +
    "WHERE table_schema='archive_delivery' AND table_name IN " +
    "('source_package','source_member','output_package','request_mapping','output_artifact')",
    Integer.class)).isEqualTo(5);
assertThat(jdbc.queryForObject(
    "SELECT definition->>'pipelineCode' FROM archive_delivery.pipeline_version " +
    "WHERE id=1", String.class)).isEqualTo("archive-delivery");
```

- [ ] **Step 2: 运行测试确认迁移尚不存在**

Run: `mvn -pl delivery-core -Dtest=SchemaIntegrationTest test`
Expected: FAIL，原因是 Flyway migration 文件尚未创建。

- [ ] **Step 3: 创建 V1 核心表**

V1 创建设计文档中的核心表及外键：`production_line 1—N production_group`、`source_package 1—N source_member`、`source_package 1—N output_package`、`output_package 1—N request_mapping`、`source_member 1—1 request_mapping`、`output_package 1—N output_artifact`、`package_node_run 1—N package_event`。

必须包含：

```sql
UNIQUE (bucket_name, object_key, object_version)
UNIQUE (source_package_id, member_key)
UNIQUE (source_member_id)
UNIQUE (new_request_id)
UNIQUE (production_group_id, group_seq)
UNIQUE (namespace, source_member_id, request_key)
CHECK ((source_package_id IS NULL) <> (output_package_id IS NULL))
```

`package_node_run` 使用两个部分唯一索引，不使用含可空字段的联合唯一键代替。

- [ ] **Step 4: 创建 V2 Pipeline 和生产线种子**

插入 LINE-1、目标数为 1 的 FILLING 大组、`archive-delivery:v1`、`june` provider 和本地数据源。Pipeline JSON 节点依次为：

```json
{
  "pipelineCode": "archive-delivery",
  "version": 1,
  "nodes": [
    {"id":"download","scope":"SOURCE","type":"local.download","config":{}},
    {"id":"repair","scope":"SOURCE","type":"python.repair","config":{"providerCode":"june"}},
    {"id":"qualify","scope":"SOURCE","type":"manifest.qualify","config":{}},
    {"id":"group","scope":"SOURCE","type":"output.group","config":{}},
    {"id":"hzUpload","scope":"OUTPUT","type":"local.hz-upload","config":{}},
    {"id":"sgVerify","scope":"OUTPUT","type":"local.sg-verify","config":{}},
    {"id":"sgDownload","scope":"OUTPUT","type":"local.sg-download","config":{}},
    {"id":"publish","scope":"OUTPUT","type":"local.publish","config":{}},
    {"id":"receipt","scope":"OUTPUT","type":"delivery.receipt","config":{}}
  ]
}
```

- [ ] **Step 5: 重跑 schema 测试**

Run: `mvn -pl delivery-core -Dtest=SchemaIntegrationTest test`
Expected: PASS；另执行插入重复 `(bucket_name,object_key,object_version)`，预期唯一键异常。

### Task 3: 领域模型、Pipeline 加载和 JDBC 仓储

**Files:**
- Create: `domain/BusinessStatus.java`
- Create: `domain/RunStatus.java`
- Create: `domain/SourcePackage.java`
- Create: `domain/OutputPackage.java`
- Create: `pipeline/PipelineDefinition.java`
- Create: `pipeline/NodeDefinition.java`
- Create: `pipeline/PipelineRepository.java`
- Create: `repository/SourcePackageRepository.java`
- Create: `repository/OutputPackageRepository.java`
- Create: `repository/NodeRunRepository.java`
- Test: `pipeline/PipelineRepositoryTest.java`
- Test: `repository/PackageRepositoryTest.java`

- [ ] **Step 1: 写 Pipeline 与并发领取测试**

```java
assertThat(pipeline.nodes().stream().map(NodeDefinition::id))
    .containsExactly("download","repair","qualify","group","hzUpload",
        "sgVerify","sgDownload","publish","receipt");

SourcePackage first = sourceRepository.claimNext("LINE-1", "worker-a").orElseThrow();
assertThat(sourceRepository.claimNext("LINE-1", "worker-b")).isEmpty();
assertThat(first.runStatus()).isEqualTo(RunStatus.RUNNING);
```

- [ ] **Step 2: 实现不可变 Pipeline 记录**

```java
public record PipelineDefinition(String pipelineCode, int version, List<NodeDefinition> nodes) {
    public NodeDefinition node(String id) {
        return nodes.stream().filter(n -> n.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown node: " + id));
    }
    public NodeDefinition next(String id) {
        int index = nodes.indexOf(node(id));
        if (index == nodes.size() - 1) throw new IllegalStateException("No next node");
        return nodes.get(index + 1);
    }
}
public record NodeDefinition(String id, String scope, String type, Map<String,Object> config) {}
```

- [ ] **Step 3: 实现原子claim和fence**

Source 和 Output 均使用 `FOR UPDATE SKIP LOCKED`，只领取 `READY` 或到期 `RETRY_WAIT`，更新为 RUNNING、`fence=fence+1`、90 秒租约。完成节点使用 `id + lease_owner + fence + RUNNING` 条件更新，影响 0 行时抛出 `StaleFenceException` 并回滚 Manifest、事件和状态。

- [ ] **Step 4: 运行仓储测试**

Run: `mvn -pl delivery-core -Dtest=PipelineRepositoryTest,PackageRepositoryTest test`
Expected: PASS，包括重复tick不重复claim、旧fence提交失败、DELIVERED不再claim。

### Task 4: Python repair 兼容节点与完整 Python 测试

**Files:**
- Create: `nodes/python/archive_repair.py`
- Create: `nodes/python/test_archive_repair.py`

- [ ] **Step 1: 编写 Python 行为测试**

```python
def test_repairs_stream_request_and_emits_mapping(self):
    result = run_repair(sample_tar, self.output, "2026-10-30", "june", 1)
    self.assertEqual(result["requestCount"], 4)
    self.assertEqual(result["mappings"][0]["newRequestId"], "req_20261030_000000001")
    with tarfile.open(result["artifacts"][0]["path"], "r:gz") as archive:
        metadata = json.load(archive.extractfile(
            "req_20261030_000000001/metadata.json"))
        self.assertEqual(metadata["providerCode"], "june")
        self.assertTrue(metadata["requestTime"].startswith("2026-10-30 "))
        self.assertIsNotNone(archive.extractfile(
            "req_20261030_000000001/biz_stream_response.jsonl"))
```

另建 SYNC 临时包，断言 stream 文件改为 `biz_response.json`；缺 `metadata.json` 时进程非零退出且不发布最终 Manifest；缺少原 requestTime 时按原脚本使用 `00:00:00`。

- [ ] **Step 2: 运行 Python 测试确认失败**

Run: `python3 -m unittest nodes/python/test_archive_repair.py -v`
Expected: FAIL，模块尚不存在。

- [ ] **Step 3: 实现单包 repair CLI**

接口：

```bash
python3 archive_repair.py \
  --source /data/work/source/batch_00002.tar.gz \
  --target /data/work/repair \
  --archive-date 2026-10-30 \
  --provider-code june \
  --start-number 1 \
  --manifest /data/work/repair/manifest.json
```

实现要求：

- 使用 `tarfile.open(..., "r:gz")` 和 `extractall(filter="data")`；Python 3.10 无 filter 参数时使用自定义安全成员校验。
- 顶级请求目录按名称稳定排序。
- 每个请求生成 `req_yyyyMMdd_九位编号`，并写原目录名、原 requestId、新 requestId 映射。
- 替换 `requestId/providerCode/requestTime`，保留原时间部分。
- 校准 STREAM/SYNC 响应文件名。
- 输出 tar 先写 `.tmp`，`os.replace` 原子发布。
- Manifest 包含 requestCount、mappings、artifact path/bytes/sha256；Manifest 同样原子发布。
- 不访问 Redis、PostgreSQL 或网络。

- [ ] **Step 4: 运行 Python 测试**

Run: `python3 -m unittest nodes/python/test_archive_repair.py -v`
Expected: 4 个以上测试全部 PASS，样本输出 requestCount=4。

### Task 5: 编号、repair Java 节点、血缘和分组

**Files:**
- Create: `service/NumberReservationService.java`
- Create: `node/PythonRepairNode.java`
- Create: `node/ManifestQualifyNode.java`
- Create: `node/OutputGroupNode.java`
- Create: `service/ManifestService.java`
- Test: `service/NumberReservationServiceTest.java`
- Test: `node/OutputGroupNodeTest.java`

- [ ] **Step 1: 写编号和血缘测试**

```java
NumberRange first = numbers.reserve(sourcePackageId, 4, "repair");
NumberRange retry = numbers.reserve(sourcePackageId, 4, "repair");
assertThat(retry).isEqualTo(first);
assertThat(first.start()).isEqualTo(1);
assertThat(first.count()).isEqualTo(4);
```

分组测试断言 1 个输出包对应 4 条 requestMapping，生产组 `assigned_count=1` 且 SEALED；重复 group 返回原输出包，不新增成员或编号。

- [ ] **Step 2: 实现 PG 编号预留**

`NumberReservationService` 在单事务中锁 `number_counter`，按 `sourcePackageId + requestKey` 查询已有 reservation；已存在则复用，不存在则分配 `[next_value,next_value+count)`。新 requestId 由 Python 按 startNumber 确定性生成，最终由 `request_mapping.new_request_id UNIQUE` 防重。

- [ ] **Step 3: 实现 PythonRepairNode**

先调用 Python inspect 模式或安全 tar 目录扫描得到请求数，再预留编号，使用 `ProcessBuilder` 参数数组启动 repair。消费 stdout JSON 结果，校验进程退出码、Manifest、artifact SHA 和 requestCount；把源成员、映射暂存和 repair Manifest 入库，源包状态推进 DOWNLOADED → REPAIRED。

- [ ] **Step 4: 实现 qualify 和 group**

qualify 逐请求检查必要文件、requestMode 响应、日期、provider 和九位编号。group 将 repair Manifest 中的请求映射关联到同一输出包，创建 artifact 和 `archive_date=delivery_date=2026-10-30`，计算 `cutoff_at=2026-10-31T02:00+08:00`，封组后组内序号不可变。

- [ ] **Step 5: 运行编号与分组测试**

Run: `mvn -pl delivery-core -Dtest=NumberReservationServiceTest,OutputGroupNodeTest test`
Expected: PASS，包括重试复用编号和重复分组幂等。

### Task 6: 本地三地传输适配器与原子重试

**Files:**
- Create: `node/LocalDownloadNode.java`
- Create: `node/LocalHzUploadNode.java`
- Create: `node/LocalSgVerifyNode.java`
- Create: `node/LocalSgDownloadNode.java`
- Create: `node/LocalPublishNode.java`
- Create: `node/DeliveryReceiptNode.java`
- Create: `service/FileTransferService.java`
- Test: `service/FileTransferServiceTest.java`
- Test: `node/LocalDeliveryNodesTest.java`

- [ ] **Step 1: 写传输测试**

```java
Path hz = transfer.copyVerified(artifact, hzRoot, relativePath);
Path sg = transfer.copyVerified(hz, sgRoot, relativePath);
assertThat(transfer.sha256(sg)).isEqualTo(manifestSha);
Path published = transfer.publishAtomically(sg, ftpRoot, relativePath);
assertThat(published).exists();
assertThat(published.resolveSibling(relativePath + ".partial")).doesNotExist();
```

另写 SHA 损坏测试，预期抛出 `ArtifactChecksumException`，目标状态不得推进。

- [ ] **Step 2: 实现文件传输**

所有复制写 `.partial`，流式计算 SHA，校验后 `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`；不支持 ATOMIC_MOVE 时只能同目录 fallback move，不跨文件系统直接改名。publish 先进入同文件系统 staging，再原子移动到客户目录。

- [ ] **Step 3: 实现六个状态节点**

节点状态依次为：

```text
PLANNED→DOWNLOADED→REPAIRED
GROUPED→HZ_UPLOADED→SG_VERIFIED→SG_DOWNLOADED→PUBLISHED→DELIVERED
```

HZ/SG/FTP 路径使用 `provider/archiveDate/batch_00002.tar.gz`。receipt 写入稳定 `receiptId=outputPackageId:artifactSha`，重试返回已有回执，不重复创建。

- [ ] **Step 4: 运行本地交付测试**

Run: `mvn -pl delivery-core -Dtest=FileTransferServiceTest,LocalDeliveryNodesTest test`
Expected: PASS，包括重复上传、SHA 不一致和重复回执。

### Task 7: Pipeline Engine 与 PowerJob BroadcastProcessor

**Files:**
- Create: `pipeline/NodeHandler.java`
- Create: `pipeline/NodeContext.java`
- Create: `pipeline/NodeResult.java`
- Create: `pipeline/PipelineEngine.java`
- Create: `service/MvpSeedService.java`
- Create: `delivery-worker/.../PackageTickProcessor.java`
- Create: `delivery-worker/.../MvpDebugRunner.java`
- Test: `delivery-worker/.../PackageTickProcessorTest.java`

- [ ] **Step 1: 定义统一节点接口**

```java
public interface NodeHandler {
    String type();
    NodeResult execute(NodeContext context) throws Exception;
}
public record NodeContext(long packageId, long runId, long fence,
                          NodeDefinition node, Path workRoot) {}
public record NodeResult(String nextNode, String businessStatus) {}
```

`PipelineEngine` 从 source/output 当前节点读取快照，按 `type` 找 NodeHandler；成功时以 fence 提交节点运行、事件、业务状态和下一 READY，终态设置 SUCCEEDED。

- [ ] **Step 2: 实现 MVP 种子服务**

启动时计算样本 SHA，以 `file://` URI 和 SHA 作为源版本，幂等插入 ingest_source、binding、PLANNED source_package。重复启动不重复发现。大组目标数为 1，便于一个样本封组。

- [ ] **Step 3: 实现真实 PowerJob Processor**

```java
@Component
@RequiredArgsConstructor
public class PackageTickProcessor implements BroadcastProcessor {
    private final PipelineEngine engine;
    @Override
    public ProcessResult process(TaskContext context) throws Exception {
        int completed = engine.executeNext("LINE-1", "local-worker");
        return new ProcessResult(true, "completedNodes=" + completed);
    }
}
```

`MvpDebugRunner` 仅在 `archive.debug-loop=true` 时直接调用同一个 Processor，每 200ms 一次，直到 output DELIVERED 或达到 maxTicks。生产模式由 PowerJob Server 周期调用，不启用调试循环。

- [ ] **Step 4: 写 Processor 测试**

使用真实 `TaskContext` 调用 `process`，断言返回成功且每调用一次只推进一个可执行节点。空队列返回 `completedNodes=0`，不把“没有工作”当失败。

- [ ] **Step 5: 运行 Processor 测试**

Run: `mvn -pl delivery-worker -Dtest=PackageTickProcessorTest test`
Expected: PASS。

### Task 8: 端到端、重试和截止流转测试

**Files:**
- Test: `delivery-worker/src/test/java/com/powerjob/archive/worker/MvpEndToEndTest.java`
- Test: `delivery-worker/src/test/java/com/powerjob/archive/worker/RetryEndToEndTest.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/service/DeliveryRolloverServiceTest.java`
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/service/DeliveryRolloverService.java`

- [ ] **Step 1: 写真实样本端到端测试**

使用 PostgreSQL Testcontainer、真实 Python 进程和临时三地目录，最多执行 30 次 Processor：

```java
while (!outputRepository.isDelivered(1L) && ticks++ < 30) {
    processor.process(new TaskContext());
}
assertThat(outputRepository.status(1L)).isEqualTo("DELIVERED");
assertThat(jdbc.queryForObject(
    "SELECT count(*) FROM archive_delivery.request_mapping WHERE output_package_id=1",
    Integer.class)).isEqualTo(4);
assertThat(jdbc.queryForObject(
    "SELECT count(DISTINCT new_request_id) FROM archive_delivery.request_mapping " +
    "WHERE output_package_id=1", Integer.class)).isEqualTo(4);
assertThat(publishedArtifact).exists();
```

逐条断言原 `req_20261027_*` 到新 `req_20261030_*` 的映射、requestTime、providerCode、STREAM/SYNC 文件、artifact SHA、节点事件顺序。

- [ ] **Step 2: 写重试测试**

在 HZ upload 首次执行注入一次 IO 异常：包保持 GROUPED + RETRY_WAIT，fence 增加后仅重试 hzUpload；最终 DELIVERED，repair 调用次数仍为 1，requestMapping 仍为 4，编号区间不变。

- [ ] **Step 3: 写截止流转测试**

将未完成包 cutoff 设置为过去，执行 rollover：deliveryDate 增加一天、rolloverCount=1、原 group/groupSeq/newPackageName 不变；已 DELIVERED 和 CANCELLED 不变；两个并发调用只流转一次。claim 查询中流转包排在普通包之前。

- [ ] **Step 4: 运行完整测试**

```bash
python3 -m unittest nodes/python/test_archive_repair.py -v
docker run --rm -v "$PWD":/workspace -v "$HOME/.m2":/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -w /workspace maven:3.9-eclipse-temurin-17 mvn test
```

Expected: Python 全 PASS，Maven 全模块 BUILD SUCCESS；MVP 日志出现 DELIVERED 且 requestCount=4。若环境不允许挂载 Docker socket，则在本机 Java 17/Maven 可用后执行 `mvn test`，不以降低测试范围替代。

### Task 9: 一键调试脚本、Worker 镜像和运行文档

**Files:**
- Create: `scripts/mvp-up.sh`
- Create: `scripts/mvp-test.sh`
- Create: `deploy/Dockerfile.worker`
- Create: `docs/mvp-runbook.md`

- [ ] **Step 1: 创建启动与测试脚本**

`mvp-up.sh` 执行 `docker compose -f deploy/docker-compose.yml up -d postgres`，等待 healthy，再构建并启动 Worker；挂载：

```text
old/sample → /samples:ro
archive-delivery-platform/nodes/python → /app/nodes/python:ro
archive-mvp-data → /data
```

`mvp-test.sh` 执行 Python 测试、Maven 测试和真实样本 debug-loop，任何一步失败即退出。

- [ ] **Step 2: 创建 Worker 镜像**

Maven 3.9 + Temurin 17 构建，Temurin 17 JRE + Python 3 运行，非 root 用户，入口 `java -jar /app/app.jar`。镜像不包含生产 Secret。

- [ ] **Step 3: 编写 runbook**

文档包含：

1. 环境前置：Docker、Docker Compose、可用 Docker daemon。
2. 数据库连接 `jdbc:postgresql://localhost:55432/archive_delivery` 和开发账号。
3. 初始化、Pipeline JSON 位置、样本位置和节点顺序。
4. 一键启动、单节点 debug-loop、PowerJob Server 接入参数。
5. 查询源包、输出包、4 条映射、artifact、node run 和 event 的 SQL。
6. 清理仅属于 MVP 的容器和数据卷命令，执行前明确提示会删除 MVP 本地数据。
7. 常见错误：Python 不存在、PG 未健康、源包 SHA 变化、旧fence、SHA 不一致、PowerJob Server 未连接。
8. 明确本地目录不代表真实 OSS/FTP/CRR 验收通过。

- [ ] **Step 4: 最终验收**

```bash
./scripts/mvp-test.sh
./scripts/mvp-up.sh
docker compose -f deploy/docker-compose.yml logs --tail=100 worker
```

Expected: 测试全绿，日志最终显示 `status=DELIVERED, outputPackageId=1, requestCount=4`，重复启动不产生第二个源包或输出包。

## 计划自审

- 覆盖用户要求：核心 ER 关系已补入设计文档；无前端；具体库配置、Pipeline 配置、Java/Python/PowerJob 代码、调试步骤和完整测试均有对应任务。
- 样本流程以 `批量修复工作流.md` 和 `fix_archive.py` 为准，杭州与新加坡使用 OSS 语义，FTP 是最终接收端，不误写成杭州 FTP 上传。
- 类型一致：NodeDefinition 的 `id/scope/type/config`、NodeContext 的 `packageId/runId/fence/node/workRoot` 在 Engine 和 Processor 任务中一致。
- 数据基数一致：源包 1:N 成员、源包 1:N 输出包、输出包 1:N 映射；生产组计数为输出包，不按请求数计数。
- 验证边界一致：真实 PG、真实 Python、真实 PowerJob Processor API、真实文件 SHA；外部 OSS/FTP/CRR 为本地适配器。
