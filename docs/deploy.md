# 当前生产部署手册

## 1. 拓扑

`deploy/docker-compose.production.yml` 在交付机启动 PostgreSQL 16、MySQL 8.0.36、PowerJob Server 5.1.7、archive-server、worker-1 和隔离 ftp-june。PG、PowerJob、API 默认只绑定回环；FTP 只在 Compose 内网开放。远程 Worker 接入时通过环境变量绑定 `0.0.0.0`，安全组只允许对方公网 IP `/32`。

同机 Worker 共享本机 archive_data，不同服务器不共享目录。禁止 `down -v`、清库、清空 Bucket/FTP 或同名不同 SHA 覆盖。

## 2. V24 数据库基线

`delivery-core/src/main/resources/db/migration/V24__golden_delivery_baseline.sql` 是唯一迁移文件，包含当前全部表、列、约束、注释、JSONB 白名单和种子 Pipeline。

- 全新数据库只执行 V24，创建完整当前模型。
- 已有 V23 模型执行 V24 时跳过建表语句，不重复修改业务事实。
- server/worker 使用 `spring.flyway.ignore-missing-migrations=true` 接受已有历史，不恢复旧迁移。
- 升级前必须备份；pre-V14 旧生产库不由自动脚本处理。

```bash
docker compose -f deploy/docker-compose.production.yml --env-file deploy/.env \
  exec -T postgres psql -U archive -d archive_delivery -Atc \
  "SELECT version,success FROM archive_delivery.flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;"
```

最新成功版本必须为 24。

## 3. 受控配置

```bash
cd /opt/archive-delivery-platform
cp deploy/env.production.example deploy/.env
python3 scripts/provision-production-config.py
python3 scripts/pipeline_registry.py materialize archive-delivery-jun
chmod 600 deploy/.env deploy/pipeline.production.json deploy/golden-case.json
```

`pipelines/*.md` 是唯一定义来源，生成的 JSON 不提交。管理员在 `.env` 配置源/目标 OSS AK/SK 和独立服务密码；生产发布时以当前 `.env` 刷新 OSS/FTP 凭据。凭据不进入 MD、镜像、日志或证据。

## 4. 一键执行

```bash
./scripts/production-registry.sh archive-delivery-jun
# 或全部注册 case
./scripts/production-registry.sh all
```

执行顺序：V24 → PowerJob 通用调度 → 不可变 PipelineVersion → 来源/实体 → 真实预检 → 提交 → 有界等待 → 独立 OSS/FTP 验证和证据。pre-V14 旧生产库阻断，不运行 reset。

生产流程：源 OSS 下载并保留 → merge 输出请求目录 → repair 按 entityCode 修复并只最终封包一次 → qualify → OSS SDK PUT/HEAD/GET → FTP 上传下载核验。生产 gzipCompressLevel=5，包大小和最终 SHA 仍须通过原验收边界。

## 5. 证据与运维

成功证据位于 `docs/evidence/<deliveryBatch>/`，必须包含实际 deliveryId/taskId、PipelineVersion、V24、节点 locator、外部 URI、size/SHA 和复核命令。日常磁盘、日志、认证、BLOCKED、暂停续跑、备份和回退见 `production-operations.md`。AI 新增 Pipeline 和 CI review 见 `pipeline-maintenance.md`。
