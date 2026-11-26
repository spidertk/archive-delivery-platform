# Archive Delivery Platform 维护基线

## 当前基线

Archive Delivery Platform 基于 PowerJob + 不可变 PipelineVersion。PostgreSQL 是业务事实源，PowerJob 只触发通用入口。流程固定为 `archive-batch` 创建并规划 OSS 批次，`archive-delivery` 多次消费批次包。

- 批次基线为 `V25__batch_delivery_baseline.sql`，V26 增加来源监控与三个渠道登记，V27 切换聚合卡片，V28 精简渠道，最新 V29 合并为两张监控表及只读看板；全新库创建当前模型，已有线上 V24 数据在迁移中转入批次关系。不得恢复旧运行时流程或平行模型。
- 批次创建只下载 OSS、归档 `source_data`、规划 `delivery_batch`，交付 DAG 为 `sourceDownload → mergePackages → repair → qualify → targetUpload → ftpDownloadVerify`。
- 核心血缘：`BATCH_CREATE delivery_submit → source_data/delivery_batch → BATCH_SUBMIT delivery_submit → delivery_task → delivery_node`。`delivery_mapping` 和 `delivery_task.metadata_ids` 已删除。
- `deploy/pipelines/*.md` 是 Pipeline 和黄金用例的唯一 review 来源；生成配置不提交、不进入镜像。
- 本地基线为 `scripts/local-pipeline.sh`，不访问真实 OSS/FTP；生产基线为 `scripts/production-registry.sh <caseId|all>`，必须执行真实外部验证。

## 开发约束（不要随意更改已有约束）
* 新增代码写有效业务注释，方法内代码超过五行，必须要有明确注释，注释表达用途和必要约束，不要只复述接口或函数名称，说明输入输出事实及安全/状态边界。
* 新增复杂业务逻辑时应优先保证业务的可扩展性，拆分职责，控制函数复杂度，并对稳定流程和可变策略建立清晰边界，可根据实际场景适当使用策略，工厂、模版方法，责任链等设计模式，但不得为了套用设计模式引入没有必要的抽象层级。
* 每次变更同步相关模块 AGENTS.md,总结每个模块的功能和用途，和必要的一些约束。
* 未提交 git 前不做老字段、老 API、老节点或老数据兼容，不留平行实现。
* 修改模型必须同步迁移、Domain、Repository、服务、API、前端、脚本、测试和对应 AGENTS。
* 所有目标表、列、枚举必须有 COMMENT ON；JSONB 必须有 Schema、用途和白名单字段。
* 不处理无关既有失败或用户文件；测试临时产物必须清理，真实验收要求保留的产物除外。


## 项目约束

1. 批次创建接口只接收 `ossUri + pipelineCode(archive-batch) + deliveryDate + deliveryBatch`；交付提交只接收同名单元及 `targets[vendorCode,entityCode,targetCount]`。cutoffAt 由 Pipeline 推导，不传数据库 ID、Secret、节点列表或 Shell。
2. BATCH_CREATE 的 deliveryBatch 唯一且 submitted_at 为空；BATCH_SUBMIT 可在同一批次独立提交多次，每次按 targetCount 领取未消费包，不走旧追加或重复请求幂等逻辑。
3. `source_data.unique_key=sha256` 全局唯一；source_data → delivery_batch 为 1:N。delivery_batch 保存原始/合并文件名、source_seq、实体、worker_group、消费 delivery_id 和状态。
4. 批次创建按 maxPackageBytes 顺序装箱全部包，不设置合并包下限，剩余小尾包也必须交付；当前原始包和最终包最大 100 MiB，不切分原始文件，超限包下载前过滤。交付任务 new_file_name 必须等于批次包名，并通过 delivery_id+new_file_name 获取来源。
5. `archive-batch` 的 `assignment.entityWorkers` 配置实体与实际 worker_group 的 `maxPackagesPerRound`；按容量逐轮分配全部包，任务创建时固化实际机器组，retry/resume/跨日不得跨组。
6. merge 对每个源包只安全解包一次；repair 在私有 staging 修复并只最终封包一次，生产 gzipCompressLevel=5。包大小上限从 BATCH_CREATE 的 batch_extension 读取。新批次规划来源合计预留 5% 修复开销；已有包 repair 超限时只移动完整源包到追加批次包并从 sourceDownload 重跑，禁止切分原始文件或放宽上限。
7. repair 使用 entityCode 写 metadata.providerCode，保留原始 requestId 和目录名（支持 UUID 等真实格式）；缺失、冲突或重复目录直接失败，不补号、不改名。requestTime 保留源日期并规范为 `yyyy-MM-dd HH:mm:ss`；拼接 gzip/TAR 必须完整读取。PostgreSQL 死锁和超限重规划冲突按有界退避重试，不直接 BLOCKED。
8. 同 URI+版本源包仅复用同机 source-cache，跨执行组没有缓存时回退真实 OSS，不共享工作目录。
9. Secret 只属于 Pipeline 节点，不进入实体、日志、事件、Manifest、看板、普通 API、证据或 CI。
10. 禁止清空 OSS/FTP 历史、同名不同 SHA 覆盖、SQL 直写 SUCCEEDED/DELIVERED、删除租约或伪造节点结果。
11. 同批次状态修改统一在短事务先锁 BATCH_CREATE 父单，再修改任务/包/提交和投影；节点结果、运维操作与跨日保持相同锁顺序，外部节点 IO 不持有该锁。

## 黄金验证与 CI

1. 每个 Markdown Pipeline 必须声明定义、targets、expected.taskCount/nodeTypes 和生产外部检查；缺少 NodeHandler 或黄金用例不得合入。
2. `GoldenPipelineDefinitionTest` 检查真实 NodeHandler 注册并用无外部 IO 替身执行同一 DAG，不作为交付证据。
3. 默认本地验证包括 MD 注册、Python/Maven/前端测试、V25 建库幂等、Shell/Compose 静态检查，不访问真实业务文件。
4. 生产每个 case 独立预检、创建批次、提交、等待 DELIVERED，并核对 SOURCE_GET、TARGET_GET、FTP_GET、SIZE_SHA、NO_PARTIAL。
5. 证据必须包含实际 batchDeliveryId/deliveryId/taskId、PipelineVersion、迁移版本、URI、size/SHA、原始文件/批次包/任务数量和复核命令。
6. skipped、fake/Local Node、仅 Repository 写入、PowerJob 绿色实例、API ACCEPTED 和本地替身均不能作为生产交付通过依据。

## 模块

| 模块 | 当前职责 |
| --- | --- |
| `delivery-core` | V25、批次创建/规划/消费、PipelineEngine、NodeHandler、执行、暂停、重试和跨日 |
| `delivery-server` | 批次与交付 API、实体/Pipeline 管理、看板、静态前端、健康检查及独立 service/ingestmonitor 渠道监控 |
| `delivery-worker` | PowerJob 通用触发、有界执行、黄金定义和生产外部验证 |
| `web` | 批次创建、交付提交/看板、暂停继续、任务时间轴、retry/resume |
| `nodes/python` | 安全解包、目录合并、repair、一次封包和 FTP 验证 |
| `deploy` | 生产 Compose、镜像、受控配置模板和 Markdown Pipeline 注册 |
| `scripts` | 本地门禁、生产注册、PowerJob 初始化、配置发布和证据采集 |
| `docs` | 当前黄金验证、生产运维和 AI 维护规范 |

## 常用命令

```bash
./scripts/local-pipeline.sh
python3 -m unittest nodes/python/test_archive_repair.py scripts/test_pipeline_registry.py -v
./scripts/maven-test.sh test
cd web && npm ci && npm run test && npm run build
./scripts/production-registry.sh archive-batch-jun
./scripts/production-registry.sh all
```

生产真实写入必须显式授权并先通过预检。失败不得继续，未执行或 skipped 均记为未验证。日常运维见 `docs/production-operations.md`，AI/PR 规则见 `docs/pipeline-maintenance.md`。

## jun-2 日期保留策略

2026-11-21 经用户明确授权，jun-2 的 archive-batch 每轮容量为 6000 包。FTP 节点通过不可变 Pipeline 的 cleanupOtherDeliveryDates 显式启用日期保留，只删除直属根目录下其他合法 YYYY-MM-DD 目录；保留当前日期、非日期目录和 OSS 历史。每个 worker_group 必须共享同机 retention 锁，跨日期切换等待在途传输，旧日期回退拒绝清理。本次旧测试流水重置为一次性授权，不形成日常自动清库入口。

## jun-2 8 核 16G 资源档

2026-11-25 用户明确授权磁盘不足时优先清理无用本地缓存，保障当天交付。本次 `20261125-fix` 批次 8 可显式启用 `PROD_EVICT_VERIFIED_SOURCE_CACHE=true`：仅回收本次已独立验收提交的可重建源缓存与 FTP 回验副本，旧批次未消费的规划引用不阻止回收；本批次未交付来源及所有已建未完成任务引用仍保护。删除前复查引用和 size/SHA，逐文件留证，保留最终 OSS/FTP、manifest、数据库事实和租约。

生产模板为 8 个同机 group1 Worker、组内并发 8、批次下载线程 16，Worker/API heap 上限各 512 MiB，每 Worker PG 连接池上限 4；所有 Worker 共用一个应用镜像并继承相同环境与本地卷。maxPackagesPerRound=6000 不变。资源配置与实际吞吐分开验收，旧小内存实测不能作为新规格性能证据；发布和容量边界见 docs/jun-2-capacity.md。

## 来源监控边界

来源监控不改变 V25 批次交付血缘。最新 V29 仅保留 ingest_monitor_record 与 ingest_monitor_notification；record_type=OBJECT 保存当前快照，SAMPLE 保存增量与趋势，以 CHECK 和部分索引隔离。V29 直接删除旧 object 表、将 sample 重命名，不保留旧表或兼容视图；状态仍复用 ingest_source。渠道由桶+第一级目录推导，同渠道登记范围聚合去重且扫描范围保持原 URI。业务集中在 delivery-server/service/ingestmonitor。原生群卡片链接受控 IP 的 /monitor 只读页面，页面展示存量、基线后累计进数、北京时间今日进数与真实成功上传的目标桶；不把存量当成传输量。存储费只按新加坡标准 LRS 官方价 US$0.017/计费GB/月估算，按有日期的参考汇率统一折算人民币，顶部明确标记当前总存储量存放一个月的预估总费用；标明预估与监控范围，不计算下载费。公网入口只放行看板、静态资源与限定 GET 监控查询；管理 API 保持回环。每日一次、持续进数 30 分钟后主动及小时非空增量规则保持，只列实际进数目录。

本次用户授权的群机器人 webhook 是通知专用运行时 Secret，仅保存在 API 受控 .env；OSS/FTP 凭据仍只属于已发布 Pipeline 节点。所有 Secret 均不得进入来源表、观察事实、日志、看板、普通 API、证据或 CI。生产通知只有企业微信 errcode=0 才算成功，不以 HTTP 200 或 outbox 入队代替。

V30 为 FTP 实体与交付日原子预留序号。启用 useFtpFileName 的 PipelineVersion 在 OSS 与 FTP 使用同一个 ftp_file_name，OSS 前缀按日期、厂商与交付实体隔离。内部来源血缘保持 canonical new_file_name。
