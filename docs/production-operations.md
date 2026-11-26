# 生产日常运维与排查手册

## 授权后的全量批次续跑

同一批次的试跑独立外部核验通过后，可通过同一生产入口继续消费全部剩余包；真实预检仍必跑，已有提交先检查/核验，不重复 POST。`PROD_DELIVER_ALL=true` 启用此行为，`PROD_BATCH_WAVE_SIZE=100` 限制每轮最多 100 个包，最后的小尾批按实际剩余数提交。每轮内部保持实际 Worker 组的并发配置。

```bash
PROD_REUSE_SERVICES=true PROD_DELIVER_ALL=true PROD_BATCH_WAVE_SIZE=100 \
PROD_DELIVERY_BATCH=<原批次号> PROD_DELIVERY_DATE=<原业务日期> \
PROD_TARGET_COUNT=2 PROD_SUBMISSION_COUNT=1 PROD_OSS_URI='<原OSS URI>' \
./scripts/production-registry.sh archive-batch-jun
```

全量续跑在每个提交 DELIVERED 且 SOURCE_GET、TARGET_GET、FTP_GET、SIZE_SHA、NO_PARTIAL 独立验收通过后，仅在可用空间低于 75 GiB 时回收可重建副本：先记录文件白名单，再按实际 size/SHA 回收源缓存和该提交的本地 FTP 回验文件。默认保护所有未交付规划引用；取得用户低磁盘清理授权后，以 `PROD_EVICT_VERIFIED_SOURCE_CACHE=true` 显式允许忽略其他批次未消费的规划引用，保护本批次未交付包和所有已建未完成任务使用的来源。删除前复查引用，新增引用跳过并留证；缺缓存时按原 URI/版本/size/SHA 从 OSS 重新下载，不改事实定位。空间充足时保留缓存。只操作当前 Docker 数据卷内的精确文件，不清空目录，不删除 manifest、最终 OSS/FTP、数据库事实或租约；验收日志摘要、回收计划、实际删除与跳过项保留在 `<evidence>/full/`。BLOCKED、缺失收据、SHA 不符、回收后空间仍不足 30 GiB 或跨组包会停止新增提交。修复后从同入口恢复，禁止伪造收据或将未执行/跳过记作通过。2026-11-25 用户已明确授权本次 jun-2 批次 8 使用该回收方式，优先保障当天交付。

## 1. 组件和事实源

jun-2 的独立外部验收容器使用最大 2 GiB 的 `/tmp` tmpfs，目标 OSS 临时包留在内存，容器结束即释放。八路、每包不超过 100 MiB 的并行验收预留主机内存；不把 Worker 的 merge/repair 工作目录搬入内存，也不缩减真实 GET 与 SHA 验证。低于规定资源档的机器须先核对内存容量；吞吐变化以完整轮次和独立验收的实际耗时记录。

生产 Compose 文件为 `deploy/docker-compose.production.yml`。PostgreSQL 是业务事实源；PowerJob 只触发准备、节点推进和跨日扫描。交付是否完成必须看 `delivery_submit`、`delivery_task`、`delivery_node` 和外部 OSS/FTP 文件，不能只看容器或 PowerJob 状态。

`ftp-june` 仅在 `COMPOSE_PROFILES=embedded-ftp` 时运行；复用已有外部 FTP 时保持 `COMPOSE_PROFILES=`，不要部署重复 FTP。以下 FTP 日志命令只适用于内置模式。

```bash
cd /opt/archive-delivery-platform
export COMPOSE_FILE=deploy/docker-compose.production.yml

docker compose -f "$COMPOSE_FILE" --env-file deploy/.env ps
curl -fsS http://127.0.0.1:8080/actuator/health
curl -fsSI http://127.0.0.1:7700
docker compose -f "$COMPOSE_FILE" --env-file deploy/.env logs --since 1h archive-server worker-1
docker compose -f "$COMPOSE_FILE" --env-file deploy/.env logs --since 1h postgres mysql powerjob-server
# 仅 embedded-ftp 模式执行：
docker compose -f "$COMPOSE_FILE" --env-file deploy/.env logs --since 1h ftp-june
```

重建 archive-server 后，现有 monitor-web 可能仍使用旧容器 IP；重载 `docker compose -f "$COMPOSE_FILE" --env-file deploy/.env exec -T monitor-web nginx -s reload`，再确认 `/monitor` 和限定 GET 监控查询正常。只读页面返回 502 不能当作业务交付失败或空桶。

不要执行 `docker compose down -v`、`reset-test-data.sh`、`docker system prune -a` 或清空 OSS/FTP 目录。普通停止使用 `docker compose ... stop`，不删除卷。

## 2. 按交付单排查

先取得 deliveryId，再查询 API 和数据库：

```bash
export DELIVERY_ID=<实际deliveryId>
curl -fsS "http://127.0.0.1:8080/api/v1/deliveries/$DELIVERY_ID"
curl -fsS "http://127.0.0.1:8080/api/v1/deliveries/$DELIVERY_ID/tasks"
```

```sql
SET search_path TO archive_delivery;
SELECT delivery_id,delivery_batch,delivery_date,cutoff_at,status,pipeline_version_id,
       discovery_completed_at,completed_at
FROM delivery_submit WHERE delivery_id=<deliveryId>;

SELECT task_id,entity_code,worker_group,current_node_id,execution_status,
       new_file_name,size_bytes,fence,retry_count,last_error_code
FROM delivery_task WHERE delivery_id=<deliveryId> ORDER BY task_id;

SELECT process_id,task_id,node_id,attempt,status,worker_instance_id,
       error_code,error_detail,output_locator
FROM delivery_node WHERE delivery_id=<deliveryId> ORDER BY process_id;

SELECT event_type,task_id,process_id,reason_code,message,payload
FROM delivery_event WHERE delivery_id=<deliveryId> ORDER BY event_id;
```

按日志中的 `deliveryId/taskId/processId` 检索，不要只看 PowerJob instance：

```bash
docker compose -f deploy/docker-compose.production.yml --env-file deploy/.env logs --no-color archive-server worker-1 \
  | grep -E 'deliveryId=<deliveryId>|taskId=<taskId>|processId=<processId>'
```

## 3. 磁盘不足

repair 若在中断后重试时出现清理 `.archive_repair_*` 的 FileNotFoundError，先查旧 Python 是否仍在运行，不能通过忽略异常或修改业务状态掩盖竞争。ProcessSupport 必须在中断/超时时回收进程子树，repair 必须持任务目录 `.repair.lock` 后才清理残留与封包。确认测试、发布和旧进程退出后，只通过 retry/resume 恢复原任务，再执行完整外部验收；不得改名、补 ID、删除租约或放宽上限。

先确认是容量还是 inode，并区分数据库、Docker 镜像和业务文件：

```bash
df -h / /srv /opt
df -i / /srv /opt
docker system df
du -xh --max-depth=2 /srv/maas-sftp/maasData/archive/june 2>/dev/null | sort -h | tail -30
docker run --rm -v archive_delivery_production_archive_data:/data:ro alpine:3.22 \
  sh -c 'du -xh --max-depth=3 /data 2>/dev/null | sort -h | tail -40'
```

重点目录：

- `/data/work/source-cache`：同机源包缓存。
- `/data/work/delivery-<deliveryId>/task-<taskId>`：来源副本、merge 工作目录、repair 和 OSS 回验副本。
- `/data/ftp-verify`：Worker 的 FTP 下载回验文件。
- `/srv/maas-sftp/maasData/archive/june`：最终 FTP 交付文件。

处理顺序：

1. 暂停新提交；运行中任务先等节点结束，再在节点边界暂停。不要直接删除正在使用的目录。
2. 检查是否有异常 `.tmp`、`.partial` 和失败 staging。只有确认对应 task/process 已失败且没有 RUNNING，才清理该次明确路径的半成品。
3. 生产默认在 FTP size/SHA 复核成功后清理当前任务的大体积中间副本，保留 merge/repair manifest、source-cache、FTP 回验文件和最终 OSS/FTP。只有需要延长工作副本保留期时才调整 `ARCHIVE_CLEANUP_WORK_AFTER_VERIFY`；其他旧工作副本仍需按交付单审批清理。
4. Docker 镜像清理只针对确认不用的镜像；不得自动清理数据库、archive_data 或 FTP 卷。
5. PostgreSQL 磁盘写满时优先扩容。不要删除 WAL、数据库卷或手工编辑 Flyway 状态。

排查期间不要执行宽泛的 `rm -rf /data/*`、`rm -rf /srv/.../june/*`。任何清理都必须指定已核验的 deliveryId/taskId 路径。

## 4. 常见故障

### 源 OSS 403

- 检查源 AK/SK 是否有指定前缀 List/Get 权限，确认没有使用匿名 URL 代替 SDK 凭据。
- 检查 endpoint 为 `https://oss-ap-southeast-1-internal.aliyuncs.com`，bucket/prefix 来自提交时固化的 `ossUri`。
- 下载节点配置不得重新加入 bucket/prefix；403 时核对提交 URI 与源 AK/SK 的 List/Get 权限。
- 修改受控配置后重新发布 PipelineVersion 和预检，不直接改数据库 definition。

### 来源不足或全部超限

检查主单是否 BLOCKED、`SOURCE_FILTERED` 事件和准备日志。单包超过 100 MiB 会在下载前过滤；剩余来源无法组成全部 targetCount 时会阻断，不切分文件。合并不设置最小包大小，非空小尾包正常交付。补充来源或经审批发布新上限，不能 SQL 创建任务绕过。

### 目标 OSS 403 或 SHA 冲突

403 检查目标 AK 的 PUT/HEAD/GET 权限和 Bucket/endpoint。同名不同 SHA 是保护性阻断，禁止覆盖；核对是否复用了 deliveryBatch 或修改了源数据。新内容使用新批次，旧对象保留供审计。

### FTP 登录失败或连接拒绝

内置 FTP 只在 Compose 内网监听，宿主机没有公网 21 端口是预期安全边界。仅 `COMPOSE_PROFILES=embedded-ftp` 时检查：

```bash
docker compose -f deploy/docker-compose.production.yml --env-file deploy/.env ps ftp-june
docker compose -f deploy/docker-compose.production.yml --env-file deploy/.env logs --since 1h ftp-june
```

内置模式确认实体 host=`ftp-june`、account=`june`；复用老 FTP 时检查老 FTP 服务、网络和实际账户，不要启动 ftp-june 代替排查。两种模式都必须让 Pipeline ftpCredential 与实体账户对应。密码只在受控 `.env`/Pipeline 中维护，不复制 SSH root 密码。同名不同 SHA 时不得删除远端文件强行重传。

### 任务一直 READY

检查 worker-1 至 worker-8 是否在线、实体和任务是否都是 group1、组内 RUNNING 是否达到受控配置的 ARCHIVE_MAX_RUNNING_TASKS（jun-2 的 8 核 16G 档为 8）、任务是否 PAUSED/WAITING/RETRY_WAIT。每次广播每个 Worker 只推进一个节点，需要实例数与组内上限同时匹配。不要用 SQL 改 lease_owner 或 execution_status。当前资源档和发布边界见 [jun-2-capacity.md](jun-2-capacity.md)。

### BLOCKED 后重试

先修复真实外部原因，从任务查询取得实际 fence：

```bash
curl -fsS -H 'Content-Type: application/json' \
  -d '{"expectedFence":<实际fence>,"reason":"外部依赖已恢复，重试当前任务"}' \
  http://127.0.0.1:8080/api/v1/tasks/<taskId>/retry
```

fence 不匹配或状态不允许时接受 409，重新核对事实，不删除租约。

### 暂停和继续

```bash
curl -fsS -H 'Content-Type: application/json' \
  -d '{"reason":"节点边界运维暂停"}' \
  http://127.0.0.1:8080/api/v1/deliveries/<deliveryId>/pause
```

存在 RUNNING 时等待节点结束后重试。继续使用 `/api/v1/deliveries/<deliveryId>/resume` 并明确 fromNode、taskIds 和 reason；重复 POST 交付接口会创建新的 BATCH_SUBMIT 并消费后续批次包，不能用于暂停恢复。

## 5. 迁移、备份和升级

```bash
docker compose -f deploy/docker-compose.production.yml --env-file deploy/.env \
  exec -T postgres psql -U archive -d archive_delivery -Atc \
  "SELECT version,success,installed_on FROM archive_delivery.flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"
```

- 升级前备份 PostgreSQL 和 PowerJob MySQL，并记录当前镜像和 PipelineVersion。
- 批次模型为 V25 基线，最新 V29 增加来源监控事实。已有 V24 的任务来源关系迁入 delivery_batch，最终删除 delivery_mapping 和 metadata_ids；升级失败时停止交付并先排查，不清库。
- 升级先停止新提交并等待 RUNNING 节点结束，再构建启动；不得删除租约或修改历史任务 worker_group。
- 回退应用版本时保留数据库和外部文件。已发布 PipelineVersion 不可原地修改，新配置必须发布新版本。

## 6. 外部文件和证据复核

```bash
export FTP_DIR='/srv/maas-sftp/maasData/archive/june/<deliveryDate>'
find "$FTP_DIR" -maxdepth 1 -type f -name 'batch_*.tar.gz' -print0 | sort -z | xargs -0 -r sha256sum
find "$FTP_DIR" -maxdepth 1 -type f -name '*.partial' -print
```

将 SHA 与最新成功 repair、targetUpload、ftpDownloadVerify 节点 locator 和 `docs/evidence/<deliveryBatch>/external-verification.log` 对比。证据目录只保存脱敏请求、API、日志和文件事实，不得放入 `.env` 或完整 Pipeline definition。

## 7. 安全基线

- API 8080 和 PowerJob 7700 仅绑定回环；PG 5432 为跨机访问绑定 `0.0.0.0`；FTP 仅在 Compose 内网。
- 云安全组只按实际访问方公网 IP `/32` 放通 PG，不向 `0.0.0.0/0` 开放。`ARCHIVE_PG_BIND_ADDRESS=0.0.0.0`、`POWERJOB_BIND_ADDRESS=127.0.0.1`。同机 Worker 使用 `POWERJOB_SERVER_ADDRESS=powerjob-server:7700` 发现服务，PowerJob external address 配置为 `powerjob-server`、external port 配置为 `10010` 供后续心跳和调度；external port 不能配置为管理端口 7700。
- 日志不得包含 AK/SK、FTP/数据库密码、JWT 或私钥。发现泄露先轮换 Secret，再清理受控日志。
- 禁止数据库直写 SUCCEEDED/DELIVERED、伪造节点 locator 或用 skipped 测试代替生产证据。

## jun-2 授权日期保留

jun-2 的不可变 FTP 节点显式启用 `cleanupOtherDeliveryDates=true` 时，在每次上传下载前检查并删除直属根目录下其他合法日期目录；当前日期和非日期目录保留。启用要求专属 FTP 根、无符号链接、MLSD 及删除/改名权限，内置 FTP 设置 `FTP_ALLOW_DATE_CLEANUP=true`。同组 Worker 必须共享 `/data/ftp-verify/.retention`，在途传输持共享锁，日期切换取独占锁；日期一旦前进，旧日期重试拒绝清理，需按正常跨日流程处理。独立验收不清理目录。

2026-11-21 的清空 jun-2 FTP 和旧测试流水属于用户一次性授权；必须先确认无 RUNNING、备份业务流水并保存文件清单，不得推广为日常重置。6000 为 jun-2/group1 每轮分配容量，并发仍由机器资源和 Worker 配置限制。

## 来源渠道监控

渠道目录登记、微信群播报和趋势查询见 [ingest-channel-monitor.md](ingest-channel-monitor.md)。最新 V29 只新增观察事实，V25 的批次交付模型不变。监控故障不能作为空桶或交付停止的依据。


V29 公网看板通过独立 monitor-web 网关访问 `/monitor`，只代理静态资源与限定 GET 监控查询；管理 API 8080 仍绑定回环。生产明确设置 ARCHIVE_MONITOR_BIND_ADDRESS/PORT 和 ARCHIVE_INGEST_MONITOR_DASHBOARD_URL，并从外部验证页面、资源及拒绝管理/写请求。监控永久表最终仅 record/notification；旧 object/sample 不保留为表或视图。存储预估采用新加坡标准 LRS 官方价，详情与口径见监控文档。

## OSS 与 FTP 成品同名

新版 archive-delivery 通过 `useFtpFileName` 使用任务分配的同一外部文件名。OSS prefix 以日期、厂商和交付实体分区。跨日按新日期分配序号并重跑 OSS 上传，独立验收按实际对象 URI 和 FTP 路径校验名称与 size/SHA。历史不可变版本保留原路径算法。
