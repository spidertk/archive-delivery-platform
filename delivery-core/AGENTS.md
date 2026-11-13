# delivery-core 模块指南

## 职责

领域模型、V24 基线、PipelineEngine、NodeHandler、提交、映射、编号、执行、暂停、重试、跨日及 OSS/FTP 适配。PostgreSQL 是唯一业务事实源。

## 当前模型

- 血缘为 delivery_submit → source_data → delivery_mapping → delivery_task → delivery_node。
- 提交只接收 ossUri/pipelineCode/deliveryDate/deliveryBatch/targets；cutoffAt 由不可变 Pipeline 推导。
- delivery_task.artifacts 只含 dataId/sourceArtifact；metadata_ids 为同顺序 data_id。包名在 delivery_id 内连续唯一。
- TaskExecutionService 是唯一任务状态推进入口，NodeHandler 不直接修改主单或任务状态。
- 同 vendorCode 目标整体分配，targetCount 展开槽位，不切分来源；merge 只解包一次，repair 只最终封包一次。
- repair 使用 entityCode 和 Pipeline 固定的 gzipCompressLevel（默认 9，生产 5），request_namespace=deliveryBatch。
- worker_group 是单服务器执行域，任务创建时固化，retry/resume/rollover 不跨组。

## V24 基线

`V24__golden_delivery_baseline.sql` 包含当前表、列、约束、注释、JSONB 白名单和种子 Pipeline。全新库只执行 V24；已有 V23 模型跳过建表，不重复修改业务事实。server/worker 设置 `spring.flyway.ignore-missing-migrations=true` 以接受旧历史，不恢复旧迁移。

## 验证

```bash
./scripts/maven-test.sh -pl delivery-core test
```

`ArchiveModelIntegrationTest` 验证全新 V24 和现有模型幂等升级、注释、血缘、提交、暂停、重试和跨日。模型变化必须同步迁移、Domain、Repository、服务、API、前端、脚本、测试和模块文档；不得恢复旧表或平行实现。
