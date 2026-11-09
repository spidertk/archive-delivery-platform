# V20 原始 artifact、data_id metadata 与跨日 locator 真实验收记录

> 日期：2026-11-09
> 模式：本地实现，真实 Compose、真实源/交付 OSS、本地 FTP
> 结果：通过
> 说明：本记录替代 `2026-11-09-v19-source-metadata-compact-artifact-verification.md` 的 deliveryArtifact 和 requestId metadataIds 口径

## 1. 当前模型与标准回归

- 数据库最新 Flyway 版本为 V20。delivery_task.artifacts 每项严格为 dataId/sourceArtifact 两字段，不保存 deliveryArtifact。
- sourceArtifact 逐字段等于 source_data.artifact；metadata_ids 是与 delivery_mapping、artifacts、sourceDataIds 完全同序的 source_data.data_id 正整数数组，不保存 requestId。
- 最终包 size/SHA 从成功 repair/OSS 节点 output_locator 读取；requestId 和 originalPath 映射仅保留在 repair manifest。
- 未 repair 任务在 V20 迁移中保持 metadata_ids=[]；跨日新 taskId 复制 rolloverStartNode 之前各节点最新成功 locator，新 process_id 由数据库生成。
- 六个 DAG 节点保留 SLF4J 开始/成功日志，跨日复制记录 oldTaskId/newTaskId/rolloverStartNode/copiedNodes。
- 全量 Maven 测试、Python unittest 6 项、前端 Vitest 和生产构建、Shell 语法、Compose 配置及 `git diff --check` 均通过；干净库实际执行 V1–V20 迁移成功。
- 本地 PipelineVersion versionId=7，selection.limit=32、minPackageBytes=8388608、maxPackageBytes=10485760；真实预检列出 32 个候选源包，总字节数 79,752,551。

## 2. 主交付、跨日与 data_id 实证

- deliveryId=10，deliveryBatch=`real-e2e-1794156800`，targets 为 alibaba/jun 与 alibaba/jun2，各 targetCount=1。
- 共分配 14 个真实 OSS 来源；独立验收再次 GET 每个来源并核对 size/SHA。
- 原任务 20/21 在 FTP 停止时进入 BLOCKED；主单节点边界暂停后使用相同批次和参数恢复，rollover 后旧任务 20/21 为 EXPIRED，新任务 22/23 接续至 2026-11-11，并逐任务 retry。
- 任务 22/23 各继承 sourceDownload、mergePackages、repair、qualify 四个最新成功 locator，targetUpload 与 ftpDownloadVerify 均最终成功。
- `./scripts/real-e2e-test.sh` 完整退出成功，`./scripts/verify-delivery.sh 10` 输出 `REAL E2E PASSED deliveryId=10 tasks=2 sources=14 packageBounds=8388608-10485760 pause/resume/retry/rollover verified`。

| taskId | entityCode | metadataIds（等于 sourceDataIds） | 请求目录数（仅 repair 事实） | sizeBytes | SHA-256 |
|---|---|---|---:|---|
| 22 | jun | `[59,60,61,62,63,64,65,66,67]` | 36 | 10461396 | `ce483a7637d1f30981f4297ca26cf46a5fce24fc65d6579c25b25ef15c400f40` |
| 23 | jun2 | `[68,69,70,71,72]` | 47 | 10461896 | `b5b6b9112caf7569f171fe5125fae42c37fa90d70b53d8ab81bec364526f5437` |

独立 API 断言确认：

```text
API V20 VERIFIED deliveryId=10 taskId=22 metadataIds=[59, 60, 61, 62, 63, 64, 65, 66, 67] artifactFields=dataId+sourceArtifact size=10461396 sha256=ce483a7637d1f30981f4297ca26cf46a5fce24fc65d6579c25b25ef15c400f40
API V20 VERIFIED deliveryId=10 taskId=23 metadataIds=[68, 69, 70, 71, 72] artifactFields=dataId+sourceArtifact size=10461896 sha256=b5b6b9112caf7569f171fe5125fae42c37fa90d70b53d8ab81bec364526f5437
```

每个 artifacts 项的键集合严格为 `{dataId,sourceArtifact}`，没有 deliveryArtifact、requestId 或嵌套 metadata 数组。

## 3. 跨交付、跨厂商缓存复用

- deliveryId=11，deliveryBatch=`real-e2e-1794156800-reuse`，目标为 local-other/jun3。
- taskId=24 的 5 个 sourceDownload 输出全部 cacheHit=true，未重复源 OSS GET；最终包仍在 8–10 MiB 内。
- metadataIds/sourceDataIds=`[73,74,75,76,77]`，sizeBytes=10092447。
- SHA-256=`a6602db0ace6920c31db82535d2300d7547f06b440261e80fb3f1286a1d849e9`，主单 DELIVERED，任务 SUCCEEDED。
- 脚本输出：`CACHE REUSE PASSED deliveryId=11 taskId=24 sourceCount=5 metadataCount=5 requestCount=32 cacheHit=true size=10092447`。

## 4. 外部文件证据

```text
/data/ftp-verify/10/2026-11-11/entity-1/batch_00001.tar.gz  10461396  ce483a7637d1f30981f4297ca26cf46a5fce24fc65d6579c25b25ef15c400f40
/data/ftp-verify/10/2026-11-11/entity-2/batch_00002.tar.gz  10461896  b5b6b9112caf7569f171fe5125fae42c37fa90d70b53d8ab81bec364526f5437
/data/ftp-verify/11/2026-11-10/entity-3/batch_00001.tar.gz  10092447  a6602db0ace6920c31db82535d2300d7547f06b440261e80fb3f1286a1d849e9
```

两件交付 OSS 均实际 GET 回验 size/SHA；两个主交付 FTP 账户和一个缓存复用 FTP 账户均实际上传并重新下载。上述本地 FTP 文件 SHA 与数据库/API 一致，未发现 `.partial`。

## 5. 自助复核命令

```bash
./scripts/verify-delivery.sh 10

curl -fsS http://127.0.0.1:8080/api/v1/deliveries/10/tasks | python3 -c \
  'import json,sys; tasks=json.load(sys.stdin)["items"]; [(print(t["taskId"],t["metadataIds"]==t["sourceDataIds"],t["metadataIds"],list(t["artifacts"][0]),t["sha256"])) for t in tasks]'

docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T postgres \
  psql -U archive -d archive_delivery -Atc \
  "SELECT task_id||':'||count(*) FILTER(WHERE node_id IN('sourceDownload','mergePackages','repair','qualify') AND status='SUCCEEDED')||':prerequisites,'||count(*) FILTER(WHERE node_id='targetUpload' AND status='SUCCEEDED')||':uploads' FROM archive_delivery.delivery_node WHERE task_id IN(22,23) GROUP BY task_id ORDER BY task_id;"

docker run --rm -v deploy_archive_data:/data:ro alpine:3.22 \
  sh -c 'find /data/ftp-verify/10 /data/ftp-verify/11 -type f ! -name "*.partial" -exec sha256sum {} \; | sort; test -z "$(find /data/ftp-verify/10 /data/ftp-verify/11 -type f -name "*.partial" -print -quit)"'
```

最终 delivery 10/11 均 DELIVERED，有效任务 22/23/24 均 SUCCEEDED；原任务 20/21 保留 EXPIRED 跨日血缘。
