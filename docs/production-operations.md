# 生产日常运维与排查手册

## 1. 组件和事实源

生产 Compose 文件为 `deploy/docker-compose.production.yml`。PostgreSQL 是业务事实源；PowerJob 只触发准备、节点推进和跨日扫描。交付是否完成必须看 `delivery_submit`、`delivery_task`、`delivery_node` 和外部 OSS/FTP 文件，不能只看容器或 PowerJob 状态。

```bash
cd /opt/archive-delivery-platform
export COMPOSE_FILE=deploy/docker-compose.production.yml

docker compose -f "$COMPOSE_FILE" --env-file deploy/.env ps
curl -fsS http://127.0.0.1:8080/actuator/health
curl -fsSI http://127.0.0.1:7700
docker compose -f "$COMPOSE_FILE" --env-file deploy/.env logs --since 1h archive-server worker-1
docker compose -f "$COMPOSE_FILE" --env-file deploy/.env logs --since 1h postgres mysql powerjob-server ftp-june
```

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
3. 已交付批次只有在 OSS/FTP size/SHA 证据完整并超过保留期后，才可按交付单审批清理旧工作副本。最终 FTP 文件默认保留。
4. Docker 镜像清理只针对确认不用的镜像；不得自动清理数据库、archive_data 或 FTP 卷。
5. PostgreSQL 磁盘写满时优先扩容。不要删除 WAL、数据库卷或手工编辑 Flyway 状态。

排查期间不要执行宽泛的 `rm -rf /data/*`、`rm -rf /srv/.../june/*`。任何清理都必须指定已核验的 deliveryId/taskId 路径。

## 4. 常见故障

### 源 OSS 403

- 检查源 AK/SK 是否有指定前缀 List/Get 权限，确认没有使用匿名 URL 代替 SDK 凭据。
- 检查 endpoint 为新加坡区域，bucket 为 `sg-oss-external-channel-source`。
- 检查 prefix 中 `YY：` 是全角冒号；URL 的 `%EF%BC%9A` 必须解码后写入 Pipeline。
- 修改受控配置后重新发布 PipelineVersion 和预检，不直接改数据库 definition。

### 来源不足、全部超限或包低于 8 MiB

检查主单是否 BLOCKED、`SOURCE_FILTERED` 事件和准备日志。单包超过 10 MiB 会在下载前过滤；剩余来源无法组成全部 targetCount 时会阻断，不切分文件。补充来源或经审批发布新的大小边界，不能 SQL 创建任务绕过。

### 目标 OSS 403 或 SHA 冲突

403 检查目标 AK 的 PUT/HEAD/GET 权限和 Bucket/endpoint。同名不同 SHA 是保护性阻断，禁止覆盖；核对是否复用了 deliveryBatch 或修改了源数据。新内容使用新批次，旧对象保留供审计。

### FTP 登录失败或连接拒绝

生产 FTP 只在 Compose 内网监听，宿主机没有公网 21 端口是预期安全边界。检查：

```bash
docker compose -f deploy/docker-compose.production.yml --env-file deploy/.env ps ftp-june
docker compose -f deploy/docker-compose.production.yml --env-file deploy/.env logs --since 1h ftp-june
```

确认实体 host=`ftp-june`、account=`june`，Pipeline ftpCredential 有对应账户。密码只在受控 `.env`/Pipeline 中维护，不复制 SSH root 密码。同名不同 SHA 时不得删除远端文件强行重传。

### 任务一直 READY

检查 worker-1 是否在线、实体和任务是否都是 group1、组内 RUNNING 是否达到 3、任务是否 PAUSED/WAITING/RETRY_WAIT。不要用 SQL 改 lease_owner 或 execution_status。

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

存在 RUNNING 时等待节点结束后重试。继续必须使用完全相同的提交 JSON 再 POST `/api/v1/deliveries`，确认返回原 deliveryId；不得新建批次。

## 5. 迁移、备份和升级

```bash
docker compose -f deploy/docker-compose.production.yml --env-file deploy/.env \
  exec -T postgres psql -U archive -d archive_delivery -Atc \
  "SELECT version,success,installed_on FROM archive_delivery.flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"
```

- 升级前备份 PostgreSQL 和 PowerJob MySQL，并记录当前镜像和 PipelineVersion。
- 当前只维护 V24 幂等基线。全新库创建完整模型，已有 V23 模型不重复建表；pre-V14 旧生产库不会被 `production-pipeline.sh` 自动升级。
- 升级先停止新提交并等待 RUNNING 节点结束，再构建启动；不得删除租约或修改历史任务 worker_group。
- 回退应用版本时保留数据库和外部文件。已发布 PipelineVersion 不可原地修改，新配置必须发布新版本。

## 6. 外部文件和证据复核

```bash
export FTP_DIR='/srv/maas-sftp/maasData/archive/june/<deliveryDate>/<deliveryBatch>'
find "$FTP_DIR" -maxdepth 1 -type f -name 'batch_*.tar.gz' -print0 | sort -z | xargs -0 -r sha256sum
find "$FTP_DIR" -maxdepth 1 -type f -name '*.partial' -print
```

将 SHA 与最新成功 repair、targetUpload、ftpDownloadVerify 节点 locator 和 `docs/evidence/<deliveryBatch>/external-verification.log` 对比。证据目录只保存脱敏请求、API、日志和文件事实，不得放入 `.env` 或完整 Pipeline definition。

## 7. 安全基线

- API 8080、PG 5432、PowerJob 7700 默认只绑定回环；FTP 仅在 Compose 内网。
- 远程 Worker 接入时再按对方公网 IP `/32` 放通 PG/PowerJob/Worker 回调，不向 `0.0.0.0/0` 开放。需要公网互联时，将 `ARCHIVE_PG_BIND_ADDRESS=0.0.0.0`、`POWERJOB_BIND_ADDRESS=0.0.0.0`，效果分别等同于 Compose 的 `"5432:5432"`、`"7700:7700"`；默认 `"127.0.0.1:5432:5432"` 仅允许本机访问。同步为 PowerJob 配置公网 external address，并在对端放通 Worker 27777–27779 回调端口。
- 日志不得包含 AK/SK、FTP/数据库密码、JWT 或私钥。发现泄露先轮换 Secret，再清理受控日志。
- 禁止数据库直写 SUCCEEDED/DELIVERED、伪造节点 locator 或用 skipped 测试代替生产证据。
