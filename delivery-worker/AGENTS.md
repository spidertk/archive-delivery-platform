# delivery-worker 模块指南

## 职责

接收 PowerJob 通用准备、推进和跨日触发。准备入口按 submission_type 调用 archive-batch 的 OSS 归档规划或 archive-delivery 的批次包消费；executeNext 每次只推进一个节点。

## 执行边界

- 有界 executor；claim、lease/fence 和状态提交由 core + PG 保证。
- PAUSED 不认领，WAITING 释放槽位；大文件节点默认租约 900 秒，可由 `ARCHIVE_TASK_LEASE_SECONDS` 调整且最低 120 秒。
- `ARCHIVE_WORKER_GROUP` 对应一台服务器的本地 `/data`；delivery_batch 规划决定任务实际执行组。
- Secret 只从当前节点配置获取；生产禁止 Local Node/debug runner 和任意 Shell。
- merge/repair/FTP 的 scriptPath 只保存固定挂载目录内相对 `.py` 路径。
- 生产 FTP 复核成功后按配置清理任务工作副本，保留 manifest、source-cache 与 FTP 回验文件。

## 黄金验证

`GoldenPipelineDefinitionTest` 读取全部 Markdown Pipeline，检查真实 NodeHandler 注册和 DAG。`RealExternalVerificationTest` 分别读取 archive-batch 与 archive-delivery，执行真实 SOURCE_GET、TARGET_GET、FTP_GET、SIZE_SHA、NO_PARTIAL 验证；并行复核使用最多 4 连接的有界池，避免逐查询建连。

```bash
./scripts/maven-test.sh -pl delivery-worker -am test
./scripts/production-registry.sh archive-batch-jun
```

skipped、fake、PowerJob 绿色实例和数据库直写均不算交付通过。

## jun-2 稳定性边界

同组 claim 的容量检查和认领通过 PG 事务 advisory lock 串行化。FTP 节点同机共享 retentionRoot，跨日期清理等待在途传输；独立 RealExternalVerificationTest 固定关闭清理策略，只复核现存文件。小内存机器对 JVM 设置可配置 heap 上限，并持续采集节点耗时、swap、RSS、磁盘及失败重试。

独立外部验收必须调用 FTP 脚本 `--verify-only`；目标缺失或 SHA 不符立即失败，不允许补传文件后宣告通过。
