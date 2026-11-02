# Archive Delivery Local Production MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在单台本地机器部署 PostgreSQL、PowerJob Server、三条生产线 Worker、业务 API 和看板，通过 OSS 地址提交交付 run，以真实杭州 OSS、CRR、新加坡 OSS 和 FTP 链路完成三线合计 10 个输出包的修复交付，并支持包级重试、指定节点续跑及两种跨日场景。

**Architecture:** 保留现有 `delivery-core` 的 PostgreSQL 状态机、Pipeline、血缘和 PowerJob Processor，新增 `delivery-server` 与最小 Vue 看板；生产 Pipeline v2 用真实 OSS/SSH 节点替换 `Local*Node`，本地目录适配器只保留给单元测试。PowerJob 只负责触发，PostgreSQL 是唯一业务事实源；所有计算在本机完成，只有源 OSS 下载、杭州 OSS 上传、新加坡 OSS 查询以及 FTP 机器下载/发布访问生产外部服务。

**Tech Stack:** Java 17、Spring Boot 2.7.18、PowerJob 5.1.7、PostgreSQL 16、MySQL 8、Flyway、JdbcTemplate、Aliyun OSS SDK 3.17.4、Python 3.11 标准库、Vue 3 + TypeScript + Vite + Element Plus、Docker Compose。

---

## 1. 第一性原理与已确认边界

### 1.1 不可破坏的原则

1. **外部执行至少一次，业务事实只提交一次。** 每个节点用本地/远端现状核验保证幂等，数据库用 `lease + fence` 防止过期 Worker 提交。
2. **有效输出 tar 是交付和重试原子。** 单包上传、下载、发布或回执失败，不重新下载和 repair 整个 run。
3. **先证明输入存在，再允许从中间节点启动。** `resume` 只能利用已有 Manifest、artifact 和外部路径；缺前置事实返回 409，不能靠改 `current_node` 伪造进度。
4. **CRR 等待不是失败。** 等待不消耗重试次数；网络/限流有界重试，权限/配置错误直接阻塞，达到阈值后停止并请求人工协助。
5. **真实链路才算真实验收。** 单元测试可用 fake；最终 10 包验收必须同时看到 HZ OSS、SG OSS、FTP 文件和本地 PG 的 DELIVERED 事实。
6. **不修改 PowerJob 核心、不写旧生产 PG/Redis、不执行旧流程中的清空目录命令。** 新流程使用本地 PG 编号和回执，FTP 只操作本次 run 的 staging 与明确的最终文件。

### 1.2 MVP 数量与并发

- 输入：`oss://junflow-ysai-source-hz-20260906/_derived/ysai-sftp-20261027/delivery_plan_0005/`。
- 按对象 Key 稳定排序，由规划器分配 `LINE-1/LINE-2/LINE-3 = 4/3/3`，合计 10 个有效输出包。
- 每条线 `download/repair/qualify/group` 同时最多 1 个源包。
- 三条线的输出节点共享**全局 10 个槽位**，不是每条线各 10 个。
- 生产组目标数固定为 4、3、3；流水线契约限定“一份合格源 tar 产出一个同名输出 tar”。缺必要协议文件的源包进入 BLOCKED，不产出空包；本 MVP 不做跨源合包和二次拆包。
- 默认业务时区 `Asia/Shanghai`，交付截止 02:00；测试通过可控时钟构造跨日，不等待真实午夜。

### 1.3 真实生产路径

```text
源 OSS
  → 本机 .partial 下载、SHA、原子 rename
  → 本机 repair/qualify/group
  → oss://hz-storage-20261013-6vmlk/june/{deliveryDate}-{legacyBatch}-{provider}/{packageName}
  → 等待 CRR；每个包按自身上传完成时间等待，默认 3 分钟
  → oss://sg-storage-20261013-6vmlk/june/{deliveryDate}-{legacyBatch}-{provider}/{packageName}
  → 本机 Worker 通过生产跳板 SSH 调用 FTP 机器固定脚本
  → /srv/maas-sftp/maasData/archive/{provider}/{deliveryDate}/{packageName}
  → 本地 PG 幂等 receipt + DELIVERED
```

`legacyBatch` 分别为 3、4、5，provider 分别为 `june/june-1/june-2`。杭州和新加坡使用各自生产 Endpoint 与凭据；FTP 使用密钥 SSH，文档不复制旧文档中的明文密码。最终文件名冲突且 SHA 不同时必须 BLOCKED，禁止覆盖其他 run 的数据。

### 1.4 作业时间与流水线策略规则

1. 提交接口只保留运行时输入 `ossUri/idempotencyKey/jobDate`，不接受任何节点或交付策略参数。`jobDate` 格式严格为 `yyyyMMdd`，缺省取本地 `Asia/Shanghai` 当前日期，测试显式传 `20261030`；该值不修改宿主机、PowerJob 或服务器系统时间。
2. 首个 `process_date/archive_date/delivery_date`、repair 的 `requestTime`、请求编号 namespace、OSS/FTP 路径和 `cutoff_at` 全部由 `jobDate` 派生，禁止节点重新读取系统日期造成同 run 日期不一致。
3. `crrWaitSeconds/autoRollover/ftpCleanBeforeDownload` 全部放在 PipelineVersion 根级 `config`，随 definition 快照传播到 source/output。测试 Pipeline v2 固定为 180、false、false；后续调参必须发布新 PipelineVersion 并切换数据源绑定，已开始 run 继续使用原快照。
4. CRR 从“整批上传后统一等待”改为**单包独立等待**：HZ 节点持久化 `hz_uploaded_at`，该包首次 SG 核验时间为 `hz_uploaded_at + 当前Pipeline快照.crrWaitSeconds`。先上传完成的包可先进入 SG/FTP，不被同批慢包阻塞。
5. 测试 Pipeline 的 `autoRollover=false` 防止真实当前时间越过 2026-10-31 02:00 后立即改写测试交付日；两个跨日 case 通过可控时钟、手工 rollover 及单独 `autoRollover=true` 的测试 Pipeline 验证。
6. `ftpCleanBeforeDownload=false` 是 MVP 的流水线安全约束，发布包含 true 的 Pipeline 时校验失败 `FTP_CLEAN_FORBIDDEN`；严禁恢复旧流程下载前清空 FTP 目录的行为，目录内历史数据必须原样保留。

测试提交参数固定为：

```json
{
  "jobDate":"20261030"
}
```

### 1.5 方案选择与不做事项

采用**演进现有骨架**：复用已存在的 Pipeline、状态机、编号、血缘和测试，替换外部适配器并增加控制面。直接包装旧 shell 虽快，但无法可靠支持包级断点、跨日和看板；一次实现主设计全部能力则超出 MVP。

本 MVP 不做 RBAC、通知渠道、可视化 Pipeline 编辑、任意 Shell、旧业务库回写、跨机器本地文件恢复和全量水平扩展。API 与看板仅绑定本地回环地址。

## 2. 文件结构

```text
archive-delivery-platform/
├── pom.xml                                      # 增加 delivery-server
├── delivery-core/
│   ├── src/main/java/com/powerjob/archive/core/
│   │   ├── config/{OssDeliveryProperties,FtpDeliveryProperties,WorkerSlotProperties}.java
│   │   ├── domain/DeliveryRun.java
│   │   ├── pipeline/PipelineEngine.java          # WAITING/BLOCKED、有界执行器
│   │   ├── repository/{DeliveryRunRepository,OperationAuditRepository}.java
│   │   ├── service/{RunSubmissionService,PlanningService,ResumeService,RetryPolicy}.java
│   │   ├── service/{OssTransferService,SshCommandRunner,RemotePathService}.java
│   │   └── node/{OssHzUploadNode,OssSgVerifyNode,SshSgDownloadNode,SshPublishNode}.java
│   ├── src/main/resources/
│   │   ├── db/migration/{V3__local_production_mvp.sql,V4__production_pipeline_v2.sql}
│   │   └── pipelines/archive-delivery-v2.json
│   └── src/test/java/com/powerjob/archive/core/
├── delivery-server/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/powerjob/archive/server/
│       │   ├── ArchiveServerApplication.java
│       │   ├── api/{RunController,PackageController,DashboardController}.java
│       │   └── service/DashboardQueryService.java
│       └── resources/application.yml
├── delivery-worker/src/main/java/com/powerjob/archive/worker/
│   ├── PackageTickProcessor.java
│   ├── DiscoveryProcessor.java
│   └── DeliveryRolloverProcessor.java
├── web/
│   ├── package.json
│   ├── vite.config.ts
│   └── src/{main.ts,App.vue,api.ts,types.ts,views/DashboardView.vue,components/PackageTimeline.vue}
├── nodes/
│   ├── python/{archive_repair.py,legacy_fix_archive.py,test_archive_repair.py}
│   └── shell/{sg_download.sh,publish_package.sh}
├── deploy/
│   ├── docker-compose.yml
│   ├── {Dockerfile.server,Dockerfile.worker}
│   ├── env.example
│   └── powerjob-mysql/01schema.sql
├── scripts/
│   ├── {mvn.sh,preflight.sh,bootstrap-powerjob.sh,mvp-up.sh,mvp-test.sh}
│   ├── {submit-run.sh,resume-run.sh,verify-delivery.sh}
└── docs/
    ├── install.md
    ├── deploy.md
    └── mvp-runbook.md
```

## 3. 实施任务

### Task 1: 固化基线、容器化构建和外部环境预检

**Files:**
- Create: `scripts/mvn.sh`
- Create: `scripts/preflight.sh`
- Modify: `deploy/env.example`
- Modify: `docs/mvp-runbook.md`

- [x] **Step 1: 记录当前回归基线**

Run:

```bash
cd archive-delivery-platform
python3 -m unittest nodes/python/test_archive_repair.py -v
./scripts/mvp-test.sh
```

Expected: 现有 Python 与 Maven 测试全绿；若有失败，先记录失败用例，不能把既有失败当成本 MVP 通过。

执行结果（2026-11-01）：Python 4/4 通过，Maven BUILD SUCCESS；真实 OSS 两个既有用例仍为 skipped，不计为生产通过。

- [x] **Step 2: 增加统一 Java 17 构建入口**

`scripts/mvn.sh` 使用现有 `deploy/Dockerfile.test` 构建 `archive-delivery-maven:test`，把 `$@` 原样传给 Maven，挂载项目、`.m2` 和 Docker socket；禁止默认添加 `-DskipTests`。

```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
docker build -t archive-delivery-maven:test -f "$ROOT/deploy/Dockerfile.test" "$ROOT/deploy"
docker run --rm -v "$ROOT":/workspace -v "$HOME/.m2":/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -w /workspace \
  archive-delivery-maven:test mvn "$@"
```

Run: `chmod +x scripts/mvn.sh && ./scripts/mvn.sh -q -DskipTests package`
Expected: `delivery-core`、`delivery-worker` BUILD SUCCESS。

执行结果：BUILD SUCCESS，提交 `b070f08`。

- [x] **Step 3: 编写有界预检脚本**

`preflight.sh` 依次检查 Docker、Compose、PG、源 OSS List/Head/1 字节 Range Get、HZ 测试前缀 Put/Head/Delete、SG Head、三条线 SSH 与 FTP 上 `ossutil64`。所有网络命令设置 10 秒连接超时，单项只试一次；HZ 预检对象放在 `.archive-mvp-preflight/{uuid}`，不触碰业务对象。

Expected: 输出逐项 `OK` 和脱敏地址。任一 403、SSH 不可达、跳板失败或 CRR 配置缺失即非零退出；停止实施真实 E2E，把具体失败项交给用户协助，不循环重跑。

执行结果：Docker、Compose、PG 通过；`deploy/.env` 缺源 bucket/prefix 显式配置和全部 HZ/SG 参数，脚本在 OSS/SSH 请求前按预期停止。真实 E2E 保持阻塞，等待环境补齐。

- [x] **Step 4: 补齐环境变量模板**

模板只写变量名和非敏感示例：`OSS_SOURCE_*`、`OSS_HZ_*`、`OSS_SG_*`、`FTP_LINE_1/2/3_SSH_*`、`FTP_LINE_1/2/3_JUMP_*`、`ARCHIVE_DB_*`、`POWERJOB_*`。私钥通过只读文件挂载，AK/SK 通过 `deploy/.env` 注入；运行 `git check-ignore deploy/.env` 确认返回 0。

- [x] **Step 5: 提交独立基线**

```bash
git add scripts/mvn.sh scripts/preflight.sh deploy/env.example docs/mvp-runbook.md
git commit -m "chore: add bounded production preflight"
```

### Task 2: 扩展 run、三线、容量和操作审计模型

**Files:**
- Create: `delivery-core/src/main/resources/db/migration/V3__local_production_mvp.sql`
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/domain/DeliveryRun.java`
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/repository/DeliveryRunRepository.java`
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/repository/OperationAuditRepository.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/repository/DeliveryRunRepositoryTest.java`

- [x] **Step 1: 先写迁移与幂等测试**

测试断言：三条线及 batch/provider 为 `(3,june)/(4,june-1)/(5,june-2)`；同一 `idempotencyKey` 和相同运行时参数只返回一个 run；`job_date/pipeline_version_id` 准确保存，策略字段不出现在 delivery_run；`source_package.run_id` 可定位 run；审计记录包含动作、原因和前后节点。

```java
LocalDate jobDate = LocalDate.of(2026, 10, 30);
long first = runs.create("oss://bucket/prefix/", "same-key", jobDate, 1L);
long second = runs.create("oss://bucket/prefix/", "same-key", jobDate, 1L);
assertThat(second).isEqualTo(first);
assertThat(jdbc.queryForObject("SELECT count(*) FROM archive_delivery.delivery_run", Long.class)).isEqualTo(1);
assertThat(jdbc.queryForObject("SELECT job_date FROM archive_delivery.delivery_run", LocalDate.class)).isEqualTo(jobDate);
assertThat(jdbc.queryForObject("SELECT pipeline_version_id FROM archive_delivery.delivery_run", Long.class)).isEqualTo(1L);
```

- [x] **Step 2: 运行测试确认失败**

Run: `./scripts/mvn.sh -pl delivery-core -Dtest=DeliveryRunRepositoryTest test`
Expected: FAIL，提示 `delivery_run` 不存在。

执行结果：V3 前测试按预期失败；同时修正了 `mvn.sh` 的 Testcontainers 网络参数，排除 Ryuk 环境噪声后确认数据库模型缺失。

- [x] **Step 3: 创建 V3 迁移**

核心 DDL：

```sql
ALTER TABLE production_line ADD COLUMN legacy_batch integer;
UPDATE production_line SET legacy_batch=id+2;
INSERT INTO production_line(line_code,provider_code,compute_host,ftp_host,target_root,legacy_batch)
VALUES ('LINE-3','june-2','8.136.128.108','8.222.228.188','june-2',5)
ON CONFLICT(line_code) DO UPDATE SET provider_code=EXCLUDED.provider_code,
  ftp_host=EXCLUDED.ftp_host,target_root=EXCLUDED.target_root,legacy_batch=5;
ALTER TABLE production_line ALTER COLUMN legacy_batch SET NOT NULL;
ALTER TABLE production_line ADD CONSTRAINT uk_line_legacy_batch UNIQUE(legacy_batch);

CREATE TABLE delivery_run (
  id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  run_code varchar(64) NOT NULL UNIQUE,
  oss_uri varchar(1280) NOT NULL,
  bucket_name varchar(128) NOT NULL,
  object_prefix varchar(1024) NOT NULL,
  idempotency_key varchar(128) NOT NULL UNIQUE,
  job_date date NOT NULL,
  pipeline_version_id bigint NOT NULL REFERENCES pipeline_version(id),
  status varchar(16) NOT NULL DEFAULT 'DISCOVERING',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE source_package ADD COLUMN run_id bigint REFERENCES delivery_run(id);
ALTER TABLE output_package ADD COLUMN hz_uploaded_at timestamptz;
CREATE INDEX idx_source_run ON source_package(run_id,status,discovered_at,id);

CREATE TABLE operation_audit (
  id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  run_id bigint NOT NULL REFERENCES delivery_run(id),
  output_package_id bigint REFERENCES output_package(id),
  action varchar(32) NOT NULL,
  reason varchar(512) NOT NULL,
  before_content jsonb,
  after_content jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_output_dashboard_run ON output_package(delivery_date,production_line_id,status,run_status);
```

V3 不修改或删除 V1/V2，既有测试继续可重复迁移。已有 5 包历史组不会被破坏性缩容；新组采用 4/3/3。

- [x] **Step 4: 实现 run 仓储和审计**

`DeliveryRunRepository.create` 在单事务中插入并用 `ON CONFLICT(idempotency_key) DO NOTHING`，冲突后按 key 查询；`run_code` 使用 `RUN-yyyyMMdd-{id}`，由同一事务更新。`OperationAuditRepository.append` 只接受 `RESUME/RETRY/ROLLOVER` 三种动作和非空 reason。

- [x] **Step 5: 运行迁移测试并提交**

Run: `./scripts/mvn.sh -pl delivery-core -Dtest=DeliveryRunRepositoryTest,LocalPostgresIntegrationTest test`
Expected: PASS，重复迁移不改变三线种子。

```bash
git add delivery-core && git commit -m "feat: add delivery run and third production line"
```

### Task 3: 发布真实生产 Pipeline v2 和分阶段配置

**Files:**
- Create: `delivery-core/src/main/resources/pipelines/archive-delivery-v2.json`
- Create: `delivery-core/src/main/resources/db/migration/V4__production_pipeline_v2.sql`
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/config/OssDeliveryProperties.java`
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/config/FtpDeliveryProperties.java`
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/config/WorkerSlotProperties.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/pipeline/PipelineDefinition.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/pipeline/ProductionPipelineTest.java`

- [x] **Step 1: 写 Pipeline 顺序和配置校验测试**

断言 v2 节点顺序为 `download,repair,qualify,group,hzUpload,sgVerify,sgDownload,publish,receipt`；外部节点类型必须依次为 `oss.hz-upload`、`oss.sg-verify`、`ssh.sg-download`、`ssh.publish`。根级 config 必须为 `crrWaitSeconds=180/autoRollover=false/ftpCleanBeforeDownload=false`，PipelineDefinition 增加 `Map<String,Object> config` 并提供类型化读取。发布校验允许 CRR 0～7200 秒和 autoRollover 布尔值，但 ftpCleanBeforeDownload=true 直接以 `FTP_CLEAN_FORBIDDEN` 拒绝。测试还断言生产 profile 不允许出现 `local.*` 和 `archive.debug-loop=true`。

执行结果：`ProductionPipelineTest` 与 `PipelineRepositoryTest` 全部通过，v1 缺省根级 config 保持兼容。

- [x] **Step 2: 创建不可变 v2 定义与迁移**

```json
{
  "pipelineCode":"archive-delivery",
  "version":2,
  "config":{
    "crrWaitSeconds":180,
    "autoRollover":false,
    "ftpCleanBeforeDownload":false
  },
  "nodes":[
    {"id":"download","scope":"SOURCE","type":"archive.download","config":{}},
    {"id":"repair","scope":"SOURCE","type":"python.repair","config":{"ruleSet":"fix-archive-legacy-v1"}},
    {"id":"qualify","scope":"SOURCE","type":"manifest.qualify","config":{}},
    {"id":"group","scope":"SOURCE","type":"output.group","config":{}},
    {"id":"hzUpload","scope":"OUTPUT","type":"oss.hz-upload","config":{"contentMd5":true}},
    {"id":"sgVerify","scope":"OUTPUT","type":"oss.sg-verify","config":{"pollSeconds":30,"maxWaitSeconds":7200}},
    {"id":"sgDownload","scope":"OUTPUT","type":"ssh.sg-download","config":{"commandTimeoutSeconds":900}},
    {"id":"publish","scope":"OUTPUT","type":"ssh.publish","config":{"commandTimeoutSeconds":60}},
    {"id":"receipt","scope":"OUTPUT","type":"delivery.receipt","config":{}}
  ]
}
```

V4 插入 `pipeline_version` 并用 definition 实际 SHA-256；v1 保留给本地回归测试，新 run 默认绑定 v2。

- [x] **Step 3: 增加类型化配置**

`OssDeliveryProperties` 分别保存 source/hz/sg 的 endpoint、bucket、AK、SK；`FtpDeliveryProperties` 以 lineId 保存 SSH host/user/key/jump 和远端固定脚本路径；`WorkerSlotProperties` 包含 `sourceSlotsPerLine=1`、`outputSlotsGlobal=10`、`maxAttempts=5`。配置启动时校验非空、bucket 不允许包含 `oss://`、remote root 必须等于约定生产根目录。

- [x] **Step 4: 验证并提交**

执行结果：V4 在全新 PostgreSQL 上迁移成功，提交 `8d6e97e`。

Run: `./scripts/mvn.sh -pl delivery-core -Dtest=ProductionPipelineTest,PipelineRepositoryTest test`
Expected: PASS，v1 本地测试仍使用原节点，v2 无本地适配器。

```bash
git add delivery-core && git commit -m "feat: define real delivery pipeline v2"
```

### Task 4: 复用历史 fix_archive 数据规则并做黄金对比

**Files:**
- Create: `nodes/python/legacy_fix_archive.py`
- Modify: `nodes/python/archive_repair.py`
- Modify: `nodes/python/test_archive_repair.py`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/node/PythonRepairNode.java`
- Test: `delivery-worker/src/test/java/com/powerjob/archive/worker/RepairGoldenEndToEndTest.java`

- [x] **Step 1: 写历史规则黄金测试**

测试输入同时覆盖 STREAM、SYNC、缺 requestTime、原目录非稳定顺序。逐条断言：

```python
self.assertEqual(metadata["requestId"], new_request_id)
self.assertEqual(metadata["providerCode"], provider)
self.assertEqual(metadata["requestTime"], "2026-11-01 " + original_time)
self.assertEqual(stream_files, ["biz_stream_response.jsonl"])
self.assertEqual(sync_files, ["biz_response.json"])
self.assertLessEqual(artifact.stat().st_size, 100 * 1024 * 1024)
```

另断言缺 `metadata.json/biz_request.json/响应文件` 时非零退出、最终 tar 与 Manifest 均不存在；重跑复用同一 `startNumber` 时 SHA 和映射一致。

- [x] **Step 2: 运行测试确认当前边界差异**

Run: `python3 -m unittest nodes/python/test_archive_repair.py -v`
Expected: 新增的大小或确定性用例失败，失败信息指向具体规则。

执行结果：确定性用例如期因两次 gzip SHA 不同失败，其余规则测试通过。

- [x] **Step 3: 抽取历史数据面函数**

从 `old/aistore-platform/tasks/3.fix_archive/fix_archive.py` 原样迁移 `fix_request` 的字段替换与响应文件逻辑、`verify_output` 的协议判断到 `legacy_fix_archive.py`；不迁移 `MiniRedis`、命令行分片和删除源文件逻辑。`archive_repair.py` 调用该共享函数，PG 继续负责编号。

修复打包规则：源 tar 对应一个同名输出 tar；超过 `100 * 1024 * 1024` 或协议验证失败时删除 `.tmp` 并抛出异常，不能静默拆成未建模的多个包。本流程不调用 `package_to_delivery_without_filter.py`，避免把已经是 req 目录的归档再次转换。`PythonRepairNode` 通过 source→run 读取 `job_date` 并转换成 `yyyy-MM-dd` 传给 `--archive-date`，编号 namespace 同样取该日期，不再读取全局 `archive.archiveDate`。

- [x] **Step 4: 运行 Python 与 Java 黄金测试**

Run:

```bash
python3 -m unittest nodes/python/test_archive_repair.py -v
./scripts/mvn.sh -pl delivery-worker -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=RepairGoldenEndToEndTest test
```

Expected: PASS；输出 tar 内 metadata、响应文件、映射与历史 `fix_archive.py` 规则一致，编号只由 PG reservation 决定。

执行结果：Python 6/6、Java golden 均通过；相同 startNumber 的映射和 SHA 完全一致。

- [x] **Step 5: 提交**

执行结果：提交 `f1bfe10`。

```bash
git add nodes delivery-worker/src/test && git commit -m "fix: preserve legacy archive repair semantics"
```

### Task 5: 提供 OSS 地址提交、发现和规划 API

**Files:**
- Create: `delivery-server/pom.xml`
- Create: `delivery-server/src/main/java/com/powerjob/archive/server/ArchiveServerApplication.java`
- Create: `delivery-server/src/main/java/com/powerjob/archive/server/api/RunController.java`
- Create: `delivery-server/src/main/resources/application.yml`
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/service/RunSubmissionService.java`
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/service/PlanningService.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/service/OssDiscoveryService.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/repository/SourcePackageRepository.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/node/OutputGroupNode.java`
- Modify: `pom.xml`
- Test: `delivery-server/src/test/java/com/powerjob/archive/server/api/RunControllerTest.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/service/PlanningServiceTest.java`

- [x] **Step 1: 写提交与 OSS URI 校验测试**

请求：

```json
{
  "ossUri":"oss://junflow-ysai-source-hz-20260906/_derived/ysai-sftp-20261027/delivery_plan_0005/",
  "idempotencyKey":"delivery-plan-0005-local-v1",
  "jobDate":"20261030"
}
```

断言 HTTP 202 和 runId；完全相同的重复请求返回同一 runId；同 idempotencyKey 但 OSS URI/jobDate 不同返回 409；非 `oss://`、缺 bucket、前缀包含 `..` 或非法日期返回 400。Jackson 开启 `FAIL_ON_UNKNOWN_PROPERTIES`，提交 crrWaitSeconds、autoRollover、ftpCleanBeforeDownload 或其他策略字段均返回 400，防止调用方绕过 Pipeline 配置。jobDate 缺省为当前业务日期；新 run 只接受默认 `download` 起点，中间节点通过 Task 9 的 resume API。

- [x] **Step 2: 实现 delivery-server 与提交服务**

根 POM 增加 `delivery-server`；server 依赖 core 和 `spring-boot-starter-web`，不引入 PowerJob Worker。Controller：

```java
public record SubmitRunRequest(
        @NotBlank String ossUri,
        @NotBlank String idempotencyKey,
        @Pattern(regexp="^\\d{8}$") String jobDate) {}
public record RunView(long runId, String status, String jobDate, int pipelineVersion) {}

@PostMapping("/api/v1/runs")
@ResponseStatus(HttpStatus.ACCEPTED)
public RunView submit(@Validated @RequestBody SubmitRunRequest request) {
    return submission.submit(request);
}
```

API 只写本地 PG，不在 HTTP 请求中列举 OSS。`jobDate` 缺省为 `LocalDate.now(ZoneId.of("Asia/Shanghai"))`，显式值用 `uuuuMMdd + ResolverStyle.STRICT` 解析。RunSubmissionService 根据数据源当前绑定选择 Pipeline v2，将完整 definition（含根级策略 config）固化到 source/output 快照；提交参数不复制策略字段，后续节点只读取各自 Pipeline 快照。

- [x] **Step 3: 把发现改为 DISCOVERED 快照**

`OssDiscoveryService.discover(runId)` 分页列举指定前缀，过滤非空 `.tar.gz`，每页按 Key 排序，以 `bucket+key+ETag/versionId` 幂等插入 `DISCOVERED + READY`；此时 `production_line_id/process_date` 均为空。游标与单页写入同事务提交，不在内存中一次保存全量对象。

- [x] **Step 4: 实现两段准入的 MVP 版本**

`PlanningService.plan(runId, businessDate)` 只领取尚未接收的最老 `DISCOVERED` 包，首次业务日期直接取 run 的 `job_date`，跨日测试由可控时钟推进逻辑日期。对每条线按 `LINE-1→2→3` 填充当日剩余配额 4/3/3，更新为 `PLANNED`、写 line 和 `process_date`；配额满后剩余包保持 DISCOVERED。repair 合格后才由 group 真正占输出组名额；PLANNED 但尚未分组的源包占用一个准入预留，BLOCKED 不会自动释放预留，防止恢复重试时超配。`OutputGroupNode` 以 `source_package.process_date` 写 archive/delivery date 和 cutoff，不再读取系统今日。

规划容量必须同时扣除历史未交付输出包、当日已占输出名额和当日在途源包预留：

```sql
SELECT
  (SELECT count(*) FROM output_package p
   WHERE p.production_line_id=:lineId AND p.run_status<>'CANCELLED'
     AND (p.delivery_date=:businessDate
          OR (p.delivery_date<:businessDate AND p.status<>'DELIVERED')))
 +
  (SELECT count(*) FROM source_package s
   WHERE s.production_line_id=:lineId AND s.process_date<=:businessDate
     AND s.status IN ('PLANNED','DOWNLOADED','REPAIRED') AND s.run_status<>'CANCELLED'
     AND NOT EXISTS (SELECT 1 FROM output_package o WHERE o.source_package_id=s.id))
  AS reserved_count;
```

若 `reserved_count` 已达 4/3/3，当日新准入为 0；这样既能在 rollover 前后持续预留容量，也不会把在途源包重复准入。对规划使用 PostgreSQL 事务级 advisory lock，避免并发 Tick 超配。

- [x] **Step 5: 运行 API、发现和规划测试**

Run: `./scripts/mvn.sh -pl delivery-server -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=RunControllerTest,PlanningServiceTest,OssDiscoveryIntegrationTest test`
Expected: 无凭据的纯单元/集成测试 PASS；真实 OSS 测试仅在凭据存在时执行，测试报告不得把 skipped 写成真实通过。

执行结果：API/Planning 全部通过，真实 OSS 测试因未注入完整生产配置而 skipped；额外三线 4/3/3 本地 E2E 通过，10 包全部 DELIVERED。

- [x] **Step 6: 提交**

执行结果：提交 `6dc1d86`。

```bash
git add pom.xml delivery-core delivery-server && git commit -m "feat: submit and plan delivery runs by oss uri"
```

### Task 6: 实现每线单源包执行和真实源 OSS 断点下载

**Files:**
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/service/OssSourceDownloader.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/repository/SourcePackageRepository.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/pipeline/PipelineEngine.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/service/OssSourceDownloaderTest.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/repository/SourceClaimConcurrencyTest.java`

- [x] **Step 1: 写下载恢复和单线并发测试**

覆盖：已存在完整文件且 size/SHA 匹配时不发 Get；只有 `.partial` 时重新下载；Get 中断不进入 DOWNLOADED；两个线程同时 claim 同线时最多一个源包 RUNNING，其他线不受影响。

- [x] **Step 2: 实现有界流式下载**

下载前 Head 并比较 size/ETag；写入同目录 `{package}.partial`，流式更新 SHA-256，完成后 `fsync`、核对 size 并原子 rename。`DownloadNode` 提交 `localPath/sourceSha256/objectVersion`，重试优先验证已有最终文件，不重复消耗 OSS 流量。

- [x] **Step 3: 在 PG 强制每线一个源执行槽**

claim 候选增加 `NOT EXISTS (SELECT 1 FROM source_package r WHERE r.production_line_id=p.production_line_id AND r.run_status='RUNNING' AND r.lease_until>=now())`，claim 与更新保持同事务和 `FOR UPDATE SKIP LOCKED`。本地 source executor 每线也只创建一个线程，PG 约束是最终防重依据。

执行补充：增加每线事务级 advisory lock，避免并发快照下同时领取同线不同包。

- [x] **Step 4: 验证并提交**

执行结果：下载 3 项与 PG 并发测试全部通过，提交 `7967661`。

Run: `./scripts/mvn.sh -pl delivery-core -Dtest=OssSourceDownloaderTest,SourceClaimConcurrencyTest test`
Expected: PASS；LINE-1 下载时 LINE-2/3 可各处理一个，但同线不会出现第二个 RUNNING 源包。

```bash
git add delivery-core && git commit -m "feat: bound source execution and resume oss downloads"
```

### Task 7: 实现真实杭州 OSS 包级幂等上传

**Files:**
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/service/OssTransferService.java`
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/service/RemotePathService.java`
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/service/RemoteConflictException.java`
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/node/OssHzUploadNode.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/node/OssHzUploadNodeTest.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/service/OssTransferIntegrationTest.java`

- [x] **Step 1: 写幂等与冲突测试**

用 fake OSS client 验证：目标不存在时上传；目标 size 和 SHA metadata 相同时不重复 Put；同 key 不同 SHA 抛 `RemoteConflictException` 并 BLOCKED；网络异常保持 GROUPED + RETRY_WAIT，repair 调用次数不变。

- [x] **Step 2: 实现路径与上传协议**

`RemotePathService` 统一生成 Task 1.3 的生产 key，路径日期取 run 的 `job_date`，拒绝绝对路径、`..` 和非 `batch_\d{5}.tar.gz` 文件名。上传使用 OSS SDK `PutObjectRequest`，设置 Content-MD5、`x-oss-meta-sha256`、runId 和 packageId metadata；上传后 Head 核对 size 与 SHA metadata，再保存 `hz_path`、artifact path 和首次 `hz_uploaded_at`。幂等跳过已上传包时保留首次 `hz_uploaded_at`，不能因重试推迟 CRR 到期时间。

OSS 不做本地目录式 rename；最终 key 本身就是幂等边界。Content-MD5 保证本次传输完整，SHA metadata 用于三地一致性核验，不能用 multipart ETag 冒充 SHA-256。

- [x] **Step 3: 写真实小对象集成测试**

测试只在 `HZ_OSS_TEST_PREFIX=.archive-mvp-it/{runId}/` 下 Put/Head/Delete 一个固定小文件，断言第二次 Put 被跳过。没有 `ARCHIVE_TEST_REAL_OSS=true` 时跳过并在报告中明确“未执行真实 OSS 写测试”。

执行结果：真实 OSS 写测试未执行（环境未开启），fake 与 RemotePath 测试全部通过。

- [x] **Step 4: 验证并提交**

执行结果：提交 `06e7dc1`。

Run: `./scripts/mvn.sh -pl delivery-core -Dtest=OssHzUploadNodeTest,OssTransferIntegrationTest test`
Expected: fake 测试全 PASS；有凭据时真实测试 2 次执行只有 1 次 Put。

```bash
git add delivery-core && git commit -m "feat: upload output packages to production hz oss"
```

### Task 8: 实现 CRR 等待和新加坡 OSS 核验

**Files:**
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/node/OssSgVerifyNode.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/pipeline/NodeResult.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/pipeline/PipelineEngine.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/repository/OutputPackageRepository.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/node/OssSgVerifyNodeTest.java`

- [x] **Step 1: 写等待、成功和超时测试**

可控时钟下断言：测试 Pipeline v2 的 `config.crrWaitSeconds=180`，包在上传后第 179 秒返回 WAITING、第 180 秒允许 SG Head；另发布配置为 0 和 600 的测试 PipelineVersion，分别验证立即可核验和 600 秒等待，提交 API 全程不接受该参数。构造两个不同 `hz_uploaded_at` 的包，断言它们独立到期、互不等整批。SG 对象不存在返回 WAITING 且 `retry_count` 不增加；size/SHA metadata 一致推进 SG_VERIFIED；SHA 不同直接 BLOCKED；超过 7200 秒进入 BLOCKED 并给出 `CRR_WAIT_TIMEOUT`，不无限轮询。

- [x] **Step 2: 扩展节点结果与 claim**

```java
public enum ResultKind { SUCCEEDED, WAITING }

public record NodeResult(String nextNode, String businessStatus, JsonNode manifest,
                         ResultKind kind, Duration nextPoll) {
    public static NodeResult waiting(String node, JsonNode manifest, Duration poll) {
        return new NodeResult(node, null, manifest, ResultKind.WAITING, poll);
    }
}
```

保留原 `NodeResult.of` 作为成功工厂。WAITING 保持当前业务状态和 `current_node=sgVerify`，设置 `run_status=WAITING + next_retry_at`；claim 只领取 `next_retry_at<=now()` 的 WAITING。WAITING 不占用执行线程和全局输出槽。

- [x] **Step 3: 实现 SG Head 核验**

按 HZ Manifest 中记录的最终 key 查询 SG，不根据“包数量相同”替代逐包核验。比较 size 与 SHA metadata；保存 `sg_source_path` 和 artifact `sg_path`。节点从 output 的 `definition_snapshot.config.crrWaitSeconds` 读取不可变等待时长，以当前包 `hz_uploaded_at` 计算首次核验时间；30 秒轮询和 2 小时上限从当前节点 config 读取。resume/retry 仍使用原 Pipeline 快照，不能通过提交接口或修改新 Pipeline 改变在途包策略。

- [x] **Step 4: 验证并提交**

执行结果：CRR/Waiting 测试通过；修复 Spring 多构造器选择及旧重试测试竞态后，RetryEndToEndTest 通过，提交 `7080483`。

Run: `./scripts/mvn.sh -pl delivery-worker -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=OssSgVerifyNodeTest,RetryEndToEndTest test`
Expected: WAITING 测试无失败重试计数，现有重试语义不回归。

```bash
git add delivery-core && git commit -m "feat: verify cross region replication per package"
```

### Task 9: 实现 FTP 生产下载、原子发布和指定节点续跑

**Files:**
- Create: `nodes/shell/sg_download.sh`
- Create: `nodes/shell/publish_package.sh`
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/service/SshCommandRunner.java`
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/node/SshSgDownloadNode.java`
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/node/SshPublishNode.java`
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/service/ResumeService.java`
- Modify: `delivery-server/src/main/java/com/powerjob/archive/server/api/RunController.java`
- Create: `delivery-server/src/main/java/com/powerjob/archive/server/api/PackageController.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/service/ResumeServiceTest.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/service/SshCommandRunnerTest.java`
- Test: `delivery-server/src/test/java/com/powerjob/archive/server/api/PackageControllerTest.java`

- [x] **Step 1: 写 SSH 安全和续跑前置校验测试**

`SshCommandRunnerTest` 断言 host、key、provider、date、packageName 全部白名单校验，参数不能以 `-` 开头，命令使用 argv，超时后销毁进程，FTP 下载命令显式携带 Pipeline 快照中的 `config.ftpCleanBeforeDownload=false`。远端脚本测试预置 `history/keep.tar.gz` 和上一批次文件，执行下载/发布后断言其内容与 mtime 均不变；再静态禁止递归删除、通配符删除和 `rsync --delete`。`ResumeServiceTest` 覆盖：

```text
HZ_UPLOADED + 有效hz_path + 相同SHA → 允许从sgVerify继续
SG_VERIFIED + 有效sg_path → 允许从sgDownload继续
PUBLISHED + 有效published_path → 只允许receipt
GROUPED但无repair Manifest → 从hzUpload返回409
请求回退到已完成hzUpload → 返回409，防止重复流量
```

- [x] **Step 2: 实现固定远端脚本**

`sg_download.sh` 参数依次为 `sgUri expectedSha stagingDir finalName cleanBeforeDownload`：第五个参数不等于 `false` 时在执行任何目录或网络操作前退出 64；随后只创建本次 run 专属 staging，使用 FTP 机器已有 `ossutil64 cp` 下载到 `{finalName}.partial`，用 `sha256sum` 校验后原子 rename。`publish_package.sh` 只在同文件系统内把已核验 staging 文件移动到最终目录，不使用覆盖式目录同步。最终文件存在且 SHA 相同直接成功，不同则退出非零。两个脚本都禁止 `rm -rf ./*`、其他递归/通配符删除和清空历史目录逻辑。

安装时脚本部署到 FTP `/opt/archive-delivery/bin/`，权限 0755；Worker 只能调用该固定路径。

- [x] **Step 3: 实现本机 SSH 节点**

`SshCommandRunner` 使用本机 `ssh -o BatchMode=yes -o ConnectTimeout=10`，跳板通过受控 ProxyCommand 连接；目标命令为固定脚本加已校验参数。`SshSgDownloadNode` 从 output 的 `definition_snapshot.config.ftpCleanBeforeDownload` 读取安全策略并作为第五个参数显式传递，随后保存远端 staging path；`SshPublishNode` 保存最终 path。每次调用有 15 分钟/60 秒超时，密码不进入 argv 或日志。

- [x] **Step 4: 实现 resume 与 retry API**

```text
POST /api/v1/runs/{runId}/resume
{"fromNode":"sgDownload","packageIds":[101,102],"reason":"CRR已完成，从FTP下载继续"}

POST /api/v1/packages/{packageId}/retry
{"expectedFence":7,"reason":"FTP网络恢复"}
```

resume 校验 run/package 归属、节点顺序、前置 Manifest 和路径，逐包加审计后设置 READY；packageIds 为空表示 run 内所有能从该节点安全续跑的包。retry 只允许 RETRY_WAIT/BLOCKED 的当前失败节点，不清空已完成节点，fence 条件不一致返回 409。

- [x] **Step 5: 验证并提交**

执行结果：bash 语法、SSH argv/超时、历史文件不变、ResumeService 与 PackageController 测试全部通过，提交 `133767a`。

Run:

```bash
bash -n nodes/shell/sg_download.sh nodes/shell/publish_package.sh
./scripts/mvn.sh -pl delivery-server -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ResumeServiceTest,SshCommandRunnerTest,PackageControllerTest test
```

Expected: PASS；无法证明前置事实的请求均为 409，不触发 SSH/OSS。

```bash
git add nodes delivery-core delivery-server && git commit -m "feat: resume real ftp delivery from verified nodes"
```

### Task 10: 实现全局 10 输出槽、有界重试和本地回执

**Files:**
- Create: `delivery-core/src/main/java/com/powerjob/archive/core/service/RetryPolicy.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/repository/OutputPackageRepository.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/pipeline/PipelineEngine.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/node/DeliveryReceiptNode.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/repository/OutputClaimConcurrencyTest.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/service/RetryPolicyTest.java`

- [x] **Step 1: 写全局并发和重试上限测试**

用三个 Worker 线程和超过 10 个 READY 输出包并发 claim，断言全局 RUNNING 不超过 10，且没有重复 packageId。网络错误依次退避 5、10、20、40、80 秒，第 5 次失败进入 BLOCKED；权限错误第一次即 BLOCKED；WAITING 不增加 attempt 失败计数。

- [x] **Step 2: 用数据库实现全局槽位**

输出 claim 事务先 `SELECT pg_advisory_xact_lock(:outputCapacityLockKey)`，再统计有效 RUNNING 数，按 `10-running` 限制候选数量；候选 SQL 保持：

```sql
ORDER BY (rollover_count>0) DESC,delivery_date,archive_date,production_group_id,group_seq
FOR UPDATE SKIP LOCKED
```

每个 claim 增加 fence 并绑定 lineId，禁止当前 `OutputPackageRepository.claimNext` 的跨线无约束领取。Worker 本地使用有界线程池执行，PowerJob `process` 返回已提交数量，不在调度线程跑完整上传。

- [x] **Step 3: 统一错误分类与本地 receipt**

`RetryPolicy` 将 `SocketTimeoutException/IOException/429/5xx` 映射为可重试，将 `AccessDenied/InvalidConfiguration/RemoteConflict/ProtocolValidation` 映射为 BLOCKED。`DeliveryReceiptNode` 以 `outputPackageId:artifactSha` 为幂等键，在本地 PG 保存 receiptId 和首次 delivered_at；不写旧生产 PG。SSH 超时/网络失败进入有界重试，远端 SHA 冲突立即 BLOCKED。

- [x] **Step 4: 验证并提交**

执行结果：OutputClaimConcurrencyTest、RetryPolicyTest、完整 10 包和 Retry/Ssh 回归全部通过，提交 `2877355`。

Run: `./scripts/mvn.sh -pl delivery-worker -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=OutputClaimConcurrencyTest,RetryPolicyTest,RetryEndToEndTest test`
Expected: 全局峰值等于 10，失败只重试当前包当前节点，最终无重复 receipt。

```bash
git add delivery-core && git commit -m "feat: bound output concurrency and package retries"
```

### Task 11: 完成两个跨日 case 和最高优先级

**Files:**
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/service/DeliveryRolloverService.java`
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/service/PlanningService.java`
- Modify: `delivery-server/src/main/java/com/powerjob/archive/server/api/RunController.java`
- Create: `delivery-worker/src/main/java/com/powerjob/archive/worker/DeliveryRolloverProcessor.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/service/BacklogNextDayIntegrationTest.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/service/DeliveryRolloverServiceTest.java`

- [x] **Step 1: 写 Case A——今日未接收，次日最老 backlog 优先**

测试第一天发现 12 个源包，容量仅 10：10 个 PLANNED，2 个保持 DISCOVERED 且 process_date 为空；让前 10 个完成分组，释放源包预留但保留当日输出名额。第二天加入新对象后规划，断言昨日 2 个先于新对象获得容量，process_date 为第二天，源包身份和 Pipeline 快照不变，不重复发现。另保留一个未完成的 PLANNED 包跨午夜，断言其 process_date 不变且继续占用准入预留。

- [x] **Step 2: 写 Case B——交付截止未完成的包重置次日**

以 `jobDate=20261030` 构造 yesterday 的 5 种包：READY、RUNNING、WAITING、BLOCKED、DELIVERED。先断言使用 v2 `config.autoRollover=false` 的历史测试 run 不被真实当前时间自动改写；通过手工入口执行 rollover 后，断言前四种 `delivery_date+1/cutoff_at+1/rollover_count+1`，DELIVERED 不变；RUNNING 不中断，BLOCKED 仍 BLOCKED。所有包的 groupId/groupSeq/newPackageName/requestMapping/current_node 不变。另发布 `config.autoRollover=true` 的测试 PipelineVersion，验证自动 Processor 能按截止点执行同一逻辑。

再用 READY 普通包和流转包竞争，断言流转包先 claim；PUBLISHED 包流转后只执行 receipt，已完成的 HZ/SG/FTP 节点 run 数不增加。两个并发 rollover 和停机补扫在同一截止周期只生效一次。

- [x] **Step 3: 实现 rollover 事务和人工同语义入口**

保留主设计的 `FOR UPDATE OF p SKIP LOCKED` 批量更新，并在同事务写 `DELIVERY_ROLLED_OVER` event。新增：

```text
POST /api/v1/runs/{runId}/rollover
{"expectedDeliveryDate":"2026-10-30","reason":"历史作业日复盘，转入2026-10-31"}
```

入口调用同一 service，只允许未完成包从 expectedDeliveryDate 前进一天，写 operation_audit；自动 Processor 每分钟只处理 output→source 的 `definition_snapshot->'config'->>'autoRollover'='true'` 的到期包，人工入口不受该开关限制，用于历史测试和当日复盘，不另写一套状态逻辑。

- [x] **Step 4: 运行跨日测试**

执行结果：BacklogNextDayIntegrationTest、DeliveryRolloverServiceTest、完整 10 包和 RunControllerTest 全部通过。

- [x] **Step 5: 提交**

执行结果：提交 `6e668fd`。

```bash
git add delivery-core delivery-server delivery-worker && git commit -m "feat: prioritize next day backlog and rolled delivery"
```

### Task 12: 一键部署本地 PowerJob Server 与三条 Worker

**Files:**
- Modify: `deploy/docker-compose.yml`
- Create: `deploy/Dockerfile.server`
- Modify: `deploy/Dockerfile.worker`
- Create: `deploy/powerjob-mysql/01schema.sql`
- Create: `scripts/bootstrap-powerjob.sh`
- Create: `delivery-worker/src/main/java/com/powerjob/archive/worker/DiscoveryProcessor.java`
- Modify: `delivery-worker/src/main/java/com/powerjob/archive/worker/PackageTickProcessor.java`
- Modify: `delivery-worker/src/main/resources/application.yml`
- Modify: `scripts/mvp-up.sh`

- [x] **Step 1: 固定 Compose 拓扑与端口**

服务为 `postgres:16-alpine`、`mysql:8.0.36`、`powerjob/powerjob-server:v5.1.7`、`archive-server`、`worker-line-1/2/3`。PowerJob MySQL 使用从本仓库 `others/sql/01schema.sql` 复制的固定初始化脚本，不使用 latest。业务 PG、PowerJob MySQL 和 work 数据分别持久卷；server/API 端口只发布到 `127.0.0.1`。

三个 Worker 使用同一 PowerJob app `archive-delivery-mvp`，分别设置 `ARCHIVE_LINE_ID=1/2/3`、唯一 workerId 和容器内 27777；`POWERJOB_SERVER_ADDRESS=powerjob-server:7700`，生产模式 `ARCHIVE_DEBUG_LOOP=false`。

- [x] **Step 2: 实现三个真实 Processor**

```text
archive-discovery          STANDALONE  每30秒  com.powerjob.archive.worker.DiscoveryProcessor
archive-package-tick       BROADCAST   每10秒  com.powerjob.archive.worker.PackageTickProcessor
archive-delivery-rollover  STANDALONE  每60秒  com.powerjob.archive.worker.DeliveryRolloverProcessor
```

`PackageTickProcessor` 每次只向有界 executor 提交空闲槽可接受的任务，返回提交数；discovery 按 DISCOVERING run 工作；rollover 调同一 service。所有 Processor 的“空调用成功”不等于业务 DELIVERED。

- [x] **Step 3: 自动创建本地 PowerJob 任务**

`bootstrap-powerjob.sh` 等 Server healthy 后创建 app，并通过 PowerJob 5.1.7 `/job/save` 保存上述三项任务，`processorType=BUILT_IN`、`executeType` 分别 `STANDALONE/BROADCAST`、`instanceRetryNum=0`、`taskRetryNum=0`，业务重试只由 PG 管理。脚本按 jobName 查询后更新，重复执行不重复创建；认证信息只从环境读取。

- [x] **Step 4: 做真实调度冒烟**

Run:

```bash
./scripts/mvp-up.sh
docker compose -f deploy/docker-compose.yml ps
./scripts/bootstrap-powerjob.sh
docker compose -f deploy/docker-compose.yml logs --tail=100 powerjob-server worker-line-1 worker-line-2 worker-line-3
```

Expected: 7 个服务 healthy/running，控制台显示 3 个 Worker；三项任务由 PowerJob 实际产生 instance 日志，debug loop 未启动，PG 无重复 claim。

执行记录：PowerJob 5.1.7 三项任务已实际产生 instance，三个 Worker 持续上报健康并执行发现、规划、下载、修复、上传、SG 核验和 SSH 节点，未使用 debug loop。容器监听、共享 `/data/work` 非 root 写权限及目标/跳板机严格主机校验的真实问题均已修复；Pipeline v4 保存每线目标与跳板机公钥，真实 OSS 和 SSH 预检通过。10 包最终状态由 Task 15 单独验收，不以空调用成功代替。

- [x] **Step 5: 提交**

执行结果：部署实现提交 `24ed75a`，调度冒烟仍保持未完成。

```bash
git add deploy delivery-worker scripts && git commit -m "feat: deploy local powerjob with three line workers"
```

### Task 13: 交付 MVP 数据看板和包级复盘

**Files:**
- Create: `web/package.json`
- Create: `web/vite.config.ts`
- Create: `web/src/main.ts`
- Create: `web/src/App.vue`
- Create: `web/src/api.ts`
- Create: `web/src/types.ts`
- Create: `web/src/views/DashboardView.vue`
- Create: `web/src/components/PackageTimeline.vue`
- Create: `delivery-server/src/main/java/com/powerjob/archive/server/api/DashboardController.java`
- Create: `delivery-server/src/main/java/com/powerjob/archive/server/service/DashboardQueryService.java`
- Test: `delivery-server/src/test/java/com/powerjob/archive/server/service/DashboardQueryServiceTest.java`
- Create: `web/src/views/DashboardView.spec.ts`

- [x] **Step 1: 定义查询 API 和一致性测试**

提供：

```text
GET /api/v1/dashboard/summary?runId=&deliveryDate=&archiveDate=&lineId=&rolledOver=
GET /api/v1/dashboard/groups?runId=&deliveryDate=
GET /api/v1/packages?runId=&status=&keyword=&page=&size=
GET /api/v1/packages/{id}
GET /api/v1/packages/{id}/timeline
GET /api/v1/runs/{runId}
```

summary 至少返回 discovered/planned/repaired/outputTotal/delivered/blocked/retrying/waiting/rolledOver/backlog/ungrouped/activeSourceSlots/activeOutputSlots/nextCutoff。组内六阶段数量之和必须等于 outputTotal；RETRY_WAIT/BLOCKED 只叠加角标，不重复计数。

- [x] **Step 2: 实现 PG 聚合查询**

所有查询从 run→source→output 血缘限定范围；总进度按输出包，不用源包百分比替代。异常与清洗原因分开返回，timeline 按 package_node_run 和 package_event 排序并展示每次 attempt、错误、耗时和 rollover 记录。

- [x] **Step 3: 实现最小 Vue 看板**

单页提供 run、deliveryDate、archiveDate、line、rolledOver、状态和关键字筛选；展示总览卡片、三线 4/3/3 进度、六阶段堆叠条、backlog、活跃槽位、异常列表和 10 包明细。点击包打开抽屉，展示节点时间轴、原/新 requestId、artifact SHA、HZ/SG/FTP 路径、重试次数和流转次数；操作按钮调用 retry/resume 并要求填写 reason。

页面每 15 秒刷新，失败时显示错误且不清空上次成功数据；不使用本地缓存伪造完成状态。

- [x] **Step 4: 构建并由 server 托管**

`delivery-server` 构建前执行 `npm ci && npm run build`，把 `web/dist` 复制到 server static；测试：

```bash
cd web && npm ci && npm run test && npm run build
cd .. && ./scripts/mvn.sh -pl delivery-server -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DashboardQueryServiceTest test
```

Expected: Vue 测试/构建 PASS，summary 与 SQL 计数一致，浏览器只访问本地 `/api/v1`，不暴露 AK、SSH key 或 PG 凭据。

- [x] **Step 5: 提交**

执行结果：Vue 与 DashboardQueryServiceTest 全部通过，提交 `5a2df6e`；生成物清理由 `697a1a6` 完成。
```

### Task 14: 编写安装、部署和日常运维文档

**Files:**
- Create: `docs/install.md`
- Create: `docs/deploy.md`
- Modify: `docs/mvp-runbook.md`
- Create: `scripts/submit-run.sh`
- Create: `scripts/resume-run.sh`
- Create: `scripts/verify-delivery.sh`

- [x] **Step 1: 编写安装文档**

`install.md` 必须包含：macOS/Docker 前置、磁盘估算（10 个源包 + repair 临时文件 + 工作卷）、Java 17 容器构建、Python 3.11、Node 构建、Compose 启动、PowerJob 控制台地址、本地 PG 连接、环境变量和只读私钥挂载。另写干净机器验证命令：

```bash
cp deploy/env.example deploy/.env
./scripts/preflight.sh
./scripts/mvp-up.sh
./scripts/bootstrap-powerjob.sh
curl -f http://127.0.0.1:8080/actuator/health
```

Expected: 文档命令顺序可直接执行，不需要读取旧项目密码或在本机安装 Maven。

- [x] **Step 2: 编写部署文档**

`deploy.md` 包含镜像构建、PowerJob 三项任务、三 Worker 参数、生产 OSS bucket/endpoint、FTP 固定脚本安装、CRR 权限/等待、启动顺序、健康检查、升级、停止新 claim、备份 PG 和回滚。明确唯一清理命令只针对本地 MVP volume，不包含生产 FTP 清目录操作。

- [x] **Step 3: 编写提交、续跑和复盘手册**

`submit-run.sh` 只接受 OSS URI、idempotencyKey、jobDate；jobDate 省略时由 API 使用当前业务日期，脚本不提供任何 Pipeline 策略参数。`resume-run.sh` 接受 runId/fromNode/reason；`verify-delivery.sh` 对照 API、PG、HZ/SG Head 和 FTP SHA，并验证预置历史文件仍存在。runbook 给出历史日期测试：

```bash
./scripts/submit-run.sh \
  'oss://junflow-ysai-source-hz-20260906/_derived/ysai-sftp-20261027/delivery_plan_0005/' \
  'delivery-plan-0005-local-v1' \
  '20261030'
./scripts/resume-run.sh 1 sgDownload '杭州及CRR已完成，仅继续FTP下载'
./scripts/verify-delivery.sh 1
```

同时记录两个跨日 case 的可控时钟验证、PipelineVersion 策略配置与版本切换、单包 CRR 独立计时、FTP 不清理历史数据、包级重试、常见 403/CRR 超时/SSH 失败处理，以及“失败后先停止自动任务再排障”的边界。

- [x] **Step 4: 校验文档与脚本**

执行结果：shell 语法、Compose 配置、git diff/check-ignore 和敏感信息扫描全部通过。

- [x] **Step 5: 提交**

执行结果：提交 `285dbaa`。

```bash
git add docs scripts && git commit -m "docs: add local production mvp operations guide"
```

### Task 15: 真实 10 包全链路、故障注入和最终验收

**Files:**
- Create: `delivery-worker/src/test/java/com/powerjob/archive/worker/RealProductionEndToEndTest.java`
- Modify: `delivery-worker/src/test/java/com/powerjob/archive/worker/RetryEndToEndTest.java`
- Modify: `scripts/real-e2e-test.sh`
- Modify: `scripts/verify-delivery.sh`

- [x] **Step 1: 先运行全部非生产写测试**

执行结果：Python 6/6、core 38 项、MvpEndToEndTest 10 包、RunControllerTest、PackageControllerTest、DashboardQueryServiceTest、Vue 测试与生产构建全部通过。RealProductionEndToEndTest、真实 OSS/CRR/SSH 项目仍为 skipped，不计为生产通过。

Run:

```bash
python3 -m unittest nodes/python/test_archive_repair.py -v
./scripts/mvp-test.sh
cd web && npm ci && npm run test && npm run build
```

Expected: 全部 PASS；任何失败先修复，不进入真实 10 包验收。

- [x] **Step 2: 用 PowerJob 真实调度跑通 10 包**

预检通过后，先记录三个 FTP 目标目录既有文件的相对路径、size 和 mtime 基线，再确认数据源绑定的当前发布 Pipeline 根级 config 为 `crrWaitSeconds=180、autoRollover=false、ftpCleanBeforeDownload=false`，最后仅通过提交 API 传 `jobDate=20261030` 创建全新 run。禁止 debug loop 和直接调用 `PipelineEngine` 代替调度；`real-e2e-test.sh` 只观察本地 PG/API，不 Flyway clean 当前 run，不直接修改节点状态。

Expected:

```text
LINE-1：4 个 DELIVERED
LINE-2：3 个 DELIVERED
LINE-3：3 个 DELIVERED
合计：10 个 DELIVERED
所有初始 process/archive/delivery date = 2026-10-30，未被系统自动 rollover
source download/repair 单线并发峰值 <= 1
output 全局并发峰值 <= 10
每个输出包：HZ_UPLOADED → SG_VERIFIED → SG_DOWNLOADED → PUBLISHED → DELIVERED
每个包首次 SG 核验不早于自身 hz_uploaded_at + 180 秒，不等待整批上传完成
三个 FTP 的所有既有文件相对路径、size、mtime 与运行前基线一致
```

执行结果：run 4 使用 Pipeline v4 和 PowerJob 实际调度达到 4/3/3 共 10 个 DELIVERED，`./scripts/verify-delivery.sh 4` 已真实执行并通过 HZ/SG/FTP size/SHA、request mapping、业务日期、五节点阶段、首次实际 SG 请求 180 秒等待、单线源节点不重叠及全局输出并发不超过 10 的断言。首次 sgVerify 仅记录本地 WAITING 不算外部 HEAD。V8–V10 修复了同对象跨 run 重放时的源、容量、分组和输出血缘问题。

用户明确授权“1030 的数据不用了直接操作”后，仅删除发生冲突的 HZ/SG 历史键及 LINE-3 的 batch_00008/00009/00010 三个旧 FTP 文件；除此之外，FTP 基线内所有历史文件 path/size/mtime 均未改变。该授权例外已单独核对，不再声称 1030 历史对象全部保留。

- [x] **Step 3: 验证上传失败只重试当前包**

本地 RetryEndToEndTest 已验证：该包保持 GROUPED + RETRY_WAIT，退避后只重试 hzUpload；repair 不重跑、requestId 不变，其他包继续。真实 run 6 使用启动前精确屏蔽 HZ bucket 虚拟主机名的隔离 JVM，package 37 首次 hzUpload 失败后进入 RETRY_WAIT，移除故障 Worker 并由正常 LINE-1 自动执行第二次上传成功。repair 仅一次、request mapping 未重写，其他包继续处理；裸 endpoint、整容器断网等不能形成真实 HZ 失败的尝试均不计为通过。

- [x] **Step 4: 验证指定节点续跑节省流量**

ResumeServiceTest 与 PackageControllerTest 已验证：不新增 download/repair/hzUpload/sgVerify 节点运行；无 Manifest 从 hzUpload 返回 409 且外部请求数为 0。真实 run 4 的 package 14 在 SG 下载断网后产生失败 attempt，恢复网络并仅从 sgDownload 续跑，最终 DELIVERED；上游 download/repair/hzUpload/sgVerify 未因续跑新增执行。后续固定脚本缺失引起的失败也通过同一节点恢复，未回退上游。

- [x] **Step 5: 在测试时钟下重放两个跨日 case**

BacklogNextDayIntegrationTest 与 DeliveryRolloverServiceTest 已通过：最老 backlog 优先、未交付包重置次日且最高优先级，group/name/mapping/已完成节点不变。DashboardQueryServiceTest 新增可控 rollover 后的看板 summary 和 rolledOver 筛选，2/2 通过；run 4 自身 rolledOver=0，未被真实当前时间自动改写。

- [x] **Step 6: 外部异常时停止并升级**

已执行：预检发现 HZ/SG 配置不完整即停止，未发起 OSS/SSH 业务请求；PowerJob 镜像失败后停止重复拉取并向用户询问方案，未清库或无限运行 E2E。

- [x] **Step 7: 最终检查并提交验收实现**

```bash
./scripts/verify-delivery.sh 6
git diff --check
git status --short
```

Expected: 验证脚本返回 10/10、无 `.partial` 遗留、无重复 requestId；API、日志、manifest、timeline 和看板不泄露 AK/SK 或私钥。AK/SK 仅允许存在受控 Pipeline 版本表，`git status` 只包含预期源码和文档，测试日志/临时包不进入提交。

```bash
git add delivery-worker scripts && git commit -m "test: verify real three line delivery mvp"
```

最终结果：run 4、run 5、run 6 均已完成 10/10 和加强核验。run 6 的 package 37 已完成真实 HZ 失败及第二次自动上传成功，package 38 已在租约过期后通过正式 resume API 恢复。逐包复核时发现 LINE-1 的 37、39、40 最终文件缺失，已按数据库 SHA 从 SG OSS 经固定下载/发布脚本恢复；随后 `./scripts/verify-delivery.sh 6` 完整通过，run 6 基线内 5,999 个历史 FTP 文件 path/size/mtime 均未改变。最终 Maven 全量回归通过：core 41 项（2 skipped）、worker 4 项（2 skipped）、server 7 项，本地 10 包通过；Python 6 项、Vue 测试/构建、完整预检、敏感信息、shell 语法和差异检查也全部通过。

## 4. 最终交付清单

1. 可执行版本化 Pipeline：真实源 OSS、修复、杭州 OSS、CRR、新加坡 OSS、FTP、回执。
2. 本地 Compose：PG、PowerJob MySQL/Server、业务 server、三条 Worker 和 Vue 看板。
3. OSS 提交 API、run 查询、指定节点 resume、包级 retry、跨日 rollover API。
4. 每线源节点并发 1、全局输出并发 10、三线 4/3/3 的 10 包真实验收证据。
5. 两个跨日 case 的自动化测试与看板复盘结果。
6. `docs/install.md`、`docs/deploy.md`、`docs/mvp-runbook.md` 和预检/提交/续跑/验收脚本。
7. 真实外部链路未通过时，明确列出阻塞项；不得用 Local*Node 或 skipped 测试宣称交付完成。
