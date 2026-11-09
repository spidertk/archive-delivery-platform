# V19 来源级 metadata 与五字段交付产物真实验收记录

> 日期：2026-11-09
> 模式：本地实现，真实 Compose、真实源/交付 OSS、本地 FTP
> 结果：历史通过；当前模型由 `2026-11-09-v20-source-artifact-data-id-verification.md` 替代
> 说明：本记录保留五字段 deliveryArtifact 和 requestId 二维 metadataIds 的历史口径，不作为当前交付依据；此前替代 `2026-11-08-v17-package-size-cache-verification.md` 的旧口径

## 1. 模型、日志与标准回归

- 数据库最新 Flyway 版本为 V19；V18 将 metadata_ids 转为逐来源二维数组，V19 删除 deliveryArtifact.newFileName/file_mapping 并强制五字段白名单。
- sourceArtifact 继续逐字段等于 source_data.artifact；任务级 new_file_name 保持 delivery_id 内有效任务唯一。
- metadataIds[i] 与 artifacts[i] 的 dataId 对应，完整 requestId 映射保留在 merge/repair manifest，不再冗余写入 delivery_task.deliveryArtifact。
- sourceDownload、mergePackages、repair、qualify、targetUpload、ftpDownloadVerify 均有 SLF4J 开始/成功日志；下载记录缓存命中/回退，repair/qualify 分开记录来源组数和请求总数。
- 全量 Maven 测试、Python unittest 6 项、前端 Vitest 和生产构建、Shell 语法、Compose 配置及 `git diff --check` 均通过；干净库实际执行 V1–V19 迁移成功。
- 本地 PipelineVersion versionId=5，selection.limit=32、minPackageBytes=8388608、maxPackageBytes=10485760；真实预检列出 32 个候选源包，总字节数 79,752,551。

## 2. 主交付与跨日

- deliveryId=7，deliveryBatch=`real-e2e-1794153301`，targets 为 alibaba/jun 与 alibaba/jun2，各 targetCount=1。
- 共分配 14 个真实 OSS 来源；独立验收再次 GET 每个来源并核对 size/SHA。
- 原任务 11/12 在 FTP 停止时进入 BLOCKED；主单节点边界暂停后使用相同批次和参数恢复，rollover 后旧任务 11/12 为 EXPIRED，新任务 13/14 接续至 2026-11-10，并逐任务 retry。
- `./scripts/real-e2e-test.sh` 完整退出成功，`./scripts/verify-delivery.sh 7` 输出 `REAL E2E PASSED deliveryId=7 tasks=2 sources=14 packageBounds=8388608-10485760 pause/resume/retry/rollover verified`。

| taskId | entityCode | 来源数 | metadata 组数 | 请求总数 | deliveryArtifact 字段数 | sizeBytes | SHA-256 |
|---|---|---:|---:|---:|---:|---:|---|
| 13 | jun | 9 | 9 | 36 | 5 | 10461374 | `3f562d8fc4394a286d6ca7cb8ac78bddfb773d6a4473fcf0b330fd8b5b1db5ab` |
| 14 | jun2 | 5 | 5 | 47 | 5 | 10461889 | `cc87302fe495ebd2f65801f33cb0b6fef68314b21822e2f3b36b562ead85f8ac` |

独立 API 断言输出：

```text
API VERIFIED deliveryId=7 taskId=13 sources=9 metadata=9 requests=36 deliveryFields=5 size=10461374 sha256=3f562d8fc4394a286d6ca7cb8ac78bddfb773d6a4473fcf0b330fd8b5b1db5ab
API VERIFIED deliveryId=7 taskId=14 sources=5 metadata=5 requests=47 deliveryFields=5 size=10461889 sha256=cc87302fe495ebd2f65801f33cb0b6fef68314b21822e2f3b36b562ead85f8ac
```

## 3. 跨交付、跨厂商缓存复用

- deliveryId=8，deliveryBatch=`real-e2e-1794153301-reuse`，目标为 local-other/jun3。
- taskId=15 的 5 个 sourceDownload 输出全部 cacheHit=true，未重复源 OSS GET；最终包仍在 8–10 MiB 内。
- API 实测 sources=5、metadata=5、requests=32、deliveryFields=5、sizeBytes=10092481。
- SHA-256=`5e66cb76e7e2ec3632a3194a62cb7a57a11d6baf2f8b2c11285f2c844232b205`，主单 DELIVERED，任务 SUCCEEDED。
- 脚本输出：`CACHE REUSE PASSED deliveryId=8 taskId=15 sourceCount=5 metadataCount=5 requestCount=32 cacheHit=true size=10092481`。

## 4. 外部文件证据

```text
/data/ftp-verify/7/2026-11-10/entity-1/batch_00001.tar.gz  10461374  3f562d8fc4394a286d6ca7cb8ac78bddfb773d6a4473fcf0b330fd8b5b1db5ab
/data/ftp-verify/7/2026-11-10/entity-2/batch_00002.tar.gz  10461889  cc87302fe495ebd2f65801f33cb0b6fef68314b21822e2f3b36b562ead85f8ac
/data/ftp-verify/8/2026-11-09/entity-3/batch_00001.tar.gz  10092481  5e66cb76e7e2ec3632a3194a62cb7a57a11d6baf2f8b2c11285f2c844232b205
```

两件交付 OSS 均实际 GET 回验 size/SHA；两个主交付 FTP 账户和一个缓存复用 FTP 账户均实际上传并重新下载。上述本地 FTP 文件 SHA 与数据库/API 一致，未发现 `.partial`。

## 5. 自助复核命令

```bash
./scripts/verify-delivery.sh 7

curl -fsS http://127.0.0.1:8080/api/v1/deliveries/7/tasks | python3 -c \
  'import json,sys; tasks=json.load(sys.stdin)["items"]; [(print(t["taskId"],len(t["sourceDataIds"]),len(t["metadataIds"]),sum(map(len,t["metadataIds"])),sorted(t["artifacts"][0]["deliveryArtifact"]))) for t in tasks]'

docker run --rm -v deploy_archive_data:/data:ro alpine:3.22 \
  sh -c 'find /data/ftp-verify/7 /data/ftp-verify/8 -type f ! -name "*.partial" -exec sha256sum {} \; | sort; test -z "$(find /data/ftp-verify/7 /data/ftp-verify/8 -type f -name "*.partial" -print -quit)"'

docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T postgres \
  psql -U archive -d archive_delivery -Atc \
  "SELECT delivery_id||':'||delivery_batch||':'||status FROM archive_delivery.delivery_submit WHERE delivery_id IN(7,8) ORDER BY delivery_id; SELECT task_id||':'||execution_status||':'||size_bytes||':'||jsonb_array_length(metadata_ids) FROM archive_delivery.delivery_task WHERE delivery_id IN(7,8) ORDER BY task_id;"
```

最终 delivery 7/8 均 DELIVERED，有效任务 13/14/15 均 SUCCEEDED；原任务 11/12 保留 EXPIRED 跨日血缘。
