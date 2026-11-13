# delivery-worker 模块指南

## 职责

接收 PowerJob 通用准备、推进和跨日触发，由 delivery-core 按不可变 PipelineVersion 有界执行。Processor 不写死节点链，每次 executeNext 只推进一个节点。

## 执行边界

- 有界 executor；claim、lease/fence 和状态提交由 core + PG 保证。
- PAUSED 不认领，WAITING 释放槽位；RUNNING 过期通过 fence 拒绝晚到提交。
- `ARCHIVE_WORKER_GROUP` 对应一台服务器的本地 `/data`，同机 Worker 共享，跨机不共享。
- Secret 只从当前节点配置获取；生产禁止 Local Node/debug runner 和任意 Shell。
- merge/repair/FTP 的 scriptPath 只保存固定挂载目录内相对 `.py` 路径。

## 黄金验证

`GoldenPipelineDefinitionTest` 逐个读取 `deploy/pipelines/*.md`，从 Spring 容器检查真实 NodeHandler 注册，再以无外部 IO 替身执行同一 PipelineEngine/DAG。它只证明逻辑，不是生产证据。

`RealExternalVerificationTest` 在生产 profile 下按 targetCount/entityCount 执行真实预检和交付复核；本地默认历史专项配置不得替代生产 case。每个生产 case 必须实际覆盖 SOURCE_GET、TARGET_GET、FTP_GET、SIZE_SHA、NO_PARTIAL。

```bash
./scripts/maven-test.sh -pl delivery-worker -am test
./scripts/production-registry.sh <caseId|all>
```

skipped、fake、PowerJob 绿色实例和数据库直写均不算交付通过。
