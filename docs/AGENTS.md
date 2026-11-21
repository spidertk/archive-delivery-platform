# docs 模块指南

## 当前文档

- `pipeline-maintenance.md`：AI 维护、Markdown 黄金用例、本地无外部验证、CI 审批和新增 Pipeline review。
- `production-jun-runbook.md`：生产 Compose、alibaba/jun 自动流水线和证据清单。
- `production-operations.md`：日常状态、磁盘、故障、升级和外部复核。
- `deploy.md`：当前生产部署和 V25 批次基线说明。
- `服务器信息/服务器1.md`：主服务器实际配置、端口边界和当前验证事实。
- `服务器信息/服务器1主机部署.md`：给 AI 按变量替换后顺序执行的主机部署手册；已有 FTP 设置 `COMPOSE_PROFILES=` 复用老服务，仅在无老 FTP 时设置 `embedded-ftp` 启用内置 ftp-june。
- `服务器信息/任务机扩展部署.md`：给 AI 执行的任务机/实体扩展手册，包含隧道、Worker、老 FTP 配置复用、Pipeline 发布和验收；FTP 账户与密码按实际服务端配置同步。
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
