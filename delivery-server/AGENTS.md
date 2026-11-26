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

1. Controller 只做 HTTP、校验和响应；交付规划、节点外部调用和任务状态迁移在 core。独立来源观察及群播报集中在 service/ingestmonitor，不能推进任务状态。
2. 看板同时暴露批次包总数、已交付包数和原始文件关系数；任务只统计最新有效任务。
3. 任务投影暴露 entityCode、sourceDataIds、artifacts[dataId/sourceArtifact] 和 newFileName，不再有 metadataIds。
4. Pipeline 已发布不可改；archive-batch 保存包大小与实体机器容量，archive-delivery 不重复保存这些配置。
5. 修改 API 同步前端、脚本、测试、设计和 AGENTS，不保留旧适配。

## 渠道监控

service/ingestmonitor 集中负责分页扫描、差异、一级渠道聚合、30 分钟进数和小时汇总、原生卡片及只读看板投影。V29 仅 record/notification 两表；record 的 OBJECT 与 SAMPLE 行由形状约束和部分索引隔离。状态复用 ingest_source，所有趋势/游标查询显式限定 SAMPLE，快照更新显式限定 OBJECT。

渠道身份=桶+第一级目录，覆盖范围去重且不扩大扫描。监控 API 返回当前存量、基线后累计/北京时间今日进数、真实成功 oss.upload 的目标桶及累计/今日交付；相同目标 URI 的多次成功以首次成功去重归日，不把回验或重试重复计为交付。MonitorDashboardService/OssCostService 只读业务事实，不推进节点；存储费按新加坡标准 LRS 官方 US$0.017/计费GB/月对当前监控存量估算，以 2026-11-20 参考汇率 1 USD=6.6777 CNY 折算人民币，不计算下载费，不把未扫描目标量当存量。storageSummary 从原始监控容量计算整月人民币总额，不累加已舍入的渠道金额；pricing 同时公开美元原价、汇率、日期与来源。群卡即使只报活跃渠道，月费总额仍覆盖全部监控渠道。

WeCom 原生 template_card 链接受控 dashboard-url=/monitor；只展示精简渠道和最多三条实际变化目录。发送器检查跳转等于受控 URL，HTTP 200 且 errcode=0 才确认，历史消息不投递。通知及游标同事务，Secret 不进入消息、API、日志或证据。Worker 不运行监控。公网由独立只读网关限制到看板及限定 GET，完整管理 API 仍回环。

## 测试

```bash
./scripts/maven-test.sh -pl delivery-server -am test
cd web && npm ci && npm run test && npm run build
```

## 生产看板打包

API Dockerfile 将前端 dist 放入 src/main/resources/static 随服务 jar 发布；生产健康验收同时核对 /actuator/health、根路径 HTTP 200 和实际静态资源。JVM heap 通过 ARCHIVE_SERVER_JAVA_OPTIONS 配置，jun-2 默认 -Xms64m -Xmx256m。

任务看板同时提供批次包名、FTP 文件名和日序号。未领取整包的重分配、经 GET/SHA 复核的历史 FTP 别名及空闲实体地址修正由受控 API 执行。
