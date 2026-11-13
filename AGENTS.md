# Archive Delivery Platform 维护基线

## 当前基线

Archive Delivery Platform 基于 PowerJob + 不可变 PipelineVersion。PostgreSQL 是业务事实源，PowerJob 只触发通用入口，Worker 按交付单绑定版本推进节点。

- 当前数据库只维护 `V24__golden_delivery_baseline.sql`：全新库创建完整模型，已有 V23 模型幂等跳过建表，不重复修改业务事实。不得恢复历史迁移或平行模型。
- 当前 DAG：`sourceDownload → mergePackages → repair → qualify → targetUpload → ftpDownloadVerify`。
- 核心血缘：`delivery_submit(delivery_id) → source_data(delivery_id) → delivery_mapping(task_id,data_id) → delivery_task(task_id) → delivery_node(task_id,process_id)`。
- `deploy/pipelines/*.md` 是 Pipeline 和黄金用例的唯一 review 来源。`pipeline.production.json`、`golden-case.json` 由注册脚本生成，不提交、不进入镜像。
- 本地基线为 `scripts/local-pipeline.sh`，不访问真实 OSS/FTP；生产基线为 `scripts/production-registry.sh <caseId|all>`，必须执行真实外部验证。
- 不维护旧 MVP、老 API、老表、老节点、兼容视图或过渡设计。

## 核心约束

1. 提交只包含 `ossUri + pipelineCode + deliveryDate + deliveryBatch + targets[vendorCode,entityCode,targetCount]`。cutoffAt 由 Pipeline 的 businessTimezone/deliveryCutoffTime 推导，不传数据库 ID、cutoffAt、idempotencyKey、Secret、节点列表或 Shell。
2. deliveryBatch 全局唯一，是幂等键和请求编号命名空间。相同规范请求返回原 deliveryId，不同请求 409；PAUSED 主单以完全相同参数续交，不重复建单。
3. `delivery_task.artifacts[]` 只含 dataId/sourceArtifact，sourceArtifact 必须等于 source_data.artifact；metadata_ids 为同顺序 data_id 数组。有效包名在整个 delivery_id 内连续且唯一。
4. targetCount 是合并任务槽位数。单包来源合计和最终产物均须在 minPackageBytes/maxPackageBytes 内，不切分原始文件；超限包下载前过滤。
5. merge 对每个源包只安全解包一次并输出请求目录；repair 在私有 staging 修复且只最终封包一次。生产 gzipCompressLevel 由不可变 Pipeline 固定，当前为 5。
6. repair 使用 entityCode 写 metadata.providerCode，保留合法 39 位 requestId；缺失或旧格式按 deliveryBatch 确定性补号并保留 originalRequestId。目录名=requestId，requestTime 为 UTC 纳秒格式。
7. 实体以 `(vendor_code,entity_code)` 联合唯一并绑定一个 worker_group。一个组只对应一台服务器的本地 `/data`，认领、retry/resume 和跨日不得跨组。
8. 同 URI+版本源包仅复用同机 source-cache，跨执行组没有缓存时回退真实 OSS。不得跨服务器共享工作目录。
9. Secret 只属于 Pipeline 节点，不进入实体、日志、事件、Manifest、看板、普通 API、证据或 CI。
10. 禁止清空 OSS/FTP 历史、同名不同 SHA 覆盖、SQL 直写 SUCCEEDED/DELIVERED、删除租约或伪造节点结果。

## 黄金验证与 CI

1. 每个 Markdown Pipeline 必须同时声明定义、targets、expected.taskCount/nodeTypes 和生产外部检查。缺少 NodeHandler 或黄金用例不得合入。
2. `GoldenPipelineDefinitionTest` 从 Spring 容器检查真实 NodeHandler 注册，再用无外部 IO 替身执行同一 PipelineEngine/DAG。它只证明代码逻辑，不是交付证据。
3. 默认本地验证：MD 注册、Python/Maven/前端测试、V24 全新建库及现有模型幂等升级、Shell/Compose 静态检查。不得下载真实业务文件或清理真实数据。
4. 生产每个 case 独立预检、提交、等待 DELIVERED，并核对 SOURCE_GET、TARGET_GET、FTP_GET、SIZE_SHA、NO_PARTIAL。
5. CI `.github/workflows/archive-delivery.yml` 分离本地门禁与生产 environment 审批，只同步已 review 的 Git 提交。证据必须包含实际 deliveryId/taskId、PipelineVersion、迁移版本、URI、size/SHA 和复核命令。
6. skipped、fake/Local Node、仅 Repository 写入、PowerJob 绿色实例、API ACCEPTED 和本地替身均不能作为生产交付通过依据。

## 模块

| 模块 | 当前职责 |
| --- | --- |
| `delivery-core` | V24、领域模型、PipelineEngine、NodeHandler、提交、映射、执行、暂停、重试和跨日 |
| `delivery-server` | Delivery API、实体/Pipeline 管理、看板、静态前端和健康检查 |
| `delivery-worker` | PowerJob 通用触发、有界执行、黄金定义和生产外部验证 |
| `web` | 提交/看板、暂停继续、任务时间轴、retry/resume |
| `nodes/python` | 安全解包、目录合并、repair、一次封包和 FTP 验证 |
| `deploy` | 生产 Compose、镜像、受控配置模板和 Markdown Pipeline 注册 |
| `scripts` | 本地门禁、生产注册、PowerJob 初始化、配置发布和证据采集 |
| `docs` | 当前黄金验证、生产运维和 AI 维护规范 |

## 常用命令

```bash
# 默认本地门禁，不访问真实 OSS/FTP
./scripts/local-pipeline.sh

# 单项测试
python3 -m unittest nodes/python/test_archive_repair.py scripts/test_pipeline_registry.py -v
./scripts/maven-test.sh test
cd web && npm ci && npm run test && npm run build

# 生产单 case / 全量黄金 case
./scripts/production-registry.sh archive-delivery-jun
./scripts/production-registry.sh all
```

生产真实写入必须显式授权并先通过预检。失败不得继续，未执行或 skipped 均记为未验证。日常运维见 `docs/production-operations.md`，AI/PR 规则见 `docs/pipeline-maintenance.md`。
