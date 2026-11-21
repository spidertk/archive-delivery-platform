# web 模块指南

## 模块职责

`web` 是 Vue 3 + TypeScript 交付管理台，用于创建 archive-batch 批次、按 entityCode targets 独立提交 archive-delivery、暂停/继续、查看批次统计、最新任务、动态 Pipeline 阶段和时间轴。

## 开发约束

1. 批次创建表单只维护 ossUri、pipelineCode=archive-batch、deliveryDate、deliveryBatch；交付表单只维护同名参数和 targets[targetCount/vendorCode/entityCode]。
2. 每次交付提交独立消费批次包；暂停后继续只调用显式 resume。
3. 任务展示 sourceDataIds、artifacts[dataId/sourceArtifact] 和 new_file_name，不展示 metadataIds 或旧映射字段。
4. 批次看板展示 batchPackageTotal、deliveredPackageCount、originalFileCount；只有全部规划包完成才显示 BATCH_CREATE DELIVERED。
5. 阶段由后端 Pipeline 驱动；retry 携带 expectedFence，EXPIRED 不提供恢复按钮。
6. 请求失败保留上次成功数据并显示错误；不展示 Secret 或完整 definition。
7. 批次创建使用独立 BatchCreateForm，避免 DashboardView 承担全部逻辑。

## 构建与测试

```bash
cd web && npm ci && npm run test && npm run build
```
