# delivery-core 模块指南

## 职责

领域模型、V25 基线、PipelineEngine、NodeHandler、OSS 批次创建/规划、批次交付消费、执行、暂停、重试、跨日及 OSS/FTP 适配。PostgreSQL 是唯一业务事实源。

## 当前模型

- 血缘为 BATCH_CREATE delivery_submit → source_data/delivery_batch → BATCH_SUBMIT delivery_submit → delivery_task → delivery_node。
- archive-batch 准备阶段全量下载 OSS、按 sha256 写 source_data.unique_key，并按包大小和 entityWorkers 容量规划 delivery_batch；archive-delivery 准备阶段只领取未消费批次包。
- BATCH_CREATE 的 deliveryBatch 唯一，BATCH_SUBMIT 同批次可独立提交多次；不存在旧追加提交、source_data 重复归档或 delivery_mapping。
- delivery_task.artifacts 只含 dataId/sourceArtifact，来源由 delivery_batch.delivery_id+new_file_name 关联；metadata_ids 已删除。
- DeliveryProjectionService 同步提交、批次文件和 BATCH_CREATE 总状态；只有全部规划包 DELIVERED，批次创建总单才 DELIVERED。`batch_extension.packageCount` 只表示已交付包数，初始为 0，不随重规划递增；规划总包数只从 delivery_batch 实时统计。
- 节点结果、retry/resume/pause、跨日和投影短事务先锁 BATCH_CREATE 父单行，再修改任务、包及提交，与准备和超限重规划锁顺序一致；同批次并发节点不得无序重写全部包。状态未变化的包不重复 UPDATE；外部节点 IO 不得放入父批次锁范围。并发集成测试使用八个真实 PG 事务、两个提交和多来源包验证处理/重试/完成投影。
- 实体与机器容量在 archive-batch `assignment.entityWorkers` 配置，按 maxPackagesPerRound 轮询分配；任务固化实际 worker_group。
- repair/merge/qualify 从提交 batch_extension 读取批次创建时固化的包大小上限，不设置合并包下限；生产 gzipCompressLevel=5。
- TaskExecutionService 是唯一任务状态推进入口，Worker 不跨组认领、retry/resume/rollover。
- ProcessSupport 在超时、中断和 stdin 失败的所有退出路径结束本次数据面进程子树，并有界等待退出、保留线程中断标记，不能让遗留 Python 与正常重试并写同一任务目录；子进程回收不修改 PG 租约或业务状态。
- 统一执行入口在子进程回收后暂时清除中断标记，先持久化真实失败与有界重试决策，再恢复中断标记，避免连接池等待被中断而留下 RUNNING 状态。

## V25 基线

`V25__batch_delivery_baseline.sql` 创建当前模型并将已有 V24 任务关系迁入 delivery_batch。最终模型不存在 delivery_mapping 和 metadata_ids，不保留旧运行时分支。

## 来源监控迁移

V26→V28 为已发布的历史迁移。最新 V29 将 object 快照合入原 sample 并重命名为 ingest_monitor_record，直接 DROP 旧 object；最终只有 record/notification 两张监控表，无兼容视图。record_type 以 CHECK 和部分索引区分当前 OBJECT 与历史 SAMPLE，保留既有采样 ID、状态、通知及真实 ACK。来源窗口和通知日报游标改用 record_id 命名且只读取 SAMPLE。渠道按桶下第一级目录自动推导，同桶一级目录归并但保留业务 source_id/原始 oss_uri。JSONB 当前原生卡片只能链接受控 /monitor，只读历史正文/Markdown/卡片均保留，交付血缘不变。

## 验证

```bash
./scripts/maven-test.sh -pl delivery-core test
```

`ArchiveModelIntegrationTest` 验证 V25、唯一键、两次独立提交、批次消费、状态联动、暂停和跨日；`BatchPlanningServiceTest` 验证动态装箱与多机轮询容量。模型变化必须同步迁移、Domain、Repository、服务、API、前端、脚本、测试和模块文档。

## FTP 与容量边界

FtpNodeConfig.cleanupOtherDeliveryDates 由不可变节点控制，Java 传入任务 deliveryDate 与同机共享 retentionRoot。FTP 上传用 .partial，完整下载 size/SHA 核验后原子改名；旧日期重试不得清理较新日期。claimNext 在同组事务 advisory lock 内检查机器容量并认领，避免多个 Worker 并发超额；锁不替代租约和 fence。

V30 每个 FTP 实体每天独立累计 ftp_file_name 和 ftp_sequence。OSS 上传的 useFtpFileName 要求日期、厂商及实体隔离，跨日重跑 OSS 上传。历史不可变版本沿用原对象键，来源关系与本地包仍使用 canonical 名。
