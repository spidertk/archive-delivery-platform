# Archive Delivery MVP 调试手册

## 1. 范围

本地生产 MVP 使用三条生产线，合计交付 10 个输出包：

| 生产线 | legacyBatch | provider | 配额 |
|---|---:|---|---:|
| LINE-1 | 3 | june | 4 |
| LINE-2 | 4 | june-1 | 3 |
| LINE-3 | 5 | june-2 | 3 |

真实输入前缀：

```text
oss://junflow-ysai-source-hz-20260906/_derived/ysai-sftp-20261027/delivery_plan_0005/
```

目标 Pipeline 为下载 → repair → 协议校验 → 分组 → 杭州 OSS → CRR/新加坡 OSS 核验 → FTP 下载 → 原子发布 → 本地回执。只有真实 HZ OSS、SG OSS、FTP 与本地 PostgreSQL 全部核验通过，才可认定生产验收完成；`Local*Node` 仅用于自动化测试。

## 2. 环境

需要 macOS、Docker、Docker Compose 和 Python 3.11。Java 17/Maven 通过 `./scripts/mvn.sh` 构建容器提供，不要求安装在本机。

手动环境准备清单：

- [ ] Docker daemon 可用，Compose 可以启动 PostgreSQL 16。
- [ ] 构建机器可以访问 Maven 仓库、Docker 镜像仓库和三个 OSS Endpoint。
- [ ] 源 Bucket 凭据具有指定前缀的 List、Get/Head 权限；HZ 凭据另需测试前缀 Put/Head/Delete 权限；SG 凭据需 Head 权限。
- [ ] 三条线的 SSH 私钥通过只读文件挂载，跳板与 FTP 主机可达，FTP 机器已有 `ossutil64`。
- [ ] 准备足够的本地磁盘用于 10 个源包、repair 临时文件和工作卷。
- [ ] 复制 `deploy/env.example` 为 `deploy/.env`，只填写真实环境值；该文件已被 Git 忽略。
- [ ] 运行 `./scripts/preflight.sh`。任何 403、CRR 探测对象缺失、SSH 或跳板失败都会非零退出；修复环境前不得开始真实 E2E。
- [ ] 预检只在 HZ 的 `.archive-mvp-preflight/{uuid}` 写入并删除一个测试对象，不触碰业务对象或 FTP 历史文件。

数据库访问使用 Java JDBC/HikariCP 连接池，Flyway 自动建表，JdbcTemplate 执行 SQL，事务和 `SKIP LOCKED` 保证并发领取安全；不依赖原项目的 Go/GORM，也不需要使用 `psql` 执行业务流程。PG 指 PostgreSQL，是独立数据库，不是项目自研组件。

数据库：

```text
JDBC: jdbc:postgresql://localhost:55432/archive_delivery
数据库: archive_delivery
用户: archive
开发密码: archive_local
Schema: archive_delivery
```

开发密码只用于本地容器。生产环境必须通过部署 Secret 注入 `ARCHIVE_DB_PASSWORD`，不能沿用该值。

## 3. 配置位置

- 应用与 OSS：`delivery-worker/src/main/resources/application.yml`
- PostgreSQL：`deploy/docker-compose.yml`
- 数据表：`delivery-core/src/main/resources/db/migration/V1__mvp_schema.sql`
- 生产线、每组 5 包及 Pipeline：`delivery-core/src/main/resources/db/migration/V2__mvp_pipeline.sql`
- 可读 Pipeline JSON：`delivery-core/src/main/resources/pipelines/archive-delivery-v1.json`
- Python repair：`nodes/python/archive_repair.py`

OSS 凭据只从 `OSS_SOURCE_ACCESS_KEY_ID`、`OSS_SOURCE_ACCESS_KEY_SECRET` 环境变量读取，不写入数据库、Pipeline 或镜像。

## 4. 测试

先运行不需要外部服务的 Python 测试：

```bash
cd archive-delivery-platform
python3 -m unittest nodes/python/test_archive_repair.py -v
```

完整测试需要可用 Docker daemon：

```bash
chmod +x scripts/mvp-test.sh scripts/mvp-up.sh
./scripts/mvp-test.sh
```

只验证本地 Docker PostgreSQL，并执行/重复校验 Flyway 初始化：

```bash
export ARCHIVE_TEST_LOCAL_PG=true
mvn -pl delivery-core -Dtest=LocalPostgresIntegrationTest test
```

只验证真实源 OSS 的 List、Metadata 和 1 字节 Range Get。先复制 `deploy/env.example` 为 `deploy/.env` 并填入真实只读凭据，再运行：

```bash
chmod +x scripts/oss-connectivity-test.sh
./scripts/oss-connectivity-test.sh
```

`deploy/.env` 已被 Git 忽略，凭据由 Docker 注入测试容器，不会写入镜像。该测试不上传、修改或删除 OSS 对象。

测试内容：

- 真实样本 4 个请求的 repair、日期、provider、响应文件及映射。
- 缺 metadata 不发布 Manifest，缺 requestTime 使用零点，SYNC 文件校准。
- 文件 SHA 核验、原子复制及错误摘要拒绝。
- Pipeline 节点顺序和 SOURCE/OUTPUT 范围。
- PostgreSQL 真实迁移、两组各 5 包、40 条映射、40 个唯一新 requestId。
- 编号重试复用、重复发现幂等、90 个成功节点运行。
- 一次截止流转后交付日期加一天，原大组不变。
- 最终 10 个输出包全部 DELIVERED。

`OSSDiscoveryIntegrationTest` 在注入真实 OSS 凭据后额外执行，只列举对象，不写 OSS。

真实 OSS 输入 + 本地 Docker PostgreSQL 的两个端到端测试（需要 `deploy/.env` 凭据，脚本自动构建带 python3 的 Maven 测试镜像并启动 compose 的 PostgreSQL）：

```bash
chmod +x scripts/real-e2e-test.sh
./scripts/real-e2e-test.sh
```

- `MvpOssEndToEndTest`：真实读取 delivery_plan_0005 前缀按 Key 排序前 10 个 `.tar.gz`（前 5 个 LINE-1、后 5 个 LINE-2），真实下载、repair、分组、杭州/新加坡本地适配器传输、FTP 原子发布与回执，断言血缘、编号连续分区、产物 SHA、逐包校验发布 tar 内 metadata/响应文件。
- `RetryEndToEndTest`：HZ upload 首次执行注入一次瞬时 IO 异常，断言包保持 GROUPED + RETRY_WAIT、fence 递增、仅重试 hzUpload，最终 DELIVERED 且 repair 不重复、requestMapping 与编号区间不变。

两个测试每次运行前都会对该库执行 Flyway clean + migrate 重置 `archive_delivery` schema，只适用于 compose 启动的可丢弃开发库，不能指向生产数据库。容器内通过 `host.docker.internal:55432` 访问本地 PostgreSQL，可用 `ARCHIVE_DB_URL` 覆盖。

## 5. 真实 OSS 调试

在 shell 中注入凭据，不把值写入项目文件：

```bash
export OSS_SOURCE_ACCESS_KEY_ID='你的AccessKeyId'
export OSS_SOURCE_ACCESS_KEY_SECRET='你的AccessKeySecret'
export OSS_SOURCE_ENDPOINT='https://oss-cn-hangzhou.aliyuncs.com'
export ARCHIVE_DISCOVERY_MODE=oss
export ARCHIVE_DEBUG_LOOP=true
./scripts/mvp-up.sh
```

Worker 按对象 Key 排序取前 10 个 `.tar.gz`，前 5 个归 LINE-1，后 5 个归 LINE-2。重复启动按 `bucket + key + ETag` 幂等，不重复创建源包。

如果需要重复使用同一批测试数据，先停止 Worker，再通过受控运维流程清理 MVP 数据卷。以下命令会删除本 MVP 的 PostgreSQL 和本地交付数据，不能用于生产：

```bash
docker compose -f deploy/docker-compose.yml down
docker volume rm archive-delivery-platform_archive_pg archive-delivery-platform_archive_data
```

## 6. 本地无 OSS 调试

将 10 个不同文件名的 tar.gz 放入挂载样本目录，设置：

```bash
export ARCHIVE_DISCOVERY_MODE=local
export ARCHIVE_LOCAL_SOURCE_DIR=/samples
export ARCHIVE_DEBUG_LOOP=true
./scripts/mvp-up.sh
```

Compose 默认挂载 `../../old/sample`（相对 `deploy/docker-compose.yml`，即 PowerJob 仓库的 `old/sample`）为只读样本目录。目录必须有 10 个包；单个样本只用于自动化测试复制，不会伪造真实 OSS 结果。

## 7. 查询结果

```bash
docker compose -f deploy/docker-compose.yml exec postgres \
  psql -U archive -d archive_delivery
```

```sql
SET search_path TO archive_delivery;

SELECT production_line_id,status,run_status,current_node,count(*)
FROM source_package GROUP BY 1,2,3,4 ORDER BY 1,2,3,4;

SELECT production_line_id,archive_date,delivery_date,status,count(*)
FROM output_package GROUP BY 1,2,3,4 ORDER BY 1,2,3,4;

SELECT line_code,group_no,target_count,assigned_count,status
FROM production_group g JOIN production_line l ON l.id=g.production_line_id
ORDER BY line_code,group_no;

SELECT sp.object_key,sm.original_request_id,rm.new_request_id,
       op.new_package_name,op.status
FROM request_mapping rm
JOIN source_member sm ON sm.id=rm.source_member_id
JOIN source_package sp ON sp.id=sm.source_package_id
JOIN output_package op ON op.id=rm.output_package_id
ORDER BY sp.id,rm.new_request_id;

SELECT output_package_id,new_file_name,size_bytes,sha256,
       hz_path,sg_path,published_path
FROM output_artifact ORDER BY output_package_id,artifact_seq;

SELECT source_package_id,output_package_id,node_id,attempt,status,error_detail
FROM package_node_run ORDER BY id;
```

## 8. PowerJob 接入

Processor 类：

```text
com.powerjob.archive.worker.PackageTickProcessor
```

Worker 配置通过环境变量开启：

```bash
export POWERJOB_ENABLED=true
export POWERJOB_APP_NAME=archive-delivery-mvp
export POWERJOB_SERVER_ADDRESS='你的PowerJobServer:7700'
export ARCHIVE_DEBUG_LOOP=false
```

在 PowerJob 控制台创建 BroadcastProcessor 周期任务。每次 Tick 两条线各推进最多一个节点；PG 的 `SKIP LOCKED + lease + fence` 负责并发防重。`ARCHIVE_DEBUG_LOOP=true` 仅用于无 Server 调试，生产不要与 PowerJob 周期任务同时开启。

## 9. 常见故障

- `OSS_SOURCE_ACCESS_KEY_ID ... required`：未注入 OSS 凭据。
- OSS 403：AccessKey 无该 Bucket 前缀的 List/Get 权限，或 Endpoint 不正确。
- `fewer than 10 tar.gz packages`：前缀下有效对象不足 10 个。
- `Downloaded size does not match`：源对象执行期间变化或下载不完整，节点进入 RETRY_WAIT。
- `Stale source/output fence`：旧 Worker 尝试提交，必须让其停止，不能强制覆盖新租约。
- `Artifact SHA-256 mismatch`：产物传输损坏，当前状态不推进。
- `Python repair failed`：查看节点错误和 stderr；缺 metadata、协议文件或编号超九位会失败。
- Docker daemon 不可用：先恢复 Docker，再运行 Testcontainers；不能只跑编译就宣称端到端通过。
