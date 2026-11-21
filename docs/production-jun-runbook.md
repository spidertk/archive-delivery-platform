# 生产 jun 自动流水线手册

## 1. 当前范围与事实

当前生产实体为 `alibaba/jun`，主交付机运行 4 个 group1 Worker 和隔离 FTP 容器。archive-batch 全量下载来源 OSS，按不超过 100 MiB 顺序规划批次包并按 entityWorkers 容量分配实体与实际 Worker，不设置合并包下限，剩余小尾包正常交付；archive-delivery 每次按 targetCount 消费未分配包。gzip 级别为 5，业务时区 Asia/Shanghai，截止时间为 deliveryDate 次日 02:00。

当前验证来源：

```text
oss://sg-storage-20261013-6vmlk/internal/gpt-sse-wire-fix-20261112-v1/products/
```

目标路径：

```text
OSS: june/<deliveryDate>-<deliveryBatch>-june/batch_NNNNN.tar.gz
FTP: /srv/maas-sftp/maasData/archive/june/<deliveryDate>/batch_NNNNN.tar.gz
```

包号在 BATCH_CREATE 内连续。同一批次每次 BATCH_SUBMIT 创建不同 deliveryId 并领取不同包，不能重复消费或重名覆盖。FTP 路径只保留 deliveryDate，同日不同批次会共享目录，必须保持包名唯一；同名不同 SHA 会阻断而不是覆盖。BATCH_CREATE 只有在全部规划包交付后才为 DELIVERED；只消费部分包时保持 PROCESSING。

## 2. 本地自动流水线

```bash
./scripts/local-pipeline.sh
```

入口执行 Markdown 注册、Python、Maven、前端和 Compose 逻辑验证，不访问真实 OSS/FTP。`GoldenPipelineDefinitionTest` 只证明处理器注册和 DAG，不是生产证据。

## 3. 生产服务器准备

将同一经过测试的代码放到 `/opt/archive-delivery-platform`，准备受控配置：

```bash
cd /opt/archive-delivery-platform
cp deploy/env.production.example deploy/.env
python3 scripts/provision-production-config.py
chmod 600 deploy/.env
```

核对数据库、PowerJob、OSS 和 FTP 凭据均为独立强随机值，并配置：

- `COMPOSE_PROFILES=embedded-ftp`，当前 jun 使用内置 ftp-june；如复用已有外部 FTP 则改为空值并配置其实际账户。
- `PROD_ENTITY_1_VENDOR_CODE=alibaba`、`PROD_ENTITY_1_ENTITY_CODE=jun`、`PROD_ENTITY_1_ACCOUNT=june`。
- `PROD_ENTITY_1_HOST=ftp-june`、`ENTITY_1_WORKER_GROUP=group1`。
- `PROD_JUNE_HOST_ROOT=/srv/maas-sftp/maasData/archive/june`。
- archive-batch MD 中 jun 的 entityWorkers 与实际在线 Worker 一致；新增机器时必须配置其 workerGroup 和 maxPackagesPerRound，不能分配给未部署组。

不得把 `.env`、真实 definition、AK/SK 或 FTP 密码复制到文档、证据或 Git。

## 4. 一键部署、创建批次、两次提交和验收

```bash
cd /opt/archive-delivery-platform
PROD_DELIVERY_BATCH=20261115 PROD_DELIVERY_DATE=2026-11-15 \
PROD_TARGET_COUNT=100 PROD_SUBMISSION_COUNT=2 \
PROD_OSS_URI='oss://sg-storage-20261013-6vmlk/internal/gpt-sse-wire-fix-20261112-v1/products/' \
./scripts/production-registry.sh archive-batch-jun
```

脚本顺序：

1. 启动并到达 Flyway V25，初始化三个 PowerJob 通用调度。
2. 发布 archive-batch 与 archive-delivery 不可变版本，登记来源和 alibaba/jun。
3. 真实检查来源 OSS、目标 Bucket 和 FTP；失败不创建批次。
4. POST `/api/v1/delivery-batches` 创建一次批次，有界等待全量下载和规划完成。
5. 校验规划包数至少为两次提交合计 200，随后两次 POST `/api/v1/deliveries`，每次 targetCount=100。
6. 每次分别等待 100 个任务 SUCCEEDED、主单 DELIVERED，并独立执行 SOURCE_GET、TARGET_GET、FTP_GET、SIZE_SHA、NO_PARTIAL。
7. 最终核对两次任务合计 200、批次 DELIVERED 包数 200、原始文件关系数、包名区间、FTP 文件数和无 `.partial`。

固定批次复用时，相同 BATCH_CREATE 参数返回原批次；每次 BATCH_SUBMIT 都会继续消费后续未分配包。剩余包不足时准备状态变为 BLOCKED，不能 SQL 绕过。

## 5. 证据清单

| 文件 | 用途 |
| --- | --- |
| `create-batch*.json`、`batch-api.json` | 批次创建参数、实际 batchDeliveryId 和规划数量 |
| `batch-packages-planned.json`、`batch-packages-final.json` | 全部包与原始文件、实体、Worker、消费提交和状态 |
| `submit-<n>.json`、`submit-response-<n>.json` | 每次提交参数和实际 deliveryId |
| `delivery-summary-<n>.json`、`tasks-api-<n>.json` | 每次 100 个任务的最终状态 |
| `external-verification-<n>.log` | 每次独立 OSS/FTP 复核 |
| `batch-summary.json`、`database-facts.txt` | 批次总状态、数量、迁移和任务聚合 |
| `ftp-sha256.txt`、`ftp-partial.txt` | 最终 FTP SHA 与无半成品证明 |

只有脚本输出 `Production batch verification passed` 且证据完整，才能认定本次生产验证通过。

## 6. 本次生产验证与历史 case

2026-11-15 批次 `20261115` 已完成真实验证：批次创建 `deliveryId=5`，第一次提交 `deliveryId=6`、第二次提交 `deliveryId=7` 均为 100/100 `DELIVERED`，两次实际消费原始包 3,322 + 3,831 = 7,153 个。最终 FTP 恰好 200 包，`.partial=0`，SOURCE_GET、TARGET_GET、FTP_GET、SIZE_SHA、NO_PARTIAL 全部通过。批次总单为 `PROCESSING`，因为仍有 1,107 个规划包未消费。完整证据见 `evidence/20261115/verification-report.md`。

历史交付证据保留在 `docs/evidence/`，仅用于审计，不代表当前批次流程已验证。当前结果必须以新批次的实际 ID 和外部文件事实为准。
