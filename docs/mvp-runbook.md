# Archive Delivery 真实验收手册

## 1. 完成口径

当前核心 Pipeline DAG：

```text
sourceDownload → mergePackages → repair → qualify → targetUpload → ftpDownloadVerify
```

本地真实核心 case 必须同时证明：

- 新地址源 OSS 真实 GET 并保留，相同 URI+版本使用缓存；逐源 original_file_name/size/SHA/artifact 与 source_data 一致，created_at/updated_at 非空。
- 持续扫描并下载足够来源，来源合计和最终包均为 8–10 MiB；merge 输出请求目录且无 merged-source.tar.gz，repair/qualify 成功，形成 alibaba/jun 与 alibaba/jun2 两个任务，repair 输入也是 entityCode。
- delivery_mapping 覆盖每个入选来源且各分配一次，任务 artifacts 仅含 dataId/sourceArtifact 且 sourceArtifact 完全等于 source_data.artifact，不保存 deliveryArtifact；metadata_ids 与 sourceDataIds 完全相等且不存 requestId，repair manifest 保留逐来源 originalPath/newRequestId。
- 两个有效任务的 new_file_name 不同且序号在整个 delivery_id 内连续，任务 size/token 与成功 repair locator 及实际文件一致，且都在 8–10 MiB 内。
- request_id_allocation.request_namespace 等于全局唯一 deliveryBatch。
- 两件合并产物上传到另一 OSS Bucket 并 GET 回验；两个本地 FTP 账户分别上传下载一件产物，文件保留、size/SHA 一致且无 .partial。
- 同一 case 覆盖节点边界暂停、相同批次再提交继续、单任务 retry 和跨日新 taskId。
- 最新有效任务 SUCCEEDED，最终 ftpDownloadVerify 成功，delivery_submit 为 DELIVERED。

API 接受、PowerJob 绿色实例、Local/fake 节点、数据库直写或 skipped 测试均不是真实交付证据。

## 2. 标准回归

```bash
python3 -m unittest nodes/python/test_archive_repair.py -v
./scripts/mvn.sh test
cd web && npm ci && npm run test && npm run build
bash -n scripts/*.sh
```

覆盖 V1–V23 迁移、全表全列注释、废弃对象不存在、source_data 单 artifact 白名单、来源时间戳、任务产物对一致性、delivery 内包名唯一、多来源映射、8–10 MiB 边界、事件不可变、复合业务键提交、Pipeline 推导 cutoffAt、暂停再提交、请求编号外键、跨日完整血缘、集中 Pipeline 和前端。

核心状态集成案例位于 `ArchiveModelIntegrationTest`：

| 案例 | 初始事实 | 跨日操作 | 必须结果 |
|---|---|---|---|
| 正向作业单 | 三实体各 2，共 6，全部 SUCCEEDED | 无 | 主单 DELIVERED |
| 当日逆向作业单 | 每实体 1 成功、第 2 个 READY | 指定下一交付日 rollover | 三个旧任务 EXPIRED，三个新 taskId 占次日配额，主单 PARTIALLY_DELIVERED |
| 次日续交 | 每实体 1 个既往成功 + 1 个次日任务 | 三个新任务全部 SUCCEEDED | 每实体有效任务仍为 2，主单 DELIVERED |

## 3. 一键真实验收

按 `docs/install.md` 配置后执行：

```bash
./scripts/real-e2e-test.sh
```

该入口具有真实 OSS/FTP 写副作用，并明确放弃旧本地业务数据。它会：

1. 仅 V14 以下数据库先携带 2026-11-06 授权完成 V13，再携带 2026-11-07 授权应用 V14–V23；已有 V14+ 数据库直接进入增量迁移。随后配置通用调度、六节点 Pipeline、来源和三个 FTP 实体。
2. 双确认清理运行事实，真实预检源 OSS、目标 Bucket 和三个 FTP 账户。
3. 暂停前两个核心实体 FTP，以指定 sg-storage products 为 ossUri，不传 cutoffAt，提交 alibaba/jun、alibaba/jun2、各 targetCount=1，持续下载到每包来源合计和最终产物均为 8–10 MiB。
4. 在两个 ftpDownloadVerify 阻塞边界暂停，使用完全相同请求再次提交并确认 deliveryId 不变。
5. 对两个旧任务 rollover，证明旧任务 EXPIRED、新 taskId 按下一日接续；新任务再次阻塞后恢复 FTP，逐任务 retry。
6. 有界等待 DELIVERED，调用 verify-delivery.sh 独立读取 PG、OSS 和 FTP 核验。
7. 再以 local-other/jun3 提交相同来源，等待第二个 delivery 成功，核对 sourceDownload 的所有 source.cacheHit=true，证明跨交付、跨厂商复用缓存而未重复 GET，并核对复用包也在 8–10 MiB 内。

任何预检、意外 BLOCKED、包大小不达标、超时或 SHA 不一致都会非零停止，不用 SQL 修改状态绕过。

## 4. 分步操作

```bash
./scripts/mvp-up.sh
./scripts/bootstrap-powerjob.sh
./scripts/configure-pipeline.sh
./scripts/preflight.sh
./scripts/submit-delivery.sh <ossUri> <pipelineCode> <deliveryDate> <deliveryBatch>
./scripts/verify-delivery.sh <deliveryId>
```

暂停与继续：

```bash
curl -H 'Content-Type: application/json' \
  -d '{"reason":"节点边界暂停验收"}' \
  http://127.0.0.1:8080/api/v1/deliveries/<deliveryId>/pause
# 使用完全相同的提交 JSON 再次 POST /api/v1/deliveries，deliveryId 不变并从当前节点继续。
```

retry/resume：

```bash
curl -H 'Content-Type: application/json' \
  -d '{"expectedFence":<fence>,"reason":"外部依赖恢复，重试当前任务"}' \
  http://127.0.0.1:8080/api/v1/tasks/<taskId>/retry
./scripts/resume-delivery.sh <deliveryId> <fromNode> '前置事实已核验，续跑当前节点' <taskId>
```

EXPIRED 任务不能 retry/resume。rollover 必须携带 expectedDeliveryDate、deliveryDate 和 reason，且新日期晚于原日期。

## 5. 只读事实查询

```sql
SET search_path TO archive_delivery;
SELECT delivery_id,delivery_batch,delivery_date,cutoff_at,status,target_strategy,discovery_completed_at,completed_at
FROM delivery_submit WHERE delivery_id=<deliveryId>;
SELECT task_id,delivery_entity_id,entity_code,worker_group,delivery_date,current_node_id,
       execution_status,new_file_name,size_bytes,token_count,artifacts,metadata_ids
FROM delivery_task WHERE delivery_id=<deliveryId> ORDER BY task_id;
SELECT data_id,original_file_name,size_bytes,sha256,artifact,created_at,updated_at,
       original_locator->>'cachePath' AS cache_path
FROM source_data WHERE delivery_id=<deliveryId> ORDER BY data_id;
SELECT task_id,data_id,source_seq FROM delivery_mapping
WHERE delivery_id=<deliveryId> ORDER BY task_id,source_seq;
SELECT process_id,task_id,node_id,node_type,stage_code,attempt,status,output_locator,error_code
FROM delivery_node WHERE delivery_id=<deliveryId> ORDER BY process_id;
SELECT event_type,task_id,process_id,delivery_entity_id,reason_code,message,payload
FROM delivery_event WHERE delivery_id=<deliveryId> ORDER BY event_id;
SELECT request_namespace,data_id,start_value,count_value FROM request_id_allocation
WHERE request_namespace=(SELECT delivery_batch FROM delivery_submit WHERE delivery_id=<deliveryId>);
```

跨日新旧 taskId 从 TASK_EXPIRED/TASK_REGENERATED payload 的 oldTaskId/newTaskId 查询，不再使用 superseded_by_task_id。

## 6. 外部证据与自助复核

`verify-delivery.sh` 在独立容器中读取当前 Pipeline 受控配置，不打印 Secret；它实际 GET 全部入选源 OSS 和交付 OSS，重新登录 FTP 下载，并核对 Worker 最终文件、merge 目录、mapping、data_id metadataIds 和 .partial。

Worker 持久卷中的最终 FTP 文件可用以下命令列出并计算 SHA（替换 deliveryId）：

```bash
docker run --rm -v deploy_archive_data:/data:ro alpine:3.22 \
  sh -c 'find /data/ftp-verify/<deliveryId> -type f -path "*/entity-*" ! -name "*.partial" -exec sha256sum {} \;'
```

输出 SHA 应与最新成功 `python.repair` 的 `delivery_node.output_locator->>'sha256'` 一致。历史 V14 三实体验收仅作历史证据，不替代本次真实合并 case。

## 7. 本机 PostgreSQL 可见合并辅助 case

该入口只用于本机 Compose，会清空并完整重建 archive_delivery，禁止用于生产：

```bash
set -a; source deploy/.env; set +a
export ARCHIVE_TEST_LOCAL_PG=true ARCHIVE_TEST_LOCAL_SCHEMA_RESET=true
export ARCHIVE_DB_URL='jdbc:postgresql://host.docker.internal:5432/archive_delivery?currentSchema=archive_delivery'
export ARCHIVE_DB_USER="${ARCHIVE_DB_USER:-archive}"
export ARCHIVE_DB_PASSWORD="${ARCHIVE_DB_PASSWORD:-archive_local}"
./scripts/mvn.sh -pl delivery-worker -am -Dsurefire.failIfNoSpecifiedTests=false \
  '-Dtest=LocalMergedCaseVerificationTest' test
```

成功日志必须包含实际 clean、V1–V13 与 V14–V23 两段迁移和 `LOCAL MERGED CASE PASSED`。核对 Flyway=23、source_data=3、原任务=1、每任务 delivery_mapping=3、sourceArtifact=source_data.artifact、跨日 taskId 不同且来源集合不变。该 case 不能替代真实 OSS/FTP 验收。
