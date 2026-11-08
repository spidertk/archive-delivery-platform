# 项目介绍

Archive Delivery Platform 是基于 PowerJob + Pipeline 的可定义归档数据交付平台。PostgreSQL 是业务事实源，PowerJob 只触发通用执行入口，Worker 按提交绑定的不可变 PipelineVersion 推进节点。

**两份 2026-11-06 设计的 2026-11-08 修订是当前唯一实施基线。** 不兼容老 API、老表或老数据；V14 在显式授权环境清空旧业务表并重建，V15/V16/V17 形成当前模型，不做双写、适配视图或平行实现。

## 核心决策

1. 核心血缘为 `delivery_submit(delivery_id) → source_data(delivery_id) → delivery_mapping(task_id,data_id) → delivery_task(task_id) → delivery_node(task_id,process_id)`。
2. Pipeline 是唯一业务流程定义。下载、目录合并、repair、质检、上传、FTP 下载验证和未来动作都由 DAG、nodeId、stageCode 和节点配置定义。
3. 提交只使用 `ossUri + pipelineCode + deliveryDate + deliveryBatch + cutoffAt + targets[vendorCode,entityCode,targetCount]`。服务端解析内部 source/version/entity ID；不得传数据库 ID、idempotencyKey、Secret、节点列表或任意 Shell。
4. `delivery_batch` 全局唯一，是提交幂等键和请求编号命名空间。相同批次相同规范请求（含 cutoffAt）返回原 deliveryId，不同请求 409；request_id_sequence/allocation 以外键依赖 delivery_batch。
5. 支持节点边界暂停：无 RUNNING 时任务和主单进入 PAUSED；对暂停主单以完全相同参数再次提交，从各任务 current_node_id 继续，不重复建单。
6. `source_data` 保存 original_file_name、单个原始 artifact 和 created_at/updated_at，不保存 original/new_request_id 或 artifacts 数组；新增模型表必须默认同时带创建时间和更新时间。同 URI+版本源包在同机 source-cache 复用，跨交付和其他厂商分配不重复 GET。
7. `delivery_task` 保存 entity_code 快照，删除 target_seq、artifact_seq、original_file_name、sha256、superseded_by_task_id、invalidated_at、invalidation_reason 和单数 artifact。
8. `delivery_task.artifacts[]` 每项严格保存 dataId/sourceArtifact/deliveryArtifact，sourceArtifact 必须等于 source_data.artifact；repair 前交付产物为 null，repair 后每个来源对保留同一合并包 size/token/SHA，file_mapping 只保存本来源 originalPath→newRequestId。metadata_ids 与映射 requestId 全集一致，任务 size/token 与合并 artifact 一致。
9. 有效任务的 new_file_name 使用整个 delivery_id 内连续序号并全局唯一；跨日复制完整 mapping、artifacts、metadata_ids、entity/entity_code/worker_group/new_file_name，新旧 taskId 由不可变事件关联。
10. 实体以 `(vendor_code,entity_code)` 联合唯一并绑定单服务器 worker_group。同 vendorCode 下多个 entityCode 目标整体分配；Worker 只认领同组非 PAUSED 任务，组内共享本机 /data，跨服务器不共享目录，每组独立计算最大 RUNNING 数。
11. Secret 只属于 Pipeline 对应节点，不进入实体、日志、事件、Manifest、看板或普通 API。

## 设计依据

1. `docs/superpowers/specs/2026-11-06-archive-delivery-platform-upgrade-design.md`：整体方案、API、Pipeline、暂停、合并优化、执行、安全、跨日和验收。
2. `docs/superpowers/specs/2026-11-06-archive-delivery-model-upgrade-design.md`：ER、字段、JSONB、约束和清空重建契约。
3. 各模块 AGENTS：模块内实施边界。
4. 2026-11-03 及更早文档只作历史参考，不得作为新开发依据。

## 服务模块

| 模块 | 职责 | 指南 |
|---|---|---|
| `delivery-core` | 领域模型、Flyway、PipelineEngine、NodeHandler、提交、映射、编号、执行、暂停、重试和跨日 | `delivery-core/AGENTS.md` |
| `delivery-server` | Delivery API、实体与 Pipeline 管理、看板、静态前端和健康检查 | `delivery-server/AGENTS.md` |
| `delivery-worker` | PowerJob 通用触发、有界执行和真实端到端测试 | `delivery-worker/AGENTS.md` |
| `web` | 业务编码提交/看板、暂停继续、任务时间轴、retry/resume | `web/AGENTS.md` |
| `nodes/python` | 本地安全解包、目录合并、repair、一次封包、token/SHA 和 FTP 验证 | `nodes/python/AGENTS.md` |
| `nodes/shell` | 仅必要的受控本地辅助脚本；禁止跳板机中继 | `nodes/shell/AGENTS.md` |
| `deploy` | 本地 OSS/FTP 验证拓扑、镜像和部署模板 | `deploy/AGENTS.md` |
| `scripts` | 构建、启动、预检、提交、续跑和真实验收 | `scripts/AGENTS.md` |
| `docs` | 当前设计、安装运维和历史资料 | `docs/AGENTS.md` |

## 当前流程与性能约束

当前 DAG：`sourceDownload → mergePackages → repair → qualify → targetUpload → ftpDownloadVerify`。

旧 merge 生成中间 tar.gz、repair 再解压的方式已废弃。package.merge 对每个源包只安全解包一次并输出 merge/requests；repair 在私有 staging 副本修复并只执行一次最终 tar/gzip 封存。保留必要的 staging 文件复制，但不得恢复重复压缩/解压。最终包仍受 `config.assignment.maxPackageBytes` 约束。

selection.limit 是扫描入选来源包数上限，targetCount 是合并后的任务数。单包超过上限时下载前过滤；同 vendorCode 目标整体按来源顺序累计，为后续目标保留来源，当前包无法容纳下一完整源包时封包，最后目标满足后忽略后续来源，禁止切分文件。repair 使用 entity_code 并写入协议 metadata.providerCode，保留合法 39 位非空 requestId，缺失或旧格式时按 deliveryBatch 号段确定性补号并保留 originalRequestId；目录名等于 requestId，requestTime 为交付日期 ISO 8601 UTC 纳秒格式。不同来源的同名目录以来源序号隔离成员键并保留 originalPath。

下载、合并、repair、qualify、OSS、FTP 日志必须带 deliveryId/taskId/processId 及必要的 dataId、来源数、请求数、路径和 size/SHA，不得记录 Secret。`.tmp`、异常 staging 和 `.partial` 必须清理，源包、merge 工作目录和有效产物保留。

## 技术栈与构建

- Java 17、Spring Boot 2.7.18、JdbcTemplate、Flyway、Jackson。
- PowerJob 5.1.7 + MySQL 8.0.36；PostgreSQL 16，schema `archive_delivery`。
- 阿里云 OSS SDK 3.17.4；Python 3.11 标准库为主；Commons Compress 1.26.1。
- Vue 3、TypeScript、Vite 6、Element Plus、Vitest、Node.js 24/npm。

新增依赖前检查 pom、package.json 和 lockfile。常用构建：

```bash
python3 -m unittest nodes/python/test_archive_repair.py -v
./scripts/mvp-test.sh
cd web && npm ci && npm run test && npm run build
./scripts/mvn.sh test
```

真实 OSS 写测试必须显式授权；预检失败不得继续。skipped、Local Node、fake 和 PowerJob 空调用成功不得写成真实交付通过。

## 开发约束（不要随意更改已有约束）

* 新增代码写有效业务注释，注释表达用途和必要约束，不要只复述接口或函数名称，说明输入输出事实及安全/状态边界。
* 新增复杂业务逻辑时应优先保证业务的可扩展性，拆分职责，控制函数复杂度，并对稳定流程和可变策略建立清晰边界，可根据实际场景适当使用策略，工厂、模版方法，责任链等设计模式，但不得为了套用设计模式引入没有必要的抽象层级。
* 每次变更同步相关模块 AGENTS.md,总结每个模块的功能和用途，和必要的一些约束。
* 未提交 git 前不做老字段、老 API、老节点或老数据兼容，不留平行实现。
* 修改模型必须同步迁移、Domain、Repository、服务、API、前端、脚本、测试和对应 AGENTS。
* 所有目标表、列、枚举必须有 COMMENT ON；JSONB 必须有 Schema、用途和白名单字段。
* 不处理无关既有失败或用户文件；测试临时产物必须清理，真实验收要求保留的产物除外。

## 核心真实验收

本地真实核心 case 必须同时证明：真实 Compose 和 V1–V17 迁移；从 `oss://sg-storage-20261013-6vmlk/internal/gpt-delta-seven-20261105-accepted-20261106-v1/products/` 真实 GET 三份样本且本地保留；提交显式 cutoffAt，同 vendorCode=alibaba 的 jun/jun2 各 targetCount=1，按 10 MiB 上限整体形成两个任务，repair 输入为 entityCode；三份来源各分配一次，sourceArtifact 完全等于 source_data.artifact，original_file_name/size/SHA 对齐，file_mapping 仅含本来源 originalPath，metadata_ids 与各 merge 请求数一致，包名在 delivery_id 下唯一，request_namespace 等于 deliveryBatch；两个产物均在另一 OSS Bucket GET 回验 size/SHA 一致；两个本地 FTP 账户分别上传并重新下载一件产物且 size/SHA 一致、无 .partial；同 case 实际覆盖暂停后原批次续交、单任务 retry、跨日新 taskId；再以 local-other/jun3 复用同来源形成新交付并证明 sourceDownload.cacheHit=true、无重复源 OSS GET；最终有效任务全部 SUCCEEDED、最终节点成功、主单 DELIVERED。

状态集成验收使用小样本，覆盖三个实体各 2 个：截止前全部成功为 DELIVERED；截止未完跨日后为 PARTIALLY_DELIVERED；指定次日继续并占次日配额，全部成功后 DELIVERED。生产真实验收必须走生产 targets、来源和交付目标，不能用本地结果冒充。

验收记录必须注明验证模式、实际 deliveryId/taskId、迁移版本、启动日志、API 结果、各节点事实和文件清单/SHA 自助复核命令。

## 端到端交付与验证模式强制规则

1. 数据库模型或框架流程有任何调整，必须在实际运行环境完成端到端验证后才能交付，覆盖真实迁移、应用启动、API、Worker/Pipeline 节点、数据库最终状态和外部结果。
2. 验证只分本地实现与生产实现两种模式。本地模式必须启动真实 Compose 服务并走完整业务流程；生产模式必须走真实生产服务、来源和交付目标。
3. 不得为绕过验证新增测试环境变量、跳过参数或条件分支。未执行、skipped、预检失败或链路中断均记为未验证。
4. 真实 OSS/FTP 写入须单独显式授权和预检；获授权 case 必须实际执行并核对 size/SHA、文件保留和最终状态。
5. 单元测试、Testcontainers、模拟库、fake/Local Node、仅 Repository 写入和 PowerJob 空调用只能作为开发辅助，不能作为交付依据。
