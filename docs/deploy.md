# 当前生产部署手册

## 1. 拓扑

`deploy/docker-compose.production.yml` 启动 PostgreSQL 16、MySQL 8.0.36、PowerJob Server 5.1.7、archive-server 和 worker-1/2/3/4。隔离 ftp-june 仅在 `COMPOSE_PROFILES=embedded-ftp` 时启动；复用已有 FTP 时设置 `COMPOSE_PROFILES=` 并沿用其账户配置，不重复安装。PG 为跨机访问绑定 `0.0.0.0`，云安全组按实际访问方公网 IP `/32` 放通。PowerJob 管理/发现端口 7700 和 API 只绑定回环，同机 Worker 从 Compose 内网 `powerjob-server:7700` 发现服务，再通过 `powerjob-server:10010` 执行调度协议；内置 FTP 只在 Compose 内网开放。

同机 Worker 共享本机 archive_data，不同服务器不共享目录。禁止 `down -v`、清库、清空 Bucket/FTP 或同名不同 SHA 覆盖。

## 2. V25 批次数据库基线

当前迁移为 `delivery-core/src/main/resources/db/migration/V25__batch_delivery_baseline.sql`。

- BATCH_CREATE 保存 OSS 批次总单，BATCH_SUBMIT 保存每次独立交付提交。
- source_data.unique_key 固定为 sha256；delivery_batch 保存原始文件到合并包、实体、Worker 和消费提交的关系。
- delivery_mapping 和 delivery_task.metadata_ids 已删除，旧线上关系在迁移时转入 delivery_batch。
- 升级前必须备份；server/worker 使用 ignore-missing-migrations 接受已有历史。

```bash
docker compose -f deploy/docker-compose.production.yml --env-file deploy/.env \
  exec -T postgres psql -U archive -d archive_delivery -Atc \
  "SELECT version,success FROM archive_delivery.flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"
```

最新成功版本必须为 25。

## 3. 受控配置

```bash
cd /opt/archive-delivery-platform
cp deploy/env.production.example deploy/.env
python3 scripts/provision-production-config.py
chmod 600 deploy/.env
```

`pipelines/*.md` 是唯一定义来源。archive-batch 独占 maxPackageBytes 和 entityWorkers 容量映射，不设置合并包下限；archive-delivery 只定义交付 DAG、目标和凭据。真实 Secret 只在发布时从 `.env` 注入，不进入 Git、日志或证据。

## 4. 一键执行

```bash
./scripts/production-registry.sh archive-batch-jun
# 或全部注册 case
./scripts/production-registry.sh all
```

执行顺序：V25 → PowerJob 通用调度 → 发布 archive-batch/archive-delivery → 来源/实体 → 真实预检 → 创建并规划一次 OSS 批次 → 每次独立提交和消费 → 有界等待 → 独立 OSS/FTP 验证和证据。

批次创建执行真实 OSS 下载和装箱；交付执行 merge 请求目录、repair 按 entityCode 修复并只最终封包一次、qualify、OSS SDK PUT/HEAD/GET、FTP 上传下载核验。生产 gzipCompressLevel=5。

## 5. 证据与运维

成功证据位于 `docs/evidence/<deliveryBatch>/`，必须包含 batchDeliveryId、每次 deliveryId/taskId、两个 PipelineVersion、V25、节点 locator、外部 URI、size/SHA、原始文件/批次包/任务数量和复核命令。日常运维见 `production-operations.md`，AI review 见 `pipeline-maintenance.md`。
