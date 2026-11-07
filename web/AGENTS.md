# web 模块指南

## 模块职责

`web` 是 Vue 3 + TypeScript 交付管理台，用于按实体 targets 提交、查看 delivery 总览、最新任务、动态 Pipeline 阶段和 delivery_node/event 时间轴，并执行 retry/resume。浏览器只访问同源 `/api/v1`。

## 文件地图

| 文件 | 职责 |
|---|---|
| `src/views/DashboardView.vue` | deliveryId 筛选、实体目标、任务列表和操作 |
| `src/components/DeliveryTaskTimeline.vue` | 展示 nodes、过滤和跨日事件 |
| `src/api.ts` | deliveries/tasks 请求及 retry/resume |
| `src/types.ts` | DeliveryTarget、DeliveryTask、NodeItem、Timeline、SubmitTarget |
| `src/views/DashboardView.spec.ts` | 组件和交互测试 |
| `vite.config.ts` | 回环开发地址、API 代理和测试环境 |

## 开发约束

1. API 使用 deliveryId/taskId/processId，不保留旧命名。
2. 提交表单只维护 targets 的 targetCount/deliveryEntityId，不出现 groups/groupCode。
3. 进度只统计最新有效任务；EXPIRED 只在时间轴展示。跨日未全部成功时主单和实体目标展示 PARTIALLY_DELIVERED，不能提前显示 DELIVERED。
4. 阶段由后端 Pipeline 驱动，执行状态只做叠加展示。
5. 任务展示实体、targetSeq、dataId、artifactSeq、文件名、size/token/SHA、日期和 locator。
6. 时间轴读取 `timeline.nodes`，不把 delivery_node 与旧 process 表混淆。
7. retry 携带 expectedFence 和原因；EXPIRED 不提供恢复按钮。
8. 请求失败保留上次成功数据并显示错误；不展示 Secret 或完整 definition。
9. 新增组件和复杂计算写业务注释，避免 DashboardView 承担全部逻辑。

## 构建与测试

```bash
cd web
npm ci
npm run test
npm run build
```

组件测试覆盖实体目标、最新任务统计、EXPIRED、失败保留旧数据和 retry/resume 冲突。
