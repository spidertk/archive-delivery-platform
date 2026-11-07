# scripts 模块指南

## 模块职责

`scripts` 是构建、启动、预检、实体目标提交、续跑和真实外部验收入口。脚本调用 Delivery API 和 core 领域服务，不绕过 Pipeline、状态机或安全校验。默认 `set -euo pipefail`，外部网络操作必须有超时。

## 脚本地图

| 脚本 | 职责 | 边界 |
|---|---|---|
| `mvn.sh` | 容器化 Maven 构建测试 | 默认不跳过测试 |
| `mvp-test.sh` | Python + Maven 标准回归 | fake/skipped 不代表真实通过 |
| `mvp-up.sh` | 启动数据库、server、Worker 和 FTP | V14 清库需显式授权 |
| `bootstrap-powerjob.sh` | 创建通用准备、推进和截止 Job | 调度重试为 0 |
| `configure-pipeline.sh` | 发布集中节点/规则/DAG/Secret 的版本 | 不打印 Secret，无 binding |
| `preflight.sh` | 检查源/目标 OSS、三个 FTP 实体和数据库 | 失败非零退出 |
| `submit-delivery.sh` | 提交日期/截止/批次/targets/幂等键 | 三实体各 1 |
| `resume-delivery.sh` | 按 deliveryId/fromNode/taskIds/reason 续跑 | 前置不足 409 |
| `verify-delivery.sh` | 按 deliveryId 核验 PG、OSS、FTP 和 delivery_node | 实际 GET 和 SHA |
| `real-e2e-test.sh` | 显式授权执行三实体真实链路 | 有界等待，BLOCKED 停止 |
| `reset-test-data.sh` | 双确认清理本地运行事实 | 保留配置和外部历史文件 |

## 开发约束

1. 新脚本和复杂逻辑写业务注释，说明副作用和停止条件。
2. 请求有连接/总超时，轮询有上限；403、对象不足、目标不一致或 BLOCKED 不得无限重试。
3. 不直接 UPDATE 状态、删除租约、伪造 SUCCEEDED/DELIVERED 或修改 artifacts。
4. 不输出 .env、Secret、密码、JWT、私钥、完整 definition 或敏感响应。
5. 真实外部写操作只走独立显式入口和预检；禁止 OSS/FTP 清目录。
6. 跨日验收证明旧 EXPIRED、新 taskId、指定 deliveryDate、次日配额和 PARTIALLY_DELIVERED→DELIVERED，deliveryId/entity/targetSeq 不变，不直接改日期或状态。
7. 修改参数/API/变量同步调用方、文档、测试和 AGENTS。

## 验证

```bash
bash -n scripts/*.sh
./scripts/mvp-test.sh
cd web && npm ci && npm run test && npm run build
```

真实顺序：启动 → bootstrap → 发布 Pipeline → preflight → real-e2e → verify-delivery。最终报告实际 deliveryId/taskId、实体数量、OSS/FTP size/SHA、最终节点、retry/rollover 和自助文件复核命令。
