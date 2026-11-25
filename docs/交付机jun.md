# 交付机 jun / group2 扩展部署手册

本手册把 `docs/服务器信息/任务机扩展部署.md` 的通用步骤实例化为实体 `alibaba/jun`、Worker 组 `group2`。主库使用 `docs/交付机jun-2.md` 所描述的 jun-2 交付机；新机器只运行 Worker，任务状态、PipelineVersion、实体登记和租约事实都写入主库。

## 固定边界

| 项目 | 本次配置 |
| --- | --- |
| 主库/主机 | `8.222.228.188` |
| 实体 | `vendorCode=alibaba`、`entityCode=jun` |
| Worker 组 | `group2` |
| Worker 服务 | 新机器本地 `worker-4`、`worker-5`、`worker-6`，每组总并发 3 |
| 每轮规划容量 | `100` 包，由主库 `archive-batch` Pipeline 固化 |
| FTP | 使用 group2 对应 FTP 机器上已有的 FTP；不安装、不重置、不生成账户或密码 |
| 数据库 | 新机器通过主库 PostgreSQL `archive_delivery` 连接 |
| PowerJob | 新机器通过 SSH 隧道发现主库 PowerJob；不部署本地 PowerJob Server |

group1 和 group2 使用不同 FTP 机器。group2 的 FTP 主机、端口、根目录、账户和密码必须从 group2 对应 FTP 机器及主库实体配置核对后原样填写；不要读取或复用 jun-2/group1 的 FTP 定位。本文不记录密码，也不允许为了扩展机重置或生成账户。两台 FTP 的文件和日期目录互不清理，扩展机使用独立 `archive_data` 卷，不能与 jun-2 共享工作目录。

## 一、准备变量和网络

在执行机上只设置非 Secret 变量；不要把密码写入 shell 历史、文档或命令行：

```bash
PRIMARY_HOST=8.222.228.188
WORKER_HOST=<扩展交付机公网 IP>
WORKER_GROUP=group2
VENDOR_CODE=alibaba
ENTITY_CODE=jun
TASK_CONCURRENCY=3
MAX_PACKAGES_PER_ROUND=100
FTP_HOST=<group2 对应 FTP 主机>
FTP_PORT=21
FTP_ROOT_PATH=/
```

安全组和防火墙按以下方向放行：

- 主库 PostgreSQL `5432/tcp` 只允许 `WORKER_HOST/32`；不要向公网开放。
- Worker 的 PowerJob Worker 端口 `27777`、`27778`、`27779` 只允许 `PRIMARY_HOST/32`。
- SSH 隧道账户只允许从 Worker 到主库建立端口转发；不开放 Worker 的 7700、10010 到公网。
- 扩展机必须能访问 group2 对应 FTP；主库继续访问 group1 的 FTP。两台 FTP 服务端都不因本次扩展重建或改密。

扩展机安装 Docker、Compose v2、OpenSSH 客户端，并创建只用于隧道的系统账户。隧道公钥通过主库管理员受控登记，禁止使用 root 私钥、Shell 登录或开放式转发。

## 二、建立 PowerJob 隧道

扩展机保存隧道私钥到 `/var/lib/archive-tunnel/.ssh/primary_ed25519`，权限必须为 `600`。主库 `authorized_keys` 只允许将两个端口转发到回环地址：

```text
permitopen="127.0.0.1:7700",permitopen="127.0.0.1:10010",no-agent-forwarding,no-X11-forwarding,no-pty,no-user-rc ssh-ed25519 <扩展机隧道公钥>
```

扩展机创建 `/etc/systemd/system/archive-powerjob-tunnel.service`：

```ini
[Unit]
Description=Archive Delivery PowerJob tunnel for alibaba/jun group2
After=network-online.target
Wants=network-online.target

[Service]
User=archive-tunnel
ExecStart=/usr/bin/ssh -NT \
  -o BatchMode=yes -o ExitOnForwardFailure=yes \
  -o ServerAliveInterval=30 -o ServerAliveCountMax=3 \
  -o StrictHostKeyChecking=accept-new \
  -i /var/lib/archive-tunnel/.ssh/primary_ed25519 \
  -L 127.0.0.1:7700:127.0.0.1:7700 \
  -L 127.0.0.1:10010:127.0.0.1:10010 \
  archive-tunnel@8.222.228.188
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload
systemctl enable --now archive-powerjob-tunnel
sed -i '/[[:space:]]powerjob-server[[:space:]]*$/d' /etc/hosts
printf '127.0.0.1 powerjob-server\n' >> /etc/hosts
curl -fsSI --connect-timeout 3 --max-time 5 http://127.0.0.1:7700
nc -vz -w 3 127.0.0.1 10010
```

`7700` 只用于 PowerJob 发现，`10010` 用于心跳和调度协议，不能互换。隧道失败时 Worker 不得假装在线或继续创建任务。

## 三、同步代码和创建 Worker 服务

扩展机同步与主库相同的已测试代码；排除 Git、受控环境、构建产物和前端依赖：

```bash
export WORKER_HOST=<扩展交付机公网 IP>
export LOCAL_REPO=/path/to/archive-delivery-platform
rsync -az --exclude '.git/' --exclude 'deploy/.env' \
  --exclude '**/target/' --exclude 'web/node_modules/' --exclude 'web/dist/' \
  "$LOCAL_REPO/" "root@$WORKER_HOST:/opt/archive-delivery-platform/"
```

扩展机 `/opt/archive-delivery-platform/deploy/.env` 只填写主库连接和 Worker 非 Secret 参数。数据库密码从管理员受控渠道注入，不写进本文：

```dotenv
ARCHIVE_DB_URL=jdbc:postgresql://8.222.228.188:5432/archive_delivery
ARCHIVE_DB_USER=archive
ARCHIVE_DB_PASSWORD=<主库当前 archive 数据库密码>
ARCHIVE_WORKER_GROUP=group2
ARCHIVE_MAX_RUNNING_TASKS=3
ARCHIVE_WORKER_DB_POOL_SIZE=4
ARCHIVE_WORKER_JAVA_OPTIONS="-Xms128m -Xmx512m"
ARCHIVE_PREPARATION_DOWNLOAD_THREADS=16
ARCHIVE_TASK_LEASE_SECONDS=900
ARCHIVE_CLEANUP_WORK_AFTER_VERIFY=true
POWERJOB_ENABLED=true
POWERJOB_APP_NAME=archive-delivery
POWERJOB_SERVER_ADDRESS=powerjob-server:7700
POWERJOB_EXTERNAL_ADDRESS=<扩展交付机公网 IP>
```

不要配置 `ARCHIVE_UPGRADE_AUTHORIZED`，不要在扩展机启动 PostgreSQL、MySQL、PowerJob Server、archive-server 或 FTP。Worker 只通过 `deploy/docker-compose.worker.yml` 创建自己的服务：

```bash
cd /opt/archive-delivery-platform
chmod 600 deploy/.env
docker compose -f deploy/docker-compose.worker.yml --env-file deploy/.env config --quiet
docker compose -f deploy/docker-compose.worker.yml --env-file deploy/.env up -d --build
docker compose -f deploy/docker-compose.worker.yml --env-file deploy/.env ps
docker compose -f deploy/docker-compose.worker.yml --env-file deploy/.env logs --since 5m worker-4
```

验收三个服务都使用 `group2` 和相同总并发：

```bash
docker compose -f deploy/docker-compose.worker.yml --env-file deploy/.env exec worker-4 printenv ARCHIVE_WORKER_GROUP
docker compose -f deploy/docker-compose.worker.yml --env-file deploy/.env exec worker-5 printenv ARCHIVE_WORKER_GROUP
docker compose -f deploy/docker-compose.worker.yml --env-file deploy/.env exec worker-6 printenv ARCHIVE_WORKER_GROUP
```

三条输出都必须为 `group2`。同一扩展机的 Worker 共用本机 `archive_data`，不能挂载 jun-2 的目录；每个 Worker 的 PowerJob 外部端口分别为 `27777`、`27778`、`27779`。

## 四、主库登记实体和发布 Pipeline

先在主库确认当前实体、FTP 定位和无未完成交付，再执行受控 API/脚本。不能直接 SQL 写入实体，也不能用旧 `jun` 记录覆盖已有不同配置。

主库受控 `.env` 在保留现有实体原值的基础上追加第二个实体槽位：

```dotenv
PROD_ENTITY_COUNT=2
PROD_ENTITY_2_VENDOR_CODE=alibaba
PROD_ENTITY_2_ENTITY_CODE=jun
PROD_ENTITY_2_NAME="生产交付实体 jun / group2"
PROD_ENTITY_2_ACCOUNT=<group2 FTP 账户，原样复用>
PROD_ENTITY_2_HOST=<group2 FTP 主机，原样复用>
ENTITY_2_WORKER_GROUP=group2
PROD_FTP_ENTITY_2_PORT=21
PROD_FTP_ENTITY_2_ROOT_PATH=/
PROD_FTP_ENTITY_2_PASSWORD=<group2 FTP 密码，原样复用>
```

如果主库实际实体 1 已经是 `alibaba/jun`，不要重复创建同一业务键；只核对其 `workerGroup`。本次目标是让新业务键 `alibaba/jun` 固定到 `group2`，并将实体 endpoint 指向 group2 的独立 FTP。group1 的 FTP 账户、密码和 endpoint 保持原样；group2 的 FTP 账户和密码也保持其服务端现状，不运行密码生成脚本，不修改任何一台 FTP 的账户。

本次主库 Pipeline review 来源已经在 [`deploy/pipelines/archive-batch-jun.md`](../deploy/pipelines/archive-batch-jun.md) 增加：

```json
{
  "vendorCode": "alibaba",
  "entityCode": "jun",
  "workers": [{"workerGroup": "group2", "maxPackagesPerRound": 100}]
}
```

`archive-delivery` 的 Pipeline 不写死机器组；提交交付时使用 `targets:[{"vendorCode":"alibaba","entityCode":"jun","targetCount":...}]`，服务从主库实体记录选择 `group2`。不要为扩展机复制一份平行 `archive-delivery` DAG 或在 Worker 本地发布 PipelineVersion。

在主库项目目录执行：

```bash
cd /opt/archive-delivery-platform
python3 scripts/pipeline_registry.py validate
python3 scripts/pipeline_registry.py materialize archive-batch-jun
./scripts/configure-production-pipeline.sh
```

该命令发布新的不可变 `archive-batch` PipelineVersion，并通过受控配置登记实体。不要重发布历史交付单绑定的版本。 `archive-delivery` 只有在定义本身发生 review 变更时才创建新版本；本次扩展仅增加批次规划实体映射和主库实体，不复制 Worker 侧 Pipeline。

## 五、验收顺序

1. 主库核对 `archive-batch` 最新已发布版本包含 `alibaba/jun/group2/maxPackagesPerRound=100`，并核对实体 API 返回 `workerGroup=group2`。
2. 扩展机核对三个 Worker healthy、PowerJob 心跳和外部地址正常；主库 5432 只接受扩展机 `/32`。
3. 使用唯一、经过生产授权的批次提交，targets 指定 `vendorCode=alibaba`、`entityCode=jun`；不要复用历史批次，也不要为了验收清理 OSS/FTP。
4. 观察 `delivery_batch.worker_group=group2`、`delivery_task.worker_group=group2`，确认任务没有落到 group1 或 jun-2。
5. 真实核对 `SOURCE_GET`、`TARGET_GET`、`FTP_GET`、`SIZE_SHA`、`NO_PARTIAL`；group2 使用自己的 FTP 主机、账户、根目录和现有密码，扩展机本身不创建 FTP 文件服务。
6. 记录实际 batchDeliveryId、deliveryId、taskId、PipelineVersion、迁移版本、URI、size/SHA 和复核命令；容器 Up、Worker 心跳或 API ACCEPTED 不能替代真实交付验收。

## 故障和回退

- 隧道断开：先停扩展机 Worker，恢复隧道后再启动；不要开放 7700/10010 公网或改连本地 PowerJob。
- 实体配置冲突：停止脚本，核对主库现有业务键/FTP 定位；不得 SQL UPDATE 绕过注册校验。
- 任务落到错误组：停止新提交，保留租约和任务事实，检查不可变 PipelineVersion、实体 workerGroup 和 Worker 环境；不得直接改任务表。
- FTP 失败：先只读检查 group2 FTP 主机、账户、根目录和权限；禁止重建 FTP、改密或清理历史日期目录，也不要切换到 group1 的 FTP。
- 回退只切换 Worker 镜像/配置并保留 PostgreSQL、PipelineVersion、OSS/FTP 历史；不得 `down -v`、删卷或清空业务数据。

