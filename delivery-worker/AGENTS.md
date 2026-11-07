# delivery-worker 模块指南

## 模块职责

`delivery-worker` 接收 PowerJob 通用触发，把提交准备、任务推进和截止扫描交给 delivery-core。Processor 只负责触发、有界槽位和摘要，不写死节点链；顺序完全由 delivery_submit 绑定的 PipelineVersion 决定。

入口：`src/main/java/com/powerjob/archive/worker/ArchiveWorkerApplication.java`。

| 通用调度 | 类型 | 业务作用 |
|---|---|---|
| 提交准备 | STANDALONE | 按 selection/targets 发现 source_data 并形成直属实体任务 |
| 任务推进 | BROADCAST 或有界池 | claim 最新任务，每次执行一个 currentNodeId |
| 截止扫描 | STANDALONE | EXPIRED 并生成下一日 task_id；主单保持 PARTIALLY_DELIVERED 至全部成功 |

正式运行启用 PowerJob，业务重试由 PG/RetryPolicy 管理，PowerJob 调度重试为 0。Processor 成功不代表任务 SUCCEEDED 或提交 DELIVERED。

## 执行边界

1. 有界 executor；防重、目标唯一约束、lease/fence 和状态提交由 core + PG 保证。
2. 每次 executeNext 只推进一个节点；WAITING 释放槽位，后续 Tick 继续。
3. claim 后创建 delivery_node，提交前校验 task_id + fence + lease_owner。
4. 跨日 RUNNING 通过 EXPIRED/fence 拒绝晚到提交，不强制 kill 或删除租约。
5. 镜像只含当前节点工具，不安装或挂载跳板机私钥。
6. Secret 只从当前 Pipeline 节点获取，不从全局业务环境变量回退。
7. 生产不能回退 Local Node/debug runner；缩容先停止新 claim。

## 核心验证职责

Worker 按 Pipeline 完成杭州源 OSS 直下载并保留源文件、本地 repair、三个实体各一任务、另一 OSS 上传、本地 FTP 下载验证。最终成功以外部 OSS/FTP size/SHA 和 delivery_node 事实为准。

```bash
./scripts/mvn.sh -pl delivery-worker -am test
./scripts/verify-delivery.sh <deliveryId>
```

真实验收必须证明三份 OSS/FTP 外部 size/SHA、文件保留、无 `.partial`、单任务 retry 和跨日新 taskId。`ARCHIVE_REAL_E2E=true` 只用于明确授权环境；skipped/fake/PowerJob 绿色实例不算通过。
