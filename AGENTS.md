# 项目介绍

Archive Delivery Platform 是基于 PowerJob + Pipeline 的可定义归档数据交付平台。PostgreSQL 是业务事实源，PowerJob 只触发通用执行入口，Worker 按提交绑定的不可变 PipelineVersion 推进节点。

**2026-11-07 实体目标升级版是当前唯一实施基线。** 不兼容老 API、老表或老数据；V14 在显式授权环境清空旧业务表并重建，不做双写、适配视图、回填或迁移对账。

## 核心决策

1. 核心血缘为 `delivery_submit(delivery_id) → source_data(delivery_id) → delivery_task(task_id) → delivery_node(task_id,process_id)`。
2. Pipeline 是唯一业务流程定义。下载、repair、清洗、校验、上传、FTP 下载验证和未来动作都由 DAG、nodeId、stageCode 和节点配置定义。
3. 提交直接表达交付实体目标，不再有交付组领域、delivery_group、groupCode、组状态或组节点。`targets` 每项为 `deliveryEntityId + targetCount`，任务直接保存 `delivery_entity_id + target_seq`。
4. 实体通过 `worker_group` 绑定单服务器执行组，任务创建和跨日接续固化该组快照。Worker 只认领 `ARCHIVE_WORKER_GROUP` 相同的任务，组内共享本机 `/data`，跨服务器不共享目录；每组独立计算 `ARCHIVE_MAX_RUNNING_TASKS`。
5. 表为 `delivery_submit`、`source_data`、`delivery_entity`、`delivery_task`、`delivery_node`、`delivery_event`、`ingest_source`、`pipeline/pipeline_version` 及请求编号表。
6. **配置集中维护原则：配置类信息能汇集就汇集，便于查找和统一管理。** `pipeline_version.definition` 统一保存 nodeDefinitions、ruleSets、DAG、selection、assignment、retry、concurrency、rollover 和节点 Secret；禁止恢复 node_definition、rule_set、source_pipeline_binding 等平行配置表。
7. `delivery_node.process_id` 是节点尝试主键，`node_id` 是 Pipeline 字符串节点 ID；API 继续使用 processId 标识尝试。
8. 核心验证为：杭州源 OSS 密钥直下载并保留文件 → 本地 repair/qualify → 三个实体各一个任务 → 上传另一 OSS Bucket → 本地 FTP 下载验证 size/SHA → DELIVERED。
9. 不经过 SSH 跳板机，不使用 CRR、新加坡中转、远端 SSH 发布或旧回执链。新增协议只新增 Pipeline 节点。
10. 交付实体维护厂商、协议、账户、执行组和非 Secret 目录；Secret 只属于 Pipeline 对应节点，不进入实体、日志、事件、Manifest、看板或普通 API。
11. 超过 cutoff_at 未完成任务即 EXPIRED，并生成指定下一交付日的新 task_id；delivery_id/data_id/entity/worker_group/target_seq/artifact 不变。新任务占用下一交付日对应实体配额，主单未全部成功前为 PARTIALLY_DELIVERED，全部成功后转为 DELIVERED。

## 设计依据

1. `docs/superpowers/specs/2026-11-06-archive-delivery-platform-upgrade-design.md`：当前整体方案、API、Pipeline、执行、安全、跨日和验收。
2. `docs/superpowers/specs/2026-11-06-archive-delivery-model-upgrade-design.md`：当前 ER、字段、JSONB、约束和清空重建契约。
3. 各模块 AGENTS：模块内实施边界。
4. 2026-11-03 及更早文档只作历史参考，不得作为新开发依据。

## 服务模块

| 模块 | 职责 | 指南 |
|---|---|---|
| `delivery-core` | 领域模型、Flyway、PipelineEngine、NodeHandler、提交、目标分配、编号、执行、重试和跨日 | `delivery-core/AGENTS.md` |
| `delivery-server` | Delivery API、实体与 Pipeline 管理、看板、静态前端和健康检查 | `delivery-server/AGENTS.md` |
| `delivery-worker` | PowerJob 通用触发、有界执行和真实端到端测试 | `delivery-worker/AGENTS.md` |
| `web` | 实体目标提交/看板、任务时间轴、retry/resume | `web/AGENTS.md` |
| `nodes/python` | 本地安全 repair、请求映射、token/SHA/产物和 FTP 验证 | `nodes/python/AGENTS.md` |
| `nodes/shell` | 仅必要的受控本地辅助脚本；禁止跳板机中继 | `nodes/shell/AGENTS.md` |
| `deploy` | 本地 OSS/FTP 验证拓扑、镜像和部署模板 | `deploy/AGENTS.md` |
| `scripts` | 构建、启动、预检、提交、续跑和真实验收 | `scripts/AGENTS.md` |
| `docs` | 当前设计、安装运维和历史资料 | `docs/AGENTS.md` |

## 核心模型

| 表 | 职责 |
|---|---|
| `delivery_submit` | 交付身份、批次、日期、截止、Pipeline 和 target_strategy |
| `source_data` | 源定位、过滤、请求映射、size/token/SHA 和严格七字段 artifacts |
| `delivery_entity` | 厂商实体、协议、账户、worker_group 和非 Secret 定位 |
| `delivery_task` | 一个 artifact 面向一个实体的执行原子，含 worker_group 快照和 target_seq |
| `delivery_node` | 节点尝试，保留 delivery_id/task_id、文件名和通用 locator |
| `delivery_event` | 过滤、人工原因、跨日和外部核验的不可变事件 |
| `ingest_source` | OSS URI、可用状态、渠道、类型及最近两次刷新字节数 |
| `pipeline/pipeline_version` | 流程身份和集中不可变版本快照 |

## 提交与执行契约

核心 API：

```text
POST /api/v1/deliveries
GET  /api/v1/deliveries/{deliveryId}
POST /api/v1/deliveries/{deliveryId}/resume
POST /api/v1/deliveries/{deliveryId}/rollover
GET  /api/v1/deliveries/{deliveryId}/tasks
POST /api/v1/tasks/{taskId}/retry
GET  /api/v1/tasks/{taskId}/timeline
```

提交参数为来源、PipelineVersion、交付日期、截止、批次、幂等键和 targets；核心验证三个 targets 各 targetCount=1。不得提交 group/groupCode、Secret、节点列表或任意 Shell。

`TaskExecutionService` 是唯一任务推进入口。稳定内核负责事务、claim、lease/fence、编号、SHA、安全路径、重试和幂等；NodeHandler 不直接 UPDATE 任务或提交状态。提交聚合不能由 PowerJob 实例状态代替，只有最终节点和外部核验完成才算成功。

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

## 开发约束

* 新增代码写有效业务注释，注释表达用途和必要约束，不要只复述接口或函数名称，说明输入输出事实及安全/状态边界。
* 新增复杂业务逻辑时应优先保证业务的可扩展性，拆分职责，控制函数复杂度，并对稳定流程和可变策略建立清晰边界，可根据实际场景适当使用策略，工厂、模版方法，责任链等设计模式，但不得为了套用设计模式引入没有必要的抽象层级。
* 每次变更必须更新项目模块里的agent.md,总结每个模块的功能和用途，和必要的一些约束。
* 在代码没有提交git前，所有改动，不做老字段、老 API、老节点或老数据兼容，不留平行实现。
* 修改模型必须同步迁移、Domain、Repository、服务、API、前端、脚本、测试和对应 AGENTS。
* 所有目标表、列、枚举必须有 COMMENT ON；JSONB 必须有 Schema、用途和白名单字段。
* 在代码没有提交git前，不处理无关既有失败或用户文件；测试临时产物必须清理，真实验收要求保留的产物除外。

## 核心真实验收

必须同时证明：杭州源 OSS 三份样本直接 GET 且本地文件保留；repair/qualify 成功；三个实体各一任务；另一 OSS Bucket 三件产物 size/SHA 与数据库一致；本地 FTP 三账户下载三件产物且 size/SHA 一致、无 `.partial`；最新任务全部 SUCCEEDED、最终 delivery_node 成功、提交 DELIVERED；单任务 retry/resume 和跨日新 task_id 接续通过，deliveryId/entity/targetSeq 不变。状态集成验收使用小样本，覆盖三个实体各 2 个：截止前全部成功为 DELIVERED；截止未完跨日后为 PARTIALLY_DELIVERED；指定次日继续并占次日配额，全部成功后 DELIVERED。验收记录必须附文件清单和 SHA 自助复核命令。
