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

## 渠道监控

IngestMonitorPanel 是 /monitor 独立只读页面，也嵌入管理台标签；公共页面不渲染交付操作。按桶+一级目录展示当前存量、基线后累计进数、北京时间今日进数和真实目标桶去向，来源与交付两种量分别标明。桶及渠道预估月存储费来自后端官方新加坡标准 LRS 参考价，按有日期的参考汇率折算人民币，页面附官方单价与汇率来源，顶部突出“总存储一个月预估费用”及对应总容量；仅估算存储，不提供下载费用或手填价格。只调用监控/趋势/changes GET；目录区域仅展示实际进数目录，错误保留最近成功事实，移动端单列响应布局。

monitor.html/monitor-main.ts 是轻量独立构建入口，仅加载 Vue 和原生只读组件，不导入管理台或完整 Element Plus。HTML 自带加载/资源失败提示，不能首屏空白；monitor-entry.spec.ts 验证无全局组件库仍能启动。构建入口配置必须同时包含 index.html 与 monitor.html。

任务看板分别展示 canonical 批次包和按实体日序号生成的 FTP 文件名，编号事实来自服务端。
