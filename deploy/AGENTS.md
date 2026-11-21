# deploy 模块指南

## 职责

维护生产 Compose、镜像、PowerJob MySQL 初始化、受控配置模板和 Markdown Pipeline 注册。

## 基线

- `pipelines/archive-batch-jun.md` 定义 OSS 下载、包大小和实体 Worker 容量；`archive-delivery-jun.md` 定义批次包处理与外部交付 DAG；当前目标主机初始化实体为 `alibaba/jun-2`。
- `pipeline.production.json`、`golden-case.json` 由注册脚本生成，权限 600，不提交、不进入镜像。
- `docker-compose.production.yml` 默认启动 PG、MySQL、PowerJob、archive-server 和 group1 Worker；`ftp-june` 仅在 `COMPOSE_PROFILES=embedded-ftp` 时启动且只开放 Compose 内网，虚拟账户由 `PROD_ENTITY_1_ACCOUNT` 注入。复用已有外部 FTP 时显式设置 `COMPOSE_PROFILES=`，不得安装重复 FTP。
- `V25__batch_delivery_baseline.sql` 是当前迁移；生产启动必须到达 V25。
- `.env` 和 Secret 由管理员离线维护，不进入 MD、日志或证据。

## 约束

1. 一个 worker_group 只对应一台服务器的本地 archive_data；跨机不共享工作目录。Compose 各 Worker 的 environment 会覆盖公共 environment，ARCHIVE_CLEANUP_WORK_AFTER_VERIFY 等必需项必须显式保留，交付后须核对节点 workCopyCleaned=true，防止中间副本无限增长。
2. 配置集中进 PipelineVersion；实体机器映射只在 archive-batch assignment.entityWorkers。
3. FTP 使用 `.partial`、SHA 和原子 rename；禁止清空历史或同名不同 SHA 覆盖。
4. 生产顺序：数据库与 PowerJob → server/Worker/FTP → bootstrap → 发布两个 Pipeline → 真实预检 → 创建批次 → 独立提交与交付。
5. 只有生产预检通过才提交，fake/skipped 不算通过；禁止 `down -v`、reset 或清目录。
6. 只有 PG 为跨机访问绑定 `0.0.0.0`，云安全组按实际访问方公网 IP `/32` 放通 5432。PowerJob 管理/发现端口 7700 仅绑定 `127.0.0.1`，同机 Worker 从 `powerjob-server:7700` 发现服务，再使用 Compose 内网 `powerjob-server:10010` 调度；不得对 `0.0.0.0/0` 开放。

```bash
bash -n scripts/*.sh
python3 scripts/pipeline_registry.py validate
docker compose -f deploy/docker-compose.production.yml --env-file deploy/env.production.example config --quiet
```

## jun-2 容量与资源

archive-batch 的 jun-2/group1 每轮 maxPackagesPerRound=6000，属于分配容量而非并发。小内存交付机默认每组最多 3 个运行节点；各 Worker 和 API JVM 默认 -Xms64m -Xmx256m，可通过 ARCHIVE_WORKER_JAVA_OPTIONS/ARCHIVE_SERVER_JAVA_OPTIONS 配置。每个 Worker 显式传入租约时长。FTP_ALLOW_DATE_CLEANUP=true 才开放删除权限，必须与 Pipeline cleanupOtherDeliveryDates 及专属 FTP 根配套启用；FTP 根不得包含链接，非日期目录不自动删除。

生产 Dockerfile 使用标准分层 Maven 依赖缓存，兼容交付机 classic builder，不依赖 BuildKit RUN --mount。API 镜像将前端产物复制进 src/main/resources/static 随 jar 发布，验收检查根路径和静态资源。
