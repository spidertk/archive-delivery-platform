# delivery-server 模块指南

## 模块职责

`delivery-server` 提供 Delivery API、交付实体与集中 PipelineVersion 管理、看板和 Vue 静态资源托管。它依赖 delivery-core，不执行 repair、OSS/FTP 节点或直接推进任务。

入口：`src/main/java/com/powerjob/archive/server/ArchiveServerApplication.java`。Spring Boot 2.7，HTTP 8080，Actuator 只暴露 health；本地只绑定回环地址。

## API 契约

```text
POST /api/v1/deliveries
GET  /api/v1/deliveries/{deliveryId}
POST /api/v1/deliveries/{deliveryId}/resume
POST /api/v1/deliveries/{deliveryId}/rollover
GET  /api/v1/deliveries/{deliveryId}/tasks
POST /api/v1/tasks/{taskId}/retry
GET  /api/v1/tasks/{taskId}/timeline
GET  /api/v1/dashboard/targets?deliveryId={deliveryId}
```

提交入参包含 sourceId、pipelineVersionId、deliveryDate、cutoffAt、deliveryBatch、idempotencyKey 和 targets。targets 每项只有 targetCount/deliveryEntityId；核心验证三个实体各 1。不接受 groups/groupCode、Secret、节点链或任意路径。时间轴返回 nodes/events，节点尝试使用 processId。

## 包职责

| 文件/包 | 职责 |
|---|---|
| `api/DeliveryController` | 提交、查询、resume 和 rollover |
| `api/DeliveryTaskController` | 任务详情、timeline 和 expectedFence retry |
| `api/DeliveryEntityController` | 实体、账户和非 Secret 定位管理 |
| `api/DashboardController` | 提交总览和实体目标派生聚合 |
| `api/PipelineController` | 版本、草稿、发布和 Secret 脱敏；无 binding API |
| `service/DashboardQueryService` | 从 submit/source/task/delivery_node/event 查询 |

## 开发约束

1. Controller 只做 HTTP、校验和响应；规划、外部调用和状态迁移在 core。
2. 提交事务只创建 delivery_submit 和 target_strategy，不创建交付组。
3. retry/resume/rollover 调用领域服务并写事件；EXPIRED 只能查看历史。rollover 请求必须包含 expectedDeliveryDate、deliveryDate、reason，响应返回实际新日期。
4. Pipeline 已发布不可改；nodeDefinitions/ruleSets/DAG/Secret 集中在 definition，无独立配置表或绑定接口。
5. 看板以最新有效任务为进度，targetCount 来自快照，assigned/completed/status 派生；跨日未完成显示 PARTIALLY_DELIVERED，全部成功才 DELIVERED。
6. 动态阶段来自 Pipeline；SQL 参数化；返回 URI 和 locator 必须脱敏并校验。
7. 修改 API 同步前端、脚本、测试和文档，不保留旧适配。

## 测试

```bash
./scripts/mvn.sh -pl delivery-server -am test
cd web && npm ci && npm run test && npm run build
```

重点覆盖 targets 提交、幂等、未知字段、实体校验、retry fence、resume 前置事实、动态看板、Pipeline 不可变和 Secret 脱敏。
