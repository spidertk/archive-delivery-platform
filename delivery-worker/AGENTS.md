# delivery-worker 模块指南

## 模块职责

`delivery-worker` 接收 PowerJob 通用触发，把提交准备、任务推进和截止扫描交给 delivery-core。Processor 只负责触发、有界槽位和摘要，不写死节点链；顺序完全由 delivery_submit 绑定的 PipelineVersion 决定。

| 通用调度 | 类型 | 业务作用 |
|---|---|---|
| 提交准备 | STANDALONE | 按 selection/entityCode targets 发现 source_data，依 vendorCode 整体分配并形成 delivery_mapping |
| 任务推进 | BROADCAST 或有界池 | claim 最新非 PAUSED 任务，每次执行一个 currentNodeId |
| 截止扫描 | STANDALONE | EXPIRED 并生成下一日 taskId，事件保存新旧血缘 |

## 执行边界

1. 有界 executor；防重、lease/fence 和状态提交由 core + PG 保证。
2. 每次 executeNext 只推进一个节点；PAUSED 不认领，WAITING 释放槽位。
3. 跨日 RUNNING 通过 EXPIRED/fence 拒绝晚到提交，不强杀外部 IO。
4. Secret 只从当前 Pipeline 节点获取；生产不能回退 Local Node/debug runner。
5. `ARCHIVE_WORKER_GROUP` 是单服务器执行域，同机 Worker 共享 /data，跨机不共享。

## 当前数据面

Worker 按 `oss.download → package.merge → python.repair → manifest.qualify → oss.upload → ftp.download-verify` 推进。超限源包下载前过滤，同 vendorCode 的 entityCode 目标整体顺序累计到 10 MiB 内且不切分。URI+版本源包在同机 source-cache 复用，sourceDownload 命中后本地校验复制并记录 cacheHit=true，其他执行组缓存缺失时回退 OSS。merge 输出请求目录；repair 接收 entityCode 并只最终封包一次。任务来源只读 delivery_mapping，artifacts 只含 dataId/sourceArtifact，metadataIds 为同顺序 data_id；最终 size/SHA 从 repair/OSS 成功 locator 读取。

## 核心验证

```bash
./scripts/mvn.sh -pl delivery-worker -am test
./scripts/verify-delivery.sh <deliveryId>
```

本地真实核心 case 从指定 sg-storage products 下载足够来源，按每包 8–10 MiB 形成两个实体任务、每组上传一件，完成 repair/qualify、两件 OSS GET 回验和两个 FTP 账户各一件产物上传下载核验；同时实际覆盖暂停后原批次续交、单任务 retry 和跨日新 taskId。最终核对入选来源各分配一次、artifacts 仅含 dataId/sourceArtifact、sourceArtifact=source_data.artifact、metadataIds 与 sourceDataIds 完全相等、repair manifest 保留 originalPath/newRequestId、最终 size/SHA 来自成功节点 locator、包名在 delivery 内唯一、deliveryBatch 编号命名空间、外部文件 size/SHA、文件保留和无 .partial。

`LocalMergedCaseVerificationTest` 仅用于显式本机 Compose V20 迁移/三来源映射辅助核验，不能替代真实节点验收。`ARCHIVE_REAL_E2E=true` 只用于明确授权环境；skipped、fake、PowerJob 绿色实例和数据库直写均不算端到端通过。模型或流程调整后必须实际跑通本地或生产完整链路。
