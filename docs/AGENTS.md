# docs 模块指南

## 文档职责

本目录保存项目设计、安装运维和历史资料。两份 2026-11-06 文档的 2026-11-08 修订是唯一实施基线；数据库迁移和真实外部链路状态必须以实际运行证据为准。

## 阅读顺序

1. `superpowers/specs/2026-11-06-archive-delivery-platform-upgrade-design.md`：整体方案、entityCode API、厂商分配、暂停、合并优化和验收。
2. `superpowers/specs/2026-11-06-archive-delivery-model-upgrade-design.md`：source_data 单原始 artifact、delivery_mapping、任务 artifacts 对、metadata_ids、约束和事件血缘。
3. 根目录和模块 AGENTS：实施约束。
4. `install.md`、`deploy.md`、`mvp-runbook.md`：当前环境操作手册。
5. `production-jun-runbook.md`：单机交付机生产 Compose、alibaba/jun 自动预检提交和证据清单；`production-operations.md`：日常状态、磁盘、故障、升级和外部复核。
6. `2026-11-09-production-jun10-deployment.md`、`2026-11-09-production-jun10-test-plan.md`：早期远程 Agent 方案，已被当前单机 FTP Compose 方案取代，不作为执行入口。
7. 2026-11-03 及更早 specs/plans 仅作历史参考。

## 当前设计口径

- 核心血缘为 `delivery_submit → source_data → delivery_mapping → delivery_task → delivery_node`。
- 提交只使用 ossUri/pipelineCode/deliveryDate/deliveryBatch/vendorCode+entityCode targets；cutoffAt 由 Pipeline 配置推导，同厂商实体整体分配，repair 使用 entityCode。
- source_data 保存 original_file_name、单 artifact 和 created_at/updated_at；新增模型表默认同时带创建与更新时间。
- 任务 artifacts 仅含 dataId/sourceArtifact，sourceArtifact 等于 source_data.artifact，不保存 deliveryArtifact；metadata_ids 是与 mapping 同顺序的 data_id 数字数组，最终 size/SHA 取成功节点 locator；有效包名在同一 delivery_id 下全局唯一。
- merge 只解包并输出请求目录，repair 直接修复并只最终封包一次，消除重复压缩/解压；targetCount 决定任务槽位，持续分页扫描到全部槽位满足，无 selection.limit、独立 ruleSets 或重复 rule_version；三个 Python 节点通过目录内相对 scriptPath 选择脚本，Compose 统一挂载整个 nodes/python；超限包过滤，小源包合并且不切分。
- PAUSED 只在节点边界设置，相同批次原样再提交继续；跨日新旧 taskId 由不可变事件关联。
- 本地真实核心 case 使用指定 sg-storage products 来源，持续下载到每包 8–10 MiB 形成两个实体任务、每组上传一件，完成两个 OSS/FTP、暂停续交、retry 和跨日验收。

## 维护规则

1. 新设计使用 `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`，实施计划使用 plans 目录。
2. 新文档标明日期、事实状态、范围、数据与状态流、安全、失败处理、测试和验收，不留 TBD。
3. 模型文档覆盖表/列/枚举注释、关系、JSONB 白名单、唯一约束和索引。
4. 修改业务行为同步当前设计、运维文档和相关 AGENTS；不能只改文档声称完成。
5. 历史文档不随意改写；当前文档不保留已否决平行方案。
6. 示例使用脱敏 URI、账号、Bucket 和主机，不新增真实 Secret 或签名 URL。
7. 真实验收记录实际 deliveryId/taskId、迁移版本、启动日志、外部 size/SHA 和保留文件复核命令；skipped/fake/历史记录不能冒充本次通过。
