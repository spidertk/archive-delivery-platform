# 部署与升级手册

## 拓扑与固定版本

`deploy/docker-compose.yml` 启动 PostgreSQL 16、MySQL 8.0.36、PowerJob Server 5.1.7、业务 server 和三个 Worker。业务 PG、PowerJob MySQL、工作文件分别使用持久卷。PG、PowerJob 与 API 端口仅绑定 `127.0.0.1`。

PowerJob 初始化使用仓库内 `deploy/powerjob-mysql/01schema.sql`。三个任务由 `bootstrap-powerjob.sh` 幂等创建：

- `archive-discovery`：STANDALONE，30 秒，`DiscoveryProcessor`。
- `archive-package-tick`：BROADCAST，10 秒，`PackageTickProcessor`。
- `archive-delivery-rollover`：STANDALONE，60 秒，`DeliveryRolloverProcessor`。

Worker 使用同一 app `archive-delivery-mvp`，分别设置 `ARCHIVE_LINE_ID=1/2/3`、唯一 HOSTNAME 和 `POWERJOB_SERVER_ADDRESS=powerjob-server:7700`。生产必须 `ARCHIVE_DEBUG_LOOP=false`。

## 外部服务

- 源 Bucket：`junflow-ysai-source-hz-20260906`。
- HZ Bucket：`hz-storage-20261013-6vmlk`；SG Bucket：`sg-storage-20261013-6vmlk`。Endpoint、bucket 和 AK/SK 维护在 Pipeline 对应 OSS 节点，经 `configure-pipeline.sh` 写入受控版本表；API、日志和看板不得明文展示。
- CRR 必须覆盖 HZ 到 SG 的交付前缀，并保留 size 与 `x-oss-meta-sha256` metadata。默认单包等待 180 秒，超时上限 7200 秒。
- 将 `nodes/shell/sg_download.sh` 和 `publish_package.sh` 安装到 FTP `/opt/archive-delivery/bin/`，权限 0755。Worker 只能调用该固定路径。FTP 根目录固定 `/srv/maas-sftp/maasData/archive`，禁止下载前清空目录。

启动顺序：PG/MySQL → PowerJob Server → 业务 server → 三 Worker → bootstrap → `configure-pipeline.sh` 上线节点配置 → preflight。检查 `docker compose -f deploy/docker-compose.yml ps`、PowerJob 三 Worker 和任务 instance 日志，以及 `curl -f http://127.0.0.1:8080/actuator/health`。空调用成功不等于业务 DELIVERED。

## 升级与回滚

1. 备份本地 PG：`docker compose -f deploy/docker-compose.yml exec -T postgres pg_dump -U archive archive_delivery > archive-backup.sql`。
2. 停止新 claim：禁用三个 PowerJob 任务，等待 RUNNING 节点结束，不直接删除租约。
3. 备份后 `./scripts/mvn.sh -q -DskipTests package`，再 `./scripts/mvp-up.sh` 和 `./scripts/bootstrap-powerjob.sh`。
4. 核对 Flyway、健康检查与看板，再恢复任务。已开始 run 继续读取其绑定的 PipelineVersion；调参必须复制草稿、上线新版本并切换数据源绑定，已上线定义不可修改。

回滚使用旧镜像和备份，不清空当前 run。只有明确放弃整个本地 MVP 环境时，才允许 `docker compose -f deploy/docker-compose.yml down -v`；该命令会删除本地卷，严禁用于生产 FTP 或旧业务 PG/Redis。没有生产 FTP 清目录命令。
