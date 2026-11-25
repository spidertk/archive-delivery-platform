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
- Worker 线程中断和节点超时必须先终止并回收数据面子进程，避免旧 Python 与重试并写；repair 任务目录另持有同机排他锁保护 staging 和最终封包。正常 retry/resume 保留原来源与 fence，不删除租约。

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

生产预检按当前 Markdown 黄金用例的 vendorCode/entityCode 精确选择已启用实体，逐一确认节点凭据并实际连接 FTP；其他实体复用相同账户名不得扩大预检范围或导致目标计数误报。

独立 SOURCE_GET 完整流式读取对象并计算 size/SHA，FTP --verify-only 同样流式回验；降低验收落盘 IO，不降低真实字节校验。经授权的全量生产入口可在独立验收完成后回收白名单本地副本，节点本身不扩大清理范围，manifest 和最终交付文件始终保留。

生产入口的独立验收容器使用上限 2 GiB 的 `/tmp` tmpfs，容纳八路各不超过 100 MiB 的目标 OSS 临时副本，降低根盘 IO；TARGET_GET 仍真实完整下载、核对 size/SHA，再由 FTP 只读独立回验。Worker 节点工作目录和成品位置不变，性能提升以本次完整验收耗时实测为准。

当用户授权低磁盘优先保障当日交付时，生产入口显式启用 PROD_EVICT_VERIFIED_SOURCE_CACHE=true，可回收本次已验收、无已建未完成任务引用的源缓存，即使旧批次还有未消费规划引用；本批次未交付来源仍保护。Worker 缺缓存按原事实回退真实 OSS，size/SHA 约束不变，回收不修改 locator 或租约。
