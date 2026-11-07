# Archive Delivery 真实验收手册

## 1. 完成口径

当前核心 Pipeline DAG：

```text
sourceDownload → repair → qualify → targetUpload → ftpDownloadVerify
```

该顺序来自 PipelineVersion，不是 Processor 或 Service 的固定分支。必须同时证明：

- 源 OSS 直接下载三份样本且本地文件保留，repair/qualify 成功，请求映射、size/token/SHA 和七字段 artifacts 入库。
- 三个交付实体各一个有效任务，任务直接保存 delivery_entity_id/target_seq。
- 三份产物上传到另一 OSS Bucket，外部 size/SHA 与 source_data、任务和 delivery_node 一致。
- 三个本地 FTP 账户按实体目录完成实际下载，文件保留，size/SHA 一致，无 `.partial`。
- 最新任务全部 SUCCEEDED，最终 ftpDownloadVerify 成功，delivery_submit 聚合 DELIVERED。
- 至少一次单任务 retry 和一次跨日新 task_id 接续，下一日 OSS/FTP 路径隔离。

API 接受、PowerJob 绿色实例、Local/fake 节点或 skipped 测试均不是真实交付证据。

## 2. 标准回归

```bash
python3 -m unittest nodes/python/test_archive_repair.py -v
./scripts/mvn.sh test
cd web && npm ci && npm run test && npm run build
bash -n scripts/*.sh
```

覆盖 V1–V15 迁移、全表全列注释、废弃对象不存在、artifacts 白名单、事件不可变、跨日血缘、集中 Pipeline 和前端。

核心状态集成案例位于 `ArchiveModelIntegrationTest`：

| 案例 | 初始事实 | 跨日操作 | 必须结果 |
|---|---|---|---|
| 正向作业单 0001 | 三实体各 2，共 6，截止前全部 SUCCEEDED | 无 | 主单 DELIVERED |
| 当日逆向作业单 0002 | 每实体 1 成功、第 2 个 READY，共 5/6 | 指定下一交付日 rollover | 三个旧任务 EXPIRED，三个新 taskId 占次日配额，主单 PARTIALLY_DELIVERED |
| 次日续交作业单 0002 | 每实体 1 个既往成功 + 1 个次日新任务 | 三个新任务全部 SUCCEEDED | 每实体有效任务仍为 2，主单 DELIVERED |

可单独执行：

```bash
./scripts/mvn.sh -pl delivery-core -Dtest=ArchiveModelIntegrationTest test
```

## 3. 一键真实验收

按 `docs/install.md` 配置后执行：

```bash
./scripts/real-e2e-test.sh
```

该入口具有真实 OSS 写副作用，并明确放弃旧本地业务数据。它会：

1. 携带 V14 授权启动新模型并应用 V15，配置通用调度、集中 Pipeline、来源和三个 FTP 实体。
2. 双确认清理运行事实，保留 Pipeline/实体配置和 OSS/FTP 历史文件。
3. 真实预检源 OSS、目标 Bucket 和三个 FTP 账户。
4. 暂时停止两个实体 FTP，提交三个 targets 各一的新交付。
5. 恢复第一实体 FTP，仅 retry 对应阻塞任务；第二实体保持未完成并 rollover。
6. 证明旧任务 EXPIRED、新 task_id 按指定下一日接续并占次日配额，主单先 PARTIALLY_DELIVERED，再恢复第二实体。
7. 有界等待 3/3 DELIVERED，调用 verify-delivery.sh 独立读取 PG、OSS 和 FTP 核验。

任何预检、意外 BLOCKED、超时或 SHA 不一致都会非零停止，不用 SQL 修改状态绕过。

## 4. 分步操作

```bash
./scripts/mvp-up.sh
./scripts/bootstrap-powerjob.sh
./scripts/configure-pipeline.sh
./scripts/preflight.sh
./scripts/submit-delivery.sh <sourceId> <pipelineVersionId> <deliveryDate> <cutoffAt> <deliveryBatch> <idempotencyKey>
./scripts/verify-delivery.sh <deliveryId>
```

retry/resume：

```bash
curl -H 'Content-Type: application/json' \
  -d '{"expectedFence":<fence>,"reason":"外部依赖恢复，重试当前任务"}' \
  http://127.0.0.1:8080/api/v1/tasks/<taskId>/retry
./scripts/resume-delivery.sh <deliveryId> <fromNode> '前置事实已核验，续跑当前节点' <taskId>
```

EXPIRED 任务不能 retry/resume。跨日手工验收调用 `/api/v1/deliveries/<deliveryId>/rollover`，必须携带 expectedDeliveryDate、deliveryDate 和 reason，且新日期晚于原日期；不得直接修改日期或状态。

```bash
curl -H 'Content-Type: application/json' \
  -d '{"expectedDeliveryDate":"2026-11-07","deliveryDate":"2026-11-08","reason":"截止未完成，次日继续"}' \
  http://127.0.0.1:8080/api/v1/deliveries/<deliveryId>/rollover
```

## 5. 只读事实查询

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T postgres \
  psql -U archive -d archive_delivery
```

```sql
SET search_path TO archive_delivery;
SELECT delivery_id,delivery_batch,status,target_strategy,discovery_completed_at,completed_at
FROM delivery_submit WHERE delivery_id=<deliveryId>;
SELECT task_id,data_id,delivery_entity_id,target_seq,artifact_seq,delivery_date,current_node_id,
       execution_status,size_bytes,token_count,sha256,superseded_by_task_id
FROM delivery_task WHERE delivery_id=<deliveryId> ORDER BY task_id;
SELECT process_id,task_id,node_id,node_type,stage_code,attempt,status,output_locator,error_code
FROM delivery_node WHERE delivery_id=<deliveryId> ORDER BY process_id;
SELECT event_type,task_id,process_id,delivery_entity_id,reason_code,message,payload
FROM delivery_event WHERE delivery_id=<deliveryId> ORDER BY event_id;
```

每个目标应有 targetCount 个最新有效任务且最终全部 SUCCEEDED。跨日旧行必须 EXPIRED 并指向下一日新行，新行占次日实体配额；主单未全部成功前为 PARTIALLY_DELIVERED，全部成功后 DELIVERED。

## 6. 外部证据与自助复核

`verify-delivery.sh` 在独立容器中读取当前 Pipeline 受控配置，不打印 Secret；它实际 GET 源 OSS 和交付 OSS，重新登录 FTP 下载，并核对 Worker 最终文件和 `.partial`。

Worker 持久卷中的最终 FTP 文件可用以下命令列出并计算 SHA（替换 deliveryId）：

```bash
docker run --rm -v deploy_archive_data:/data:ro alpine:3.22 \
  sh -c 'find /data/ftp-verify/<deliveryId> -type f -path "*/entity-*" ! -name "*.partial" -exec sha256sum {} \;'
```

该命令只读挂载持久卷，不访问 Pipeline Secret，不修改文件。输出应包含三个不同实体路径，且 SHA 与 `delivery_task.sha256` 一致。本次 V14 验收使用 deliveryId=1，实际结果和原始证据见 `2026-11-07-entity-target-v14-verification.md`。
