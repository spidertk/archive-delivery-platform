# delivery-core 模块指南

## 模块职责

`delivery-core` 是 server 与 Worker 共用的业务核心，负责实体目标升级版领域对象、PostgreSQL/Flyway、集中式 PipelineVersion、NodeHandler、提交、目标分配、请求编号、重试、跨日和 OSS/FTP 适配。

PipelineEngine 是通用节点执行器，节点顺序只来自当前 PipelineVersion。实现以两份 2026-11-06 文档的 2026-11-07 修订为唯一基线，不做老数据兼容。

## 包职责

| 包 | 职责 |
|---|---|
| `config` | 类型化 Worker、时钟、Jackson 和并发配置 |
| `domain` | DeliverySubmit、SourceData、DeliveryEntity、DeliveryTask、DeliveryNode、DeliveryEvent |
| `repository` | 新表 SQL、claim、lease/fence、目标唯一约束、任务接续和事件 |
| `pipeline` | 集中 nodeDefinitions/ruleSets、DAG、Secret 脱敏、NodeResult 和 PipelineEngine |
| `node` | OSS 直下载、本地 repair、qualify、OSS 上传、本地 FTP 下载验证 |
| `service` | 提交、准备、执行、投影、retry/resume、跨日和路径服务 |

核心表：`delivery_submit`、`source_data`、`delivery_entity`、`delivery_task`、`delivery_node`、`delivery_event`。不存在 DeliveryGroup、delivery.group、delivery_group、delivery_pipeline_submit、delivery_task_process、node_definition、rule_set、source_pipeline_binding。

## 稳定执行约束

1. PostgreSQL 是事实源；外部至少一次调用通过 fence/lease、唯一约束和 SHA 幂等防重。
2. TaskExecutionService 是唯一任务状态推进入口；NodeHandler 不直接 UPDATE delivery_task 或提交状态。
3. delivery_node 同时携带 delivery_id/task_id；process_id 是尝试主键，node_id 是 DAG 字符串 ID。
4. source_data.artifacts 每项只允许 artifactSeq、originalFileName、newFileName、sizeBytes、sha256、token、file_mapping。
5. 重复源或请求编号必须 FILTERED 并记录原因，不生成有效任务、不占目标数量。
6. targets 每项只有 deliveryEntityId/targetCount；任务直接写 delivery_entity_id/target_seq，目标状态由查询派生。
7. WAITING 不增加失败次数；权限、配置和 SHA 冲突 BLOCKED；临时网络/IO 有界退避。
8. 跨日同事务 EXPIRED 并生成指定下一交付日 task_id；delivery_id/data_id/entity/target_seq/artifact 不变。新任务占次日实体配额，主单 PARTIALLY_DELIVERED，全部成功才 DELIVERED。
9. 人工 rollover 必须传 expectedDeliveryDate、deliveryDate 和原因，新日期必须晚于原日期；不得直接 UPDATE 日期或状态。
10. Python 不直接写 PG；Shell 不拼 sh -c；路径、包名、URI 和 FTP 根目录必须校验。
11. 同 URI 同 SHA 幂等，不同 SHA 阻断；禁止清空 Bucket/FTP 或覆盖其他任务。

## 集中配置与密钥

- pipeline_version.definition 统一保存 nodeDefinitions、ruleSets、nodes、selection、assignment、rollover、retry、concurrency。
- 配置类信息能集中就集中，禁止为节点、规则、来源绑定恢复平行配置表。
- 源 OSS、交付 OSS、FTP 凭据属于对应节点；已发布版本不可改，换配置发布新版本。
- Secret 只写不读并脱敏，不进入 Domain 投影、locator、artifacts、节点、事件、日志或测试快照。
- Worker 只取得当前节点凭据；不恢复 SSH、CRR 或远端发布节点。

## 数据升级与测试

V14 是单向清空重建迁移，只在 `archive_delivery.upgrade_authorized=2026-11-07` 的授权连接执行；V15 增加 PARTIALLY_DELIVERED。所有目标表、列、枚举必须 COMMENT ON；模型修改同步所有层和文档。

```bash
./scripts/mvn.sh -pl delivery-core test
```

测试覆盖废弃表不存在、注释、目标约束、artifact 白名单、事件不可变、lease/fence、retry/resume、跨日和 Secret 扫描。状态案例使用小样本覆盖三实体各 2 个：6/6 正向 DELIVERED，5/6 跨日 PARTIALLY_DELIVERED，指定次日补齐 3 个后 DELIVERED，并验证次日配额。真实 OSS 写测试只有显式授权和预检通过才执行。
