# V17 复合实体与源缓存真实验收记录

> 日期：2026-11-08
> 模式：本地实现，真实 Compose、真实源/交付 OSS、本地 FTP
> 结果：历史记录；仅证明 size/SHA 一致，来源数与包大小验收不足，不作为当前 8–10 MiB 交付通过依据
> 授权：用户明确授权本地清库及真实 OSS/FTP 写入

## 1. 启动、迁移与标准回归

- `scripts/real-e2e-test.sh` 先以 `2026-11-06` 授权完成 V13，再以 `2026-11-07` 授权完成 V14–V17。
- PostgreSQL 16.15 查询 `flyway_schema_history` 最新记录为 `17:true`；API `/actuator/health` 返回 `{"status":"UP"}`。
- PipelineVersion=1，来源为 `oss://sg-storage-20261013-6vmlk/internal/gpt-delta-seven-20261105-accepted-20261106-v1/products/`，maxPackageBytes=10485760。
- 实体为 `alibaba/jun`、`alibaba/jun2`、`local-other/jun3`，证明 vendorCode 可复用且实体按复合业务键定位。
- 标准回归通过：Python unittest 6 项、全量 Maven 测试、前端 Vitest 1 项及生产构建、Shell 语法和 Compose 配置检查。额外分配测试覆盖 10 个 2 MiB 源包形成两个 10 MiB 包；节点测试证明缓存命中不调用 OSS 下载服务。

启动与迁移复核：

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env ps
docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs --tail=100 archive-server
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T postgres \
  psql -U archive -d archive_delivery -Atc \
  "SELECT version||':'||success FROM archive_delivery.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1"
```

## 2. 主交付：同厂商两个实体

- deliveryId=1，deliveryBatch=`real-e2e-1794141493`，初始 deliveryDate=2026-11-09，cutoffAt=2026-11-09T18:00:00+08:00。
- targets 为 `alibaba/jun targetCount=1` 与 `alibaba/jun2 targetCount=1`，提交显式携带 cutoffAt。
- 三份来源各分配一次，不切分：原 dataId=1/2 分给 jun，dataId=3 分给 jun2；复用交付的新 dataId=4/5/6 对应相同三份 URI+版本。
- 原任务 1/2 在 FTP 停止时进入 BLOCKED，主单在节点边界 PAUSED；相同批次和完全相同参数（含 cutoffAt）再次提交恢复原 deliveryId。
- rollover 后旧任务 1/2 为 EXPIRED，新任务为 3/4，新日期为 2026-11-10；恢复 FTP 后逐任务 retry。

最终有效任务：

| taskId | entityCode | file | sources | metadataIds | sizeBytes | SHA-256 |
|---|---|---|---|---:|---:|---|
| 3 | jun | batch_00001.tar.gz | 2 | 6 | 2171820 | `5acd7f75c1ec4b184eeaa94ad365d72db2f84129090e88e1614e3663be2000ce` |
| 4 | jun2 | batch_00002.tar.gz | 1 | 13 | 2150714 | `f203d6bb5913722ad54137f8b54c43a498ecf0ebab632e85680c9737597f529a` |

`verify-delivery.sh 1` 独立执行并通过：三份源 OSS 实际 GET，两件交付 OSS 实际 GET，两个 FTP 账户独立上传/下载；核对 sourceArtifact=source_data.artifact、original_file_name/size/SHA、mapping、metadataIds、repair entityCode、外部 size/SHA、merge 目录和无 `.partial`。主单最终 DELIVERED，任务 3/4 均 SUCCEEDED。

## 3. 跨交付、跨厂商缓存复用

- deliveryId=2，deliveryBatch=`real-e2e-1794141493-reuse`，目标为 `local-other/jun3 targetCount=1`，来源 URI 与主交付相同。
- taskId=5 关联三份新 source_data（dataId=4/5/6），每个 sourceArtifact 均等于本交付 source_data.artifact。
- `sourceDownload` 三个输出全部为 `cacheHit=true`，实际只进行本地校验复制，没有重复源 OSS GET；其他执行组无此本机缓存时才回退 OSS。
- 任务 5 形成 batch_00001.tar.gz，metadataIds=19，sizeBytes=4323054，SHA-256=`bb3df84e30b2523f889dff3fbefcc48dd3bb5b2d34b8f6ec91ef8cebe0c8e7be`。
- 交付 OSS 完成 PUT/HEAD/GET，entity3 FTP 上传并重新下载成功；主单最终 DELIVERED，任务 5 为 SUCCEEDED。

缓存事实复核：

```bash
curl -fsS http://127.0.0.1:8080/api/v1/tasks/5/timeline | python3 -c \
  'import json,sys; t=json.load(sys.stdin); n=next(n for n in t["nodes"] if n["nodeType"]=="oss.download" and n["status"]=="SUCCEEDED"); print([(s["dataId"],s["cacheHit"],s["sizeBytes"],s["sha256"]) for s in n["outputLocator"]["sources"]])'
```

期望三个 dataId 的 cacheHit 均为 True。

## 4. 文件与数据库自助复核

本次 FTP 文件：

```text
/data/ftp-verify/1/2026-11-10/entity-1/batch_00001.tar.gz  2171820  5acd7f75c1ec4b184eeaa94ad365d72db2f84129090e88e1614e3663be2000ce
/data/ftp-verify/1/2026-11-10/entity-2/batch_00002.tar.gz  2150714  f203d6bb5913722ad54137f8b54c43a498ecf0ebab632e85680c9737597f529a
/data/ftp-verify/2/2026-11-09/entity-3/batch_00001.tar.gz  4323054  bb3df84e30b2523f889dff3fbefcc48dd3bb5b2d34b8f6ec91ef8cebe0c8e7be
```

只读命令：

```bash
docker run --rm -v deploy_archive_data:/data:ro alpine:3.22 \
  sh -c 'find /data/ftp-verify/1/2026-11-10 /data/ftp-verify/2/2026-11-09 -type f ! -name "*.partial" -exec sha256sum {} \;; test -z "$(find /data/ftp-verify/1/2026-11-10 /data/ftp-verify/2/2026-11-09 -type f -name "*.partial" -print -quit)"'

docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T postgres \
  psql -U archive -d archive_delivery -Atc \
  "SELECT delivery_id||':'||delivery_batch||':'||status FROM archive_delivery.delivery_submit ORDER BY delivery_id; SELECT task_id||':'||delivery_id||':'||entity_code||':'||execution_status||':'||new_file_name||':'||size_bytes||':'||(artifacts->0->'deliveryArtifact'->>'sha256') FROM archive_delivery.delivery_task WHERE execution_status='SUCCEEDED' ORDER BY task_id;"
```

最终输出为 delivery 1/2 均 DELIVERED，任务 3/4/5 均 SUCCEEDED。历史 FTP 文件未清理，本次检查未发现 `.partial`。
