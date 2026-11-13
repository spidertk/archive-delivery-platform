# docs 模块指南

## 当前文档

- `pipeline-maintenance.md`：AI 维护、Markdown 黄金用例、本地无外部验证、CI 审批和新增 Pipeline review。
- `production-jun-runbook.md`：生产 Compose、alibaba/jun 自动流水线和证据清单。
- `production-operations.md`：日常状态、磁盘、故障、升级和外部复核。
- `deploy.md`：当前生产部署和 V24 基线说明。
- `evidence/PROD-JUN-20261111-205140/`：当前成功黄金生产 case 的真实证据。

不维护旧 MVP、过渡设计、失败历史或历史迁移说明。

## 维护规则

1. 文档只描述当前实现，标明验证模式、实际 ID、迁移版本、外部 size/SHA 和复核命令。
2. 本地逻辑测试、fake/skipped、旧记录不得冒充本次生产通过。
3. 新 Pipeline 必须同时更新 MD、处理器、测试、部署和运维文档。
4. 示例不得新增真实 Secret、签名 URL、密码或私钥；证据目录不得包含完整 definition 或受控配置。
