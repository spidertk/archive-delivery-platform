# web 模块指南

## 模块职责

`web` 是 Vue 3 + TypeScript 交付管理台，用于按 entityCode targets 提交、暂停/继续、查看 delivery 总览、最新任务、动态 Pipeline 阶段和 delivery_node/event 时间轴。浏览器只访问同源 `/api/v1`。

## 开发约束

1. 提交表单只维护 ossUri、pipelineCode、deliveryDate、deliveryBatch、cutoffAt 和 targets[targetCount/vendorCode/entityCode]，不出现来源/版本/实体 ID 或 idempotencyKey。
2. 相同批次原样提交按钮同时承担暂停后的继续；暂停调用 `/pause`，只在节点边界生效。
3. 任务展示 entityCode 及其 vendorCode，使用 sourceDataIds、artifacts 产物对、metadataIds 和 newFileName，不展示 targetSeq/artifactSeq/supersededByTaskId 等已删除字段。
4. 进度只统计最新有效任务；PAUSED、EXPIRED 和 PARTIALLY_DELIVERED 不能提前显示 DELIVERED。
5. 阶段由后端 Pipeline 驱动；retry 携带 expectedFence，EXPIRED 不提供恢复按钮。
6. 请求失败保留上次成功数据并显示错误；不展示 Secret 或完整 definition。
7. 新增组件和复杂计算写业务注释，避免 DashboardView 承担全部逻辑。

## 构建与测试

```bash
cd web
npm ci
npm run test
npm run build
```

组件测试覆盖 entityCode 目标、最新任务统计、暂停状态、失败保留旧数据和 retry/resume 冲突。
