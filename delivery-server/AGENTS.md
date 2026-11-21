# delivery-server 模块指南

## 模块职责

`delivery-server` 提供 OSS 批次创建、批次交付 API、实体与集中 PipelineVersion 管理、看板和 Vue 静态资源托管。它依赖 delivery-core，不执行 repair、OSS/FTP 节点或直接推进任务。

## API 契约

```text
POST /api/v1/delivery-batches
GET  /api/v1/delivery-batches/{deliveryId}
GET  /api/v1/delivery-batches/{deliveryId}/packages
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

批次创建只含 ossUri、pipelineCode=archive-batch、deliveryDate、deliveryBatch。交付提交只含 ossUri、pipelineCode=archive-delivery、deliveryDate、deliveryBatch、targets[targetCount/vendorCode/entityCode]。

BATCH_CREATE 在同参数下幂等；BATCH_SUBMIT 每次创建独立提交并领取未消费批次包，同批次两次提交不是旧请求重放。暂停恢复使用显式 resume，不再通过重复提交继续。

## 开发约束

1. Controller 只做 HTTP、校验和响应；规划、外部调用和状态迁移在 core。
2. 看板同时暴露批次包总数、已交付包数和原始文件关系数；任务只统计最新有效任务。
3. 任务投影暴露 entityCode、sourceDataIds、artifacts[dataId/sourceArtifact] 和 newFileName，不再有 metadataIds。
4. Pipeline 已发布不可改；archive-batch 保存包大小与实体机器容量，archive-delivery 不重复保存这些配置。
5. 修改 API 同步前端、脚本、测试、设计和 AGENTS，不保留旧适配。

## 测试

```bash
./scripts/maven-test.sh -pl delivery-server -am test
cd web && npm ci && npm run test && npm run build
```

## 生产看板打包

API Dockerfile 将前端 dist 放入 src/main/resources/static 随服务 jar 发布；生产健康验收同时核对 /actuator/health、根路径 HTTP 200 和实际静态资源。JVM heap 通过 ARCHIVE_SERVER_JAVA_OPTIONS 配置，jun-2 默认 -Xms64m -Xmx256m。
