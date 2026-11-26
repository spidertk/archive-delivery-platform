# docs 模块指南

## 当前文档

- `pipeline-maintenance.md`：AI 维护、Markdown 黄金用例、本地无外部验证、CI 审批和新增 Pipeline review。
- `production-jun-runbook.md`：生产 Compose、alibaba/jun 历史自动流水线示例和证据清单；当前 jun-2 资源档链接到 jun-2-capacity.md。
- `production-operations.md`：日常状态、磁盘、故障、升级和外部复核。
- 重建 API 后须重载 monitor-web 的 Nginx 并复核 `/monitor` 与限定 GET，避免保留旧容器 IP 导致 502。
- 同文档的全量续跑章节：生产预检后按最多 100 包的轮次消费全部剩余包，每轮独立外部验收后按文件白名单回收可重建本地副本，保留最终交付和事实证据。
- 2026-11-25 已授权低磁盘优先保障当日交付；显式 PROD_EVICT_VERIFIED_SOURCE_CACHE=true 可回收本次已验收、无未完成任务引用的源缓存，忽略旧批次未消费规划引用，删除前复查并逐文件留证。
- 独立验收容器采用 2 GiB 上限的私有 `/tmp` tmpfs 减少目标包临时落盘，完整外部 GET/SHA 约束保持；性能结论仅引用完整轮次实测。
- `jun-2-capacity.md`：jun-2 8 核 16G 的生产资源配置、发布边界和运行核验；资源配置通过不代表吞吐实测通过。
- `evidence/20261125-fix/`：本次 6,819 个源包/3,541 个规划包的两包试跑、独立内容与外部验收、并发投影死锁修复和全量运行证据；全量完成前不得写成全部通过。
- 同批次第 19 单中断后 repair staging 清理竞争的修复证据保留在同目录；ProcessSupport 中断回收子进程、repair 任务目录排他锁及真实重试验收均须分别记录，不能仅凭恢复 API 接受判通过。
- 同目录的 `audit-ftp-directory-uniqueness.py` 只读完整扫描 FTP 当日成品快照，跨包核对请求目录及 requestId 唯一性；排除在途 `.partial`，明确列出快照后新增文件，不能将快照通过当成未来全日通过。
- `evidence/INGEST-MONITOR-20261122/`：jun-2 渠道来源 LIST、趋势、微信发送确认及静默停止的真实观察证据。
- `evidence/INGEST-CARDS-20261122/`：V27 原生单卡日报、目录树统计、窗口规则、历史保留及真实微信 ACK 验收。
- `evidence/INGEST-MINIMAL-20261122/`：历史 V28 七表转三表、一级目录渠道归并、仅展示进数目录、无登录跳转摘要及真实微信群 ACK 验收。
- `evidence/INGEST-CNY-20261122/`：人民币存储单价换算、汇率快照及顶部整月总额的发布验收。
- `evidence/INGEST-DASHBOARD-20261122/`：V29 两表合并、只读 IP 页面、官方存储价预估、进数/真实目标桶量及原生卡片 ACK 验收。
- `schemas/ingest-monitor-card.schema.json`：当前原生卡片及只读历史 payload 的严格白名单 Schema，历史文本仅保存而不发送。
- `ingest-channel-monitor.md`：两表监控、桶下一级目录渠道、只含新增数据目录的摘要与小时增量、微信群播报、配置和无外部 IO 验证。
- `deploy.md`：当前生产部署和 V25 批次基线说明。
- `服务器信息/服务器1.md`：主服务器实际配置、端口边界和当前验证事实。
- `服务器信息/服务器1主机部署.md`：给 AI 按变量替换后顺序执行的主机部署手册；已有 FTP 设置 `COMPOSE_PROFILES=` 复用老服务，仅在无老 FTP 时设置 `embedded-ftp` 启用内置 ftp-june。
- `服务器信息/任务机扩展部署.md`：给 AI 执行的变量化任务机/实体扩展手册，包含 PostgreSQL advisory 全局扩展锁、PowerJob 隧道、独立 Worker、复用已有 FTP 或扩展机自带 FTP/SFTP 两种模式、Pipeline 映射、分阶段验收和回退；FTP 账户与密码按实际服务端配置同步，不能写入文档。
- `交付机jun.md`：`alibaba/jun`、`group2` 扩展交付机的实际部署手册；共享 jun-2 主库，使用 group2 自己已有的独立 FTP，任务机只创建自己的 Worker 服务。
- `evidence/PROD-JUN-20261111-205140/`：历史单包黄金生产证据。
- `evidence/20261115_v3/`：当前无合并包下限版本，一次创建、两次各 200 包的真实验证报告、性能报告与完整证据。
- `evidence/20261115/`：此前两次各 100 包的历史验证证据。
- `evidence/001/`：历史 deliveryId=3 首批 200、同单追加 500 的最终 700 包真实证据与容量报告。

不维护旧 MVP、过渡设计、失败历史或历史迁移说明。

## 维护规则

1. 文档只描述当前实现，标明验证模式、实际 ID、迁移版本、外部 size/SHA 和复核命令。
2. 本地逻辑测试、fake/skipped、旧记录不得冒充本次生产通过。
3. 新 Pipeline 必须同时更新 MD、处理器、测试、部署和运维文档。
4. 示例不得新增真实 Secret、签名 URL、密码或私钥；证据目录不得包含完整 definition 或受控配置。

## jun-2 本轮验证

本轮验收与性能报告保留在 evidence/JUN2-20261121-REDO/，记录 343 个来源与实际批次包数的区别、旧测试流水重置范围、不可变版本、真实外部复核及小内存主机容量限制。仅实际完成的检查标通过，6000 包配置不代表已压测 6000 包。

2026-11-21 后续扩容为 8 核 16G，于 2026-11-22 发布资源配置，当前资源档见 jun-2-capacity.md，配置与运行核验保留在 evidence/JUN2-20261122-8C16G/；旧性能报告保留原测量硬件和并发口径，不改写成新配置的吞吐证据。

新版 OSS/FTP 同名规则及历史 Pipeline 的路径复核见 production-operations.md。
