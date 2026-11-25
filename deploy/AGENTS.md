# deploy 模块指南

## 职责

维护生产 Compose、镜像、PowerJob MySQL 初始化、受控配置模板和 Markdown Pipeline 注册。

## 基线

- `pipelines/archive-batch-jun.md` 定义 OSS 下载、包大小和实体 Worker 容量；当前同时登记 `alibaba/jun-2/group1` 与扩展实体 `alibaba/jun/group2`，后者每轮规划容量为 100 包。`archive-delivery-jun.md` 定义批次包处理与外部交付 DAG；目标实体由提交 targets 选择。
- `pipeline.production.json`、`golden-case.json` 由注册脚本生成，权限 600，不提交、不进入镜像。
- `docker-compose.production.yml` 默认启动 PG、MySQL、PowerJob、archive-server 和 8 个 group1 Worker；Worker 共用 `ARCHIVE_WORKER_IMAGE`（默认 `archive-delivery-worker:production`），只构建一份相同镜像。`ftp-june` 仅在 `COMPOSE_PROFILES=embedded-ftp` 时启动且只开放 Compose 内网，虚拟账户由 `PROD_ENTITY_1_ACCOUNT` 注入。复用已有外部 FTP 时显式设置 `COMPOSE_PROFILES=`，不得安装重复 FTP。
- `V25__batch_delivery_baseline.sql` 维护批次基线；生产启动必须到达最新 V29（来源监控增量迁移）。
- `.env` 和 Secret 由管理员离线维护，不进入 MD、日志或证据。

## 约束

1. 一个 worker_group 只对应一台服务器的本地 archive_data；跨机不共享工作目录。Compose 各 Worker 必须继承公共 worker-environment 锚点，保留 ARCHIVE_CLEANUP_WORK_AFTER_VERIFY 等必需项；交付后须核对节点 workCopyCleaned=true，防止中间副本无限增长。
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

archive-batch 的 jun-2/group1 每轮 maxPackagesPerRound=6000，属于分配容量而非并发。生产模板按 jun-2 的 8 核 16G 配置：8 个同机 Worker 共享 ARCHIVE_MAX_RUNNING_TASKS=8，批次下载线程为 16；每次广播每个 Worker 只推进一个节点，不能只增大组内上限而保留四个实例。各 Worker 和 API JVM 默认 -Xms128m -Xmx512m，可通过 ARCHIVE_WORKER_JAVA_OPTIONS/ARCHIVE_SERVER_JAVA_OPTIONS 配置。小内存机器必须另行降低实例数、并发和堆上限。Worker environment 统一继承白名单，保留租约、成功后清理和共享 archive_data/retention 锁。FTP_ALLOW_DATE_CLEANUP=true 才开放删除权限，必须与 Pipeline cleanupOtherDeliveryDates 及专属 FTP 根配套启用；FTP 根不得包含链接，非日期目录不自动删除。

生产 Dockerfile 使用标准分层 Maven 依赖缓存，兼容交付机 classic builder，不依赖 BuildKit RUN --mount。API 镜像将前端产物复制进 src/main/resources/static 随 jar 发布，验收检查根路径和静态资源。

8 个 Worker 的 ARCHIVE_WORKER_DB_POOL_SIZE 默认为 4，通过 Spring Hikari 环境变量注入；Worker 合计最多 32 个 PG 连接，为 API、独立验收和运维保留额度，禁止八个实例沿用每池 10 连接耗尽 PG 默认 100 连接。

## 渠道监控配置

只有 archive-server 接收 ARCHIVE_INGEST_MONITOR_*，Worker 不接收 webhook。dashboard-url 必须是明确配置的 http(s) /monitor 页面，无查询参数。monitor-web 使用 monitor.nginx.conf，只代理 /monitor、assets 和限定 GET 监控路径；完整 API 8080 继续绑定回环。网关默认回环 8090，需公网查看时显式设置 ARCHIVE_MONITOR_BIND_ADDRESS/PORT；不开放交付写接口或 Pipeline 配置。

/monitor 转发独立 monitor.html，不转发完整管理台入口；开启 JS/CSS/JSON gzip，降低手机首屏下载量。页面 no-store，静态资源使用内容哈希。访问日志只记录 URI 路径，不保存企业微信附加的确认查询参数。

V29 合并快照和采样，最终仅两张表；旧 object 迁入 record 后删除，不保留旧表/视图。发布前备份来源/监控和镜像、配置，确认真实 LIST 成功且无在途交付，仅重建 API 和只读网关。V26→V28 已发布迁移不改写。存储预估价随源码注明官方链接、核对日期和地域，不复制云账号凭据或下载计价。
