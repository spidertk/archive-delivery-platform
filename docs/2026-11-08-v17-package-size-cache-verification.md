# V17 包大小、复合实体与缓存复用真实验收记录

> 日期：2026-11-08
> 模式：本地实现，真实 Compose、真实源/交付 OSS、本地 FTP
> 结果：历史通过；当前模型由 `2026-11-09-v19-source-metadata-compact-artifact-verification.md` 替代
> 说明：本记录保留扁平 metadataIds 和七字段 deliveryArtifact 的历史口径，不作为当前交付依据；此前替代 `2026-11-08-v17-composite-entity-cache-verification.md` 中未校验包大小的结论

## 1. 迁移、配置与标准回归

- 数据库最新 Flyway 版本为 V17，真实 Compose API、Worker、PostgreSQL、PowerJob 与三个 FTP 服务正常运行。
- 提交不包含 cutoffAt，服务端由不可变 Pipeline 的 businessTimezone/deliveryCutoffTime 推导截止时间。
- 实体为 `alibaba/jun`、`alibaba/jun2`、`local-other/jun3`；同一 vendorCode 下多个 entityCode 可共存并整体分配。
- 真实 Pipeline 配置为 selection.limit=32、minPackageBytes=8388608、maxPackageBytes=10485760；预检真实列出 32 个候选源包，总字节数 79,752,551。
- 分配保持连续来源优先，无法满足下限时按字节和回溯其他组合；组内保持来源顺序，不切分任何原始文件。来源合计或最终包低于下限时必须阻断。
- 全量 Maven 测试、Python unittest 6 项、前端 Vitest 和生产构建、Shell 语法、Compose 配置及 `git diff --check` 均通过。分配测试覆盖 10 个 2 MiB 源包形成两个 10 MiB 包，以及 8/2/2/2/2 MiB 回溯为两个 8 MiB 包。

## 2. 主交付：真实下载并形成接近 10 MiB 的包

- deliveryId=5，deliveryBatch=`real-e2e-1794145391`，targets 为 alibaba/jun 与 alibaba/jun2，各 targetCount=1。
- 共分配 14 个真实 OSS 来源，每个来源均由独立验收再次 GET 并核对 size/SHA；不再固定为三份来源。
- 原任务 6/7 在 FTP 停止时进入 BLOCKED，主单节点边界暂停后使用相同批次和参数恢复；rollover 后旧任务 6/7 为 EXPIRED，新任务 8/9 接续至 2026-11-10，并逐任务 retry。

| taskId | entityCode | 来源数 | metadataIds | sizeBytes | MiB（约） | SHA-256 |
|---|---|---:|---:|---:|---:|---|
| 8 | jun | 9 | 36 | 10461374 | 9.98 | `3f562d8fc4394a286d6ca7cb8ac78bddfb773d6a4473fcf0b330fd8b5b1db5ab` |
| 9 | jun2 | 5 | 47 | 10461889 | 9.98 | `cc87302fe495ebd2f65801f33cb0b6fef68314b21822e2f3b36b562ead85f8ac` |

`./scripts/verify-delivery.sh 5` 独立执行通过：14 个来源真实 GET、两件交付 OSS GET、两个 FTP 上传下载、task 与每个 deliveryArtifact 的 size/SHA、metadataIds、merge 目录和 `.partial` 检查全部通过。主单最终 DELIVERED，任务 8/9 均 SUCCEEDED。

## 3. 跨交付、跨厂商缓存复用

- deliveryId=6，deliveryBatch=`real-e2e-1794145391-reuse`，目标为 local-other/jun3。
- taskId=10 复用相同来源，5 个 sourceDownload 输出全部 `cacheHit=true`，没有重复源 OSS GET；独立复核输出为 `CACHE REUSE VERIFIED 5 10143282 True`。
- 来源合计为 10,143,282 字节，最终包为 10,092,481 字节，均在 8–10 MiB 内；metadataIds=32，来源对数量为 5。
- 最终包 SHA-256=`5e66cb76e7e2ec3632a3194a62cb7a57a11d6baf2f8b2c11285f2c844232b205`，主单 DELIVERED，任务 SUCCEEDED。

任务 API 已验证直接返回 JSON 数组/对象，不再泄漏 JDBC `PGobject` 的 `type/value` 包装：taskId=10 的 artifacts 为 5 对，metadataIds 为 32 项，sourceDataIds 为 5 项；每个 deliveryArtifact.sizeBytes 都等于 task.size_bytes=10092481。

## 4. 文件证据与自助复核

本次 FTP 文件：

```text
/data/ftp-verify/5/2026-11-10/entity-1/batch_00001.tar.gz  10461374  3f562d8fc4394a286d6ca7cb8ac78bddfb773d6a4473fcf0b330fd8b5b1db5ab
/data/ftp-verify/5/2026-11-10/entity-2/batch_00002.tar.gz  10461889  cc87302fe495ebd2f65801f33cb0b6fef68314b21822e2f3b36b562ead85f8ac
/data/ftp-verify/6/2026-11-09/entity-3/batch_00001.tar.gz  10092481  5e66cb76e7e2ec3632a3194a62cb7a57a11d6baf2f8b2c11285f2c844232b205
```

只读复核命令：

```bash
./scripts/verify-delivery.sh 5

curl -fsS http://127.0.0.1:8080/api/v1/tasks/10/timeline | python3 -c \
  'import json,sys; t=json.load(sys.stdin); n=next(n for n in t["nodes"] if n["nodeType"]=="oss.download" and n["status"]=="SUCCEEDED"); sources=n["outputLocator"]["sources"]; assert sources and all(s["cacheHit"] is True for s in sources); print(len(sources),sum(s["sizeBytes"] for s in sources))'

docker run --rm -v deploy_archive_data:/data:ro alpine:3.22 \
  sh -c 'find /data/ftp-verify/5/2026-11-10 /data/ftp-verify/6/2026-11-09 -type f ! -name "*.partial" -exec sha256sum {} \;; test -z "$(find /data/ftp-verify/5/2026-11-10 /data/ftp-verify/6/2026-11-09 -type f -name "*.partial" -print -quit)"'

docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T postgres \
  psql -U archive -d archive_delivery -Atc \
  "SELECT delivery_id||':'||delivery_batch||':'||status FROM archive_delivery.delivery_submit ORDER BY delivery_id; SELECT task_id||':'||execution_status||':'||size_bytes FROM archive_delivery.delivery_task WHERE execution_status='SUCCEEDED' ORDER BY task_id;"
```

最终数据库状态为 delivery 5/6 均 DELIVERED，任务 8/9/10 均 SUCCEEDED；本次文件检查未发现 `.partial`。
