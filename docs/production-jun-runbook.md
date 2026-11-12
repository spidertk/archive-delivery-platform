# 生产 jun 自动流水线手册

## 1. 当前范围与事实

当前生产 case 使用一台交付机同时运行主服务、group1 Worker 和隔离 FTP 容器，实体为 `alibaba/jun`，每次 `PROD_TARGET_COUNT=1` 生成一件 8–10 MiB 交付包。FTP 容器只开放 Compose 内网，宿主机 FTP 数据目录为 `/srv/maas-sftp/maasData/archive/june`。

源 OSS：

```text
oss://sg-oss-external-channel-source/jayden-incoming/maasData/archive/YY：@feiji777888/2026-11-09/
```

源路径中的 `YY：` 使用全角冒号，必须保留，不能改成 `%EF%BC%9A` 字面量或半角冒号。源桶禁止匿名访问，必须配置可读 AK/SK。目标 OSS 为 `sg-storage-20261013-6vmlk`，生产 Pipeline 使用 `{accountName}` 分隔目录：jun 的账户为 `june`，后续 jun2 可使用 `june-2`；包名仍在整个 delivery_id 内全局连续。repair 压缩级别由不可变 Pipeline 固定为 5，只改变 gzip 大小与速度，不降低 8–10 MiB 验收标准。

目标路径：

```text
OSS: june/<deliveryDate>-<deliveryBatch>-june/batch_NNNNN.tar.gz
FTP: /srv/maas-sftp/maasData/archive/june/<deliveryDate>/<deliveryBatch>/batch_NNNNN.tar.gz
```

本文不表示真实 case 已通过。最终结果以 `docs/evidence/<deliveryBatch>/` 中的实际 deliveryId/taskId、数据库事实和 OSS/FTP size/SHA 为准。

## 2. 本地自动流水线

本地完整入口：

```bash
cd /path/to/archive-delivery-platform
./scripts/local-pipeline.sh
```

该入口调用 `real-e2e-test.sh`，会使用真实本地 Compose、真实 OSS/FTP，并双确认清理本地运行事实。禁止在生产服务器执行。它会验证 V1–V23、两个核心实体、暂停续交、retry、跨日、缓存复用和外部 size/SHA。

只做标准回归时执行：

```bash
python3 -m unittest nodes/python/test_archive_repair.py -v
./scripts/mvn.sh test
cd web && npm ci && npm run test && npm run build
bash -n scripts/*.sh
```

## 3. 生产服务器准备

生产脚本不替管理员安装 Docker。首次在 Ubuntu 交付机安装并启动：

```bash
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y docker.io docker-compose-v2
systemctl enable --now docker
docker --version
docker compose version
```

将同一经过测试的代码放到 `/opt/archive-delivery-platform`，然后准备受控配置：

```bash
cd /opt/archive-delivery-platform
cp deploy/env.production.example deploy/.env
cp deploy/pipeline.production.example.json deploy/pipeline.production.json
python3 scripts/provision-production-config.py
chmod 600 deploy/.env deploy/pipeline.production.json
```

初始化脚本只为仍为占位值的数据库、PowerJob 和 FTP 生成独立随机密码，已有值不会被覆盖。随后编辑 `deploy/.env`，必须移除全部 `replace-*`，并核对：

- `ARCHIVE_DB_PASSWORD`、`POWERJOB_MYSQL_ROOT_PASSWORD`、PowerJob 管理和应用密码均为独立强随机值；`POWERJOB_ADMIN_INITIAL_PASSWORD` 仅用于首次登录，bootstrap 会立即轮换为随机密码。
- `OSS_SOURCE_ACCESS_KEY_ID/SECRET` 只能读取指定源前缀。
- `TARGET_OSS_ACCESS_KEY_ID/SECRET` 只能写指定目标 Bucket 的交付前缀。
- `PROD_FTP_ENTITY_1_PASSWORD` 为隔离 FTP 容器的独立密码，不使用 SSH root 密码。
- `PROD_ENTITY_1_VENDOR_CODE=alibaba`、`PROD_ENTITY_1_ENTITY_CODE=jun`、`PROD_ENTITY_1_ACCOUNT=june`、`PROD_ENTITY_1_HOST=ftp-june`。
- `ENTITY_1_WORKER_GROUP=group1`，`PROD_JUNE_HOST_ROOT=/srv/maas-sftp/maasData/archive/june`。

不得把 `.env`、真实 Pipeline definition、AK/SK、FTP 密码复制到文档、证据目录或 git。

## 4. 一键部署、预检、提交和验收

```bash
cd /opt/archive-delivery-platform
./scripts/production-pipeline.sh
```

脚本顺序：

1. 启动 PostgreSQL，识别 Flyway 状态。
2. 空库按 V1–V13、V14–V23 两阶段迁移；已有 V14+ 数据库只执行增量。已有 V14 以下生产库直接停止，不自动清库。
3. 启动 MySQL、PowerJob、archive-server、worker-1 和 ftp-june，清除迁移授权对应的容器环境。
4. 初始化三个 PowerJob 通用调度，发布不可变 PipelineVersion，登记来源和 alibaba/jun。
5. 使用 OSS SDK 检查源候选、目标 Bucket，并真实登录 FTP。预检失败不会提交。
6. 默认使用次日作为 deliveryDate，生成全局唯一 `PROD-JUN-*` 批次，提交 jun targetCount=1。
7. 有界等待 DELIVERED；BLOCKED 或超时非零退出。
8. 独立 GET 全部入选源和交付 OSS，重新登录 FTP 下载，核对 mapping、metadataIds、size/SHA 和 `.partial`。
9. 保存脱敏证据到 `docs/evidence/<deliveryBatch>/`。

需要固定批次复跑时，在受控 `.env` 设置 `PROD_DELIVERY_DATE`、`PROD_DELIVERY_BATCH`。相同批次只能使用完全相同规范请求；已交付批次不要重复写外部文件。需要改包数时设置 `PROD_TARGET_COUNT`，首次生产 case 保持 1。

## 5. 证据清单

成功目录包含：

| 文件 | 用途 |
| --- | --- |
| `submit.json`、`submit-response.json` | 提交参数和实际 deliveryId/cutoffAt，不含 Secret |
| `delivery-summary.json`、`delivery-api.json`、`tasks-api.json` | 最终主单和任务状态 |
| `compose-services.txt`、`application.log` | 真实容器和节点日志 |
| `external-verification.log` | OSS SDK 独立 GET、FTP 下载和血缘校验 |
| `database-facts.txt` | Flyway 绑定主单、任务、节点和 locator 事实 |
| `ftp-sha256.txt` | 宿主机 FTP 最终文件 SHA |
| `ftp-partial.txt` | 必须为空 |

只有脚本输出 `Production verification passed` 且上述外部证据完整，才能写生产验收通过。PowerJob 绿色实例、API ACCEPTED、 skipped 或预检成功都不是交付成功。

## 6. 已完成生产 case

2026-11-11 已完成 alibaba/jun × 1 真实生产验证：deliveryId=2、taskId=2、PipelineVersion=5、Flyway=23，最终主单 DELIVERED。产物为 8,469,625 字节，SHA256 为 `a16d8f829c64e11fa1eb6f1e24630fa3925ea15264f1bd664c581bd2bc312e87`。完整证据见 `evidence/PROD-JUN-20261111-205140/verification-report.md`。
