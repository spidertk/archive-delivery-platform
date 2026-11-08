# Archive Delivery 部署与升级手册

## 1. 本地验证拓扑

`deploy/docker-compose.yml` 启动 PostgreSQL 16、MySQL 8.0.36、PowerJob Server 5.1.7、archive-server、三个通用 Worker 和 ftp-entity-1/2/3。PG、PowerJob、API 只绑定 `127.0.0.1`；Worker 与 FTP 不向宿主机发布端口。工作文件与 FTP 根目录使用持久卷，禁止清空历史文件。

PowerJob 只有提交准备、任务推进和截止扫描三个通用触发器。PowerJob instance 成功只表示触发结束，交付完成以最终 delivery_node 和外部 OSS/FTP size/SHA 为准。

## 2. 当前模型执行边界

核心血缘为 `delivery_submit → source_data → delivery_mapping → delivery_task → delivery_node`。任务按未过期、未取消记录计算目标数量；跨日旧任务 EXPIRED 并创建指定下一交付日新 taskId，完整 mapping、artifacts、metadata_ids、entity/entity_code 和 worker_group 不变，新旧关系由事件 payload 保存。

提交只传 ossUri、pipelineCode、deliveryDate、deliveryBatch、cutoffAt 和 vendorCode+entityCode targets。同一 vendorCode 下多个 entityCode 按厂商整体分配，repair 接收 entityCode。deliveryBatch 全局唯一并作为请求编号命名空间；相同请求（含 cutoffAt）幂等返回，暂停主单以相同请求再提交继续。

PipelineVersion 集中保存 nodeDefinitions、ruleSets、DAG、selection、assignment、retry/concurrency/rollover 和节点 Secret。实体按 vendorCode+entityCode 联合唯一，保存协议、业务账户、workerGroup 及非 Secret 定位。source_data 保存 original_file_name、单原始 artifact、created_at/updated_at 和同机 cachePath；任务 sourceArtifact 必须与其完全一致，有效包名在同一 delivery_id 下全局唯一。

## 3. V14–V17 升级

V14 在显式授权环境中删除全部旧业务表并重建，不回填、不对账、不保留旧 ID。执行前停止 server/Worker 和外部调用，确认这是已明确放弃旧数据的本地环境，再运行：

全新库必须分两个阶段，等待 V13 成功后再执行第二阶段；已有 V13+ 数据库只执行符合当前授权边界的增量：

```bash
ARCHIVE_UPGRADE_AUTHORIZED=2026-11-06 ./scripts/mvp-up.sh
# 核对 flyway_schema_history 最新成功版本为 13
ARCHIVE_UPGRADE_AUTHORIZED=2026-11-07 ./scripts/mvp-up.sh
```

未授权连接会拒绝执行 V13/V14 对应的清库步骤。生产或未授权环境禁止通过设置变量绕过确认。V15 增加 PARTIALLY_DELIVERED；V16 创建 delivery_mapping 和任务产物对；V17 形成复合业务键提交、entityCode repair、source_data 单 artifact、original_file_name、来源时间戳、任务 entity_code 和 delivery 内有效包名唯一约束。

升级后检查：Flyway 到 V17；delivery_task_source 和旧配置表不存在；source_data 不含 original_request_id/new_request_id/artifacts；delivery_task 不含 data_id/target_seq/artifact_seq/original_file_name/sha256/superseded_by_task_id/invalidated_at/invalidation_reason/artifact；全表全列注释覆盖 100%。新增模型表必须默认同时包含 created_at/updated_at。

## 4. 本地运行事实重置

双确认入口只清理交付运行事实，保留 Flyway、Pipeline、来源、实体以及 OSS/FTP 外部历史：

```bash
ARCHIVE_ALLOW_TEST_DATA_RESET=true \
ARCHIVE_CONFIRM_LOCAL_TEST_RESET=archive_delivery \
./scripts/reset-test-data.sh
./scripts/mvp-up.sh
./scripts/bootstrap-powerjob.sh
```

禁止对 ECS/生产调用。禁止 `down -v`、清空 Bucket、清空 FTP 目录或同名不同 SHA 覆盖。

## 5. ECS 分组部署边界

主服务器运行 PostgreSQL、MySQL、PowerJob、archive-server 和 group1 Worker；其他任务服务器只运行 Worker，统一连接主 PostgreSQL 和 PowerJob。delivery_entity.worker_group 决定新任务执行组，delivery_task.worker_group 固化快照；认领、retry/resume 和跨日不得跨组。

一个执行组只对应一台服务器的本地 archive_data，同组 Worker 共享该卷，不同服务器不共享工作目录。主子链路使用公网 IP 时，安全组只允许对方公网 IP/32 访问 PG/PowerJob 和 Worker 回调端口；实际地址只写入未跟踪的本地配置。

上线顺序：数据库与 PowerJob → 主 server/Worker → 通用调度 → 发布 Pipeline 和实体 → 任务服务器纯 Worker → 真实预检 → 小批量分组验收 → 外部 OSS/FTP 与各组本地文件核验。滚动升级先停止新 claim 并等待节点安全结束，不删除租约或清理外部历史。

## 6. 生产 Pipeline 初始化与合并交付

1. 复制 `deploy/pipeline.production.example.json` 为未跟踪的 `deploy/pipeline.production.json`，填写源 OSS bucket/prefix、目标 OSS 和 endpoint。模板默认 selection.limit=10、maxPackageBytes=10485760；targetCount 指任务数，不是来源包数。
2. 在受控 `deploy/.env` 配置 SOURCE_OSS/TARGET_OSS AK/SK，以及 PROD_FTP_ENTITY_1..3_ACCOUNT/PASSWORD。实体 vendorCode 为 june、june-1、june-2，entityCode 为 PROD-JUNE、PROD-JUNE-1、PROD-JUNE-2；repair 接收 entityCode 并写入协议 metadata.providerCode。
3. 执行 `./scripts/configure-production-pipeline.sh`。脚本发布不可变版本，初始化来源和三个按 vendorCode+entityCode 联合唯一的实体，不打印 definition 或 Secret，不调用 ossutil/SSH。
4. 生产 OSS 相对目录仍按厂商 `{providerCode}/{deliveryDate}-{deliveryBatch}-{providerCode}`；FTP 相对目录为 `{deliveryDate}`。
5. 执行顺序为下载入选来源并保留 → merge 对各源只解包一次并输出请求目录 → repair 在 staging 副本修复并只最终封包一次 → qualify → OSS SDK PUT/HEAD/GET 回验 → FTP 上传下载核验。不得恢复 merge 中间压缩包。
6. 源包按 URI+版本缓存到 work/source-cache；跨交付、跨厂商分配命中后只做本地校验复制并记录 cacheHit=true，不重复 OSS GET。其他执行组没有该本机缓存时才回退 OSS。
7. 单包超过 maxPackageBytes 时下载前过滤并继续扫描；同 vendorCode 目标整体按来源顺序累计，当前包无法容纳下一完整源包时封包，最后目标满足后忽略后续来源，禁止切分原始文件。
8. 排查时按 deliveryId/taskId/processId 检索 Worker 日志。merge 日志含 workspace/requestCount，repair/OSS/FTP 日志含最终 size/SHA；不得记录 Secret。

生产提交示例：

```json
{
  "ossUri": "oss://source-bucket/archive/input/",
  "pipelineCode": "archive-delivery",
  "deliveryDate": "2026-11-09",
  "deliveryBatch": "BATCH-20261109-001",
  "cutoffAt": "2026-11-09T18:00:00+08:00",
  "targets": [{"targetCount": 1, "vendorCode": "june", "entityCode": "PROD-JUNE"}]
}
```

如果需要两个 PROD-JUNE 任务，则 targetCount=2；包名批次序号在整个 delivery_id 内连续。暂停调用 `/api/v1/deliveries/{deliveryId}/pause`，恢复时原样再次 POST 上述请求。
