# delivery-core 模块指南

## 模块职责

`delivery-core` 是 server 与 Worker 共用的业务核心，负责领域对象、PostgreSQL/Flyway、集中式 PipelineVersion、NodeHandler、业务编码提交、目标分配、请求编号、暂停、重试、跨日和 OSS/FTP 适配。唯一实施基线是两份 2026-11-06 文档的 2026-11-08 修订，不做老 API 或老数据兼容。

## 当前模型与提交

- 核心表：`delivery_submit`、`source_data`、`delivery_entity`、`delivery_mapping`、`delivery_task`、`delivery_node`、`delivery_event`、`ingest_source`、`pipeline/pipeline_version` 及请求编号表。
- `delivery_mapping(task_id,source_seq)` 定义任务完整有序来源，复合外键保证同 delivery_id；禁止恢复 delivery_task_source 或任务 data_id。
- `source_data` 保存 original_file_name 和单个原始 `artifact` 对象，不保存 original/new_request_id 或 artifacts 数组；created_at/updated_at 默认当前时间，更新触发器维护 updated_at。新增模型表必须默认同时带 created_at/updated_at。original_locator.cachePath 按 URI+版本指向同机共享缓存，跨交付和其他厂商复用时不重复 GET，缓存缺失的执行组回退 OSS。
- `delivery_task` 保存 entity_code 快照，不保存 target_seq、artifact_seq、original_file_name、sha256、superseded_by_task_id、invalidated_at、invalidation_reason 或单数 artifact。
- `delivery_task.artifacts[]` 每项严格为 dataId/sourceArtifact 两字段，sourceArtifact 必须等于对应 source_data.artifact，不保存 deliveryArtifact。metadata_ids 是与 delivery_mapping、artifacts 同顺序的 source_data.data_id 正整数数组，不保存 requestId；任务级 new_file_name 唯一，最终 size/SHA 取 repair 成功节点 output_locator，任务 size/token 由 repair 补丁更新。
- `delivery_task.new_file_name` 使用整个 delivery_id 内连续的 batch_NNNNN 序号；有效任务在 delivery_id 下文件名全局唯一，跨日 EXPIRED 后复用原名。
- `delivery_submit.delivery_batch` 全局唯一，是幂等键和请求编号命名空间；request_id_sequence/allocation 以此外键约束。
- SubmissionCommand 只接收 ossUri、pipelineCode、deliveryDate、deliveryBatch、targets[vendorCode,entityCode,targetCount]。target_strategy 保存同一复合业务键快照，同 vendorCode 的多个 entityCode 目标整体分配；cutoffAt 由不可变 Pipeline 配置推导。
- 相同批次相同规范请求返回原 deliveryId，不同请求 409；PAUSED 主单相同请求再提交将任务恢复 READY。

## 稳定执行约束

1. PostgreSQL 是事实源；TaskExecutionService 是唯一任务状态推进入口，NodeHandler 不直接 UPDATE 任务或提交状态。
2. delivery_node.process_id 是尝试主键，node_id 是 DAG 字符串 ID；locator 不得包含 Secret。
3. 来源原始 size/SHA/artifact 不被合并产物覆盖；repair 只更新来源 qualification/request_namespace，执行版本通过主单 pipeline_version_id 追溯。
4. PAUSED 只在无 RUNNING 的节点边界设置，不被 claim；跨日 EXPIRED 与新 taskId 的关系写 TASK_EXPIRED/TASK_REGENERATED payload。
5. 跨日复制完整 mapping、artifacts、metadata_ids、entity/entity_code/worker_group/new_file_name，并复制 rolloverStartNode 之前各节点最新成功 locator，保证新 taskId 可读取 repair size/SHA；主单 PARTIALLY_DELIVERED 至全部成功。
6. WAITING 不增加失败次数；权限、配置和 SHA 冲突 BLOCKED；临时网络/IO 有界退避。
7. 同 URI 同 SHA 幂等，不同 SHA 阻断；禁止清空 Bucket/FTP 或覆盖其他任务。
8. worker_group 是单服务器本地执行域，claim/retry/rollover 不跨组。

## 合并、分配与修复性能边界

当前 DAG：`sourceDownload → mergePackages → repair → qualify → targetUpload → ftpDownloadVerify`。

- targetCount 展开为合并任务槽位数，不另设 selection.limit。OSS 按 key 顺序分页扫描，每页尝试满足全部任务槽位，成功后停止；单包超过 maxPackageBytes 时下载前过滤并继续。同 vendorCode 目标优先按连续来源分组，无法满足下限时再按字节和回溯其他组合，组内保持来源顺序，禁止切分原始文件。列表耗尽后来源合计仍低于 minPackageBytes 时规划失败。
- package.merge 对每个源包只安全解包一次，输出 merge/requests 工作目录和 workspacePath，不生成 merged-source.tar.gz。
- python.repair 直接消费工作目录，在私有 staging 副本修复，只执行一次最终 tar/gzip 封存；不得恢复重复压缩/解压。`gzipCompressLevel` 由不可变 Pipeline 固定为 1–9，默认 9，生产当前为 5；最终包仍必须通过 min/max 与 SHA 校验。
- repair 接收 entityCode，协议 metadata.providerCode 写 entityCode；vendorCode 用于厂商分配，外部路径模板支持 providerCode、entityCode 和 accountName，生产以 accountName 分隔 june/june-2 目录。request_namespace 取 deliveryBatch。
- qualify 校验 dataId/sourceArtifact、metadataIds 与 sourceDataIds 同序相等以及 repair locator 的真实 size/SHA；最终包必须处于 minPackageBytes/maxPackageBytes 边界内。六个 DAG 节点使用 SLF4J 记录开始/成功及必要血缘，超过五行的关键执行方法逐条业务语句添加注释。

## 集中配置与测试

pipeline_version.definition 统一保存 nodeDefinitions、nodes、assignment、rollover、retry、concurrency 和 Secret，不接受独立 ruleSets、selection 或 repair.ruleSet。package.merge/python.repair/ftp.download-verify 必须通过 config.scriptPath 指定固定脚本目录内的规范化相对 .py 路径；Worker 统一从 `/app/nodes/python` 解析并在执行前核对文件存在，不使用 Shell 或 Worker 脚本路径环境变量。Secret 只写不读并脱敏，不进入 Domain 投影、locator、artifacts、事件或日志。

```bash
./scripts/mvn.sh -pl delivery-core test
```

模型修改同步迁移、Domain、Repository、服务、API、前端、脚本、测试和 AGENTS。所有目标表、列、枚举必须 COMMENT ON；JSONB 必须有 Schema 和白名单。Testcontainers 只作开发辅助；模型或流程变化必须在真实本地 Compose 或生产环境完成端到端验证，skipped/fake/直写 case 不得作为交付依据。
