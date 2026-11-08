# docs 模块指南

## 文档职责

本目录保存项目设计、安装运维和历史资料。**两份 2026-11-06 文档的 2026-11-07 实体目标修订是唯一实施基线**；数据库迁移和真实外部链路状态必须以实际运行证据为准。

## 阅读顺序

1. `superpowers/specs/2026-11-06-archive-delivery-platform-upgrade-design.md`：当前整体方案、集中 Pipeline、targets API、跨日和验收。
2. `superpowers/specs/2026-11-06-archive-delivery-model-upgrade-design.md`：当前表关系、字段、JSONB、约束和 V14 清空重建。
3. 根目录和模块 AGENTS：实施约束。
4. `install.md`、`deploy.md`、`mvp-runbook.md`：当前环境操作手册；真实主机、账户和凭据只保存在未跟踪的受控本地配置中。
5. 2026-11-03 及更早 specs/plans 仅作历史参考，不进入新方案。

## 当前设计口径

- 核心血缘为 `delivery_submit → source_data → delivery_task → delivery_node`。
- 提交使用 targets 直接绑定实体；任务含 delivery_entity_id/worker_group/target_seq，无交付组模型。worker_group 是单服务器本地执行域，组内认领和并发独立，跨机不共享目录。
- 节点定义、规则、DAG、执行配置和 Secret 集中在 pipeline_version.definition，无平行节点/规则/绑定表。
- 核心验证为杭州源 OSS 直下载、本地 repair、三个实体各一、另一 OSS、本地 FTP 下载验证。
- 跨日旧任务 EXPIRED，新 task_id 按指定下一交付日接续并占次日实体配额，deliveryId/entity/targetSeq 不变；主单 PARTIALLY_DELIVERED 至全部成功后 DELIVERED。
- V14 清空重建，V15 增加部分交付状态；不写迁移映射、旧 API 兼容或双写。

## 维护规则

1. 新设计使用 `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`，实施计划使用 plans 目录。
2. 新文档标明日期、事实状态、范围、数据与状态流、安全、失败处理、测试和验收，不留 TBD。
3. 模型文档覆盖表/列/枚举注释、关系、JSONB 白名单、唯一约束和索引。
4. 修改业务行为同步当前设计、运维文档和相关 AGENTS；不能只改文档声称完成。
5. 历史文档不随意改写，通过当前设计说明替代；当前文档不保留已否决平行方案。
6. 示例使用脱敏 URI、账号、Bucket 和主机，不新增真实 Secret 或签名 URL。
7. 真实验收记录实际 deliveryId/taskId、时间、实体数量、OSS/FTP size/SHA、最终节点和保留文件复核命令；skipped/fake/历史记录不能冒充本次通过。
