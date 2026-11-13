# delivery-server 模块指南

## 模块职责

`delivery-server` 提供 Delivery API、实体与集中 PipelineVersion 管理、看板和 Vue 静态资源托管。它依赖 delivery-core，不执行 repair、OSS/FTP 节点或直接推进任务。入口为 `ArchiveServerApplication`，HTTP 8080，Actuator 只暴露 health。

## API 契约

```text
POST /api/v1/deliveries
GET  /api/v1/deliveries/{deliveryId}
POST /api/v1/deliveries/{deliveryId}/pause
POST /api/v1/deliveries/{deliveryId}/resume
POST /api/v1/deliveries/{deliveryId}/rollover
GET  /api/v1/deliveries/{deliveryId}/tasks
POST /api/v1/tasks/{taskId}/retry
GET  /api/v1/tasks/{taskId}/timeline
GET  /api/v1/dashboard/targets?deliveryId={deliveryId}
```

SubmitDeliveryRequest 只包含 ossUri、pipelineCode、deliveryDate、deliveryBatch、targets；targets 每项只有 targetCount/vendorCode/entityCode。不得恢复 sourceId、pipelineVersionId、deliveryEntityId、cutoffAt、idempotencyKey、Secret 或节点链入参。

deliveryBatch 全局幂等；同参数返回原单，不同参数 409。pause 只接收 reason，在节点边界暂停；PAUSED 主单通过相同提交继续。resume 使用 fromNode/taskIds/reason，retry 使用 expectedFence，rollover 使用 expectedDeliveryDate/deliveryDate/reason。

## 开发约束

1. Controller 只做 HTTP、校验和响应；规划、外部调用和状态迁移在 core。
2. target_strategy 只保存 vendorCode/entityCode/targetCount；看板通过复合业务键关联实体，任务聚合不含 targetSeq。
3. 任务投影暴露 entityCode、sourceDataIds、artifacts[dataId/sourceArtifact]、metadataIds 和 newFileName；metadataIds 是与 sourceDataIds 相同的正整数 data_id 数组，不保存 requestId 或 deliveryArtifact。JSONB 必须直接输出 JSON 数组/对象，禁止把 JDBC PGobject 的 type/value 包装泄漏到 API。SHA 从最新成功 repair 节点 output_locator 派生展示，不恢复数据库冗余列。
4. 看板只统计最新有效任务，PAUSED 和 PARTIALLY_DELIVERED 不得提前显示 DELIVERED。
5. Pipeline 已发布不可改；nodeDefinitions/DAG/assignment/Secret 集中在 definition，不再接受独立 ruleSets、selection 或 repair.ruleSet；三个 Python 节点的 scriptPath 只能是固定脚本目录内的规范化相对 .py 路径。
6. 修改 API 同步前端、脚本、测试、设计和 AGENTS，不保留旧适配。

## 测试

```bash
./scripts/maven-test.sh -pl delivery-server -am test
cd web && npm ci && npm run test && npm run build
```

重点覆盖 entityCode 提交、批次幂等、未知字段、实体校验、暂停再提交、retry fence、resume 前置事实、动态看板、Pipeline 不可变和 Secret 脱敏。
