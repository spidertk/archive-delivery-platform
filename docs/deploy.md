# Archive Delivery 部署与升级手册

## 1. 本地验证拓扑

`deploy/docker-compose.yml` 启动 PostgreSQL 16、MySQL 8.0.36、PowerJob Server 5.1.7、archive-server、三个通用 Worker 和 ftp-entity-1/2/3。PG、PowerJob、API 只绑定 `127.0.0.1`；Worker 与 FTP 不向宿主机发布端口。工作文件与三个 FTP 根目录分别使用持久卷，禁止清空历史文件。

PowerJob 只有三个通用触发器：

- `archive-delivery-preparation`：STANDALONE，15 秒，按 selection/targets 发现并规划。
- `archive-task-tick`：BROADCAST，5 秒，每个 Worker 每次推进一个节点。
- `archive-delivery-rollover`：STANDALONE，30 秒，处理截止未完成任务。

PowerJob instance 成功只表示触发结束，交付完成以最终 delivery_node 和外部 OSS/FTP size/SHA 为准。

## 2. 新模型执行边界

核心血缘为 `delivery_submit → source_data → delivery_task → delivery_node`。任务直接引用 delivery_entity_id/target_seq，主单目标数量按未过期、未取消任务计算；跨日旧任务 EXPIRED 并创建指定下一交付日新 task_id，新任务占次日实体配额，deliveryId/dataId/entity/targetSeq 不变。主单未全部成功前为 PARTIALLY_DELIVERED，全部成功后 DELIVERED。

PipelineVersion 集中保存 nodeDefinitions、ruleSets、DAG、selection、assignment、retry/concurrency/rollover 和节点 Secret。没有独立节点、规则或来源绑定配置表。交付实体只保存厂商、协议、业务账户及非 Secret 定位。

## 3. V14 单向升级与 V15 状态扩展

V14 在显式授权环境中删除全部旧业务表并重建，不回填、不对账、不保留旧 ID。执行前停止 server/Worker 和外部调用，确认这是已明确放弃旧数据的本地环境，再运行：

```bash
ARCHIVE_UPGRADE_AUTHORIZED=2026-11-07 ./scripts/mvp-up.sh
```

未授权连接会拒绝执行 V14。生产或未授权环境禁止通过设置该变量绕过确认；数据库备份与恢复属于独立运维措施，不是应用兼容层。V15 在 V14 模型上增加 PARTIALLY_DELIVERED，不清理交付事实。

升级后检查：Flyway 到 V15；delivery_group、delivery_pipeline_submit、delivery_task_process、node_definition、rule_set、source_pipeline_binding 不存在；全表全列注释覆盖 100%；健康检查和 Worker 注册正常。

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

## 5. ECS 部署边界（尚未部署）

ECS 复用 server/Worker 镜像和不可变 PipelineVersion，但需重新设计内网、持久存储、Secret、副本、端口和健康检查。业务 PG 与 PowerJob MySQL 独立持久化，API/PowerJob 仅开放受控内网入口，Worker 使用有界执行槽和各自持久工作目录。

上线顺序：数据库与 PowerJob → server/Worker → 通用调度 → 发布 Pipeline → 真实预检 → 小批量三实体验收 → 外部 OSS/FTP 核验。不得照搬 loopback、宿主机路径、本地 FTP 密码或 reset-test-data.sh。滚动升级先停止新 claim 并等待节点安全结束，不删除租约或清理外部历史。
