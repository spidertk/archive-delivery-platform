# scripts 模块指南

## 模块职责

`scripts` 是构建、启动、预检、entityCode 提交、暂停续跑和真实外部验收入口。脚本调用 Delivery API 和 core 领域服务，不绕过 Pipeline、状态机或安全校验。默认 `set -euo pipefail`，外部网络操作必须有超时。

## 脚本地图

| 脚本 | 职责 | 边界 |
|---|---|---|
| `mvn.sh` / `mvp-test.sh` | 容器化 Maven 和标准回归 | fake/skipped 不代表真实通过 |
| `mvp-up.sh` | 启动数据库、server、Worker 和 FTP | V14 清库需显式授权 |
| `bootstrap-powerjob.sh` | 创建通用准备、推进和截止 Job | 调度重试为 0 |
| `configure-pipeline.sh` | 发布集中 Pipeline 并创建复合唯一实体 | 本地 alibaba/jun、alibaba/jun2、local-other/jun3，不打印 Secret |
| `preflight.sh` | 检查源/目标 OSS、FTP 和数据库 | 失败非零退出 |
| `submit-delivery.sh` | `ossUri pipelineCode deliveryDate cutoffAt deliveryBatch` 三实体提交 | 传 vendorCode+entityCode，不传 ID 或 idempotencyKey |
| `resume-delivery.sh` | 按 deliveryId/fromNode/taskIds/reason 续跑 | 前置不足 409 |
| `real-e2e-test.sh` | 两阶段 V13/V14–V17 迁移、alibaba/jun+jun2、local-other/jun3 缓存复用 | 覆盖 cutoffAt、暂停再提交、retry、rollover、cacheHit |
| `verify-delivery.sh` | 核验 PG、OSS、FTP、mapping、source artifact、metadataIds 和节点 | 实际 GET 和 SHA |
| `reset-test-data.sh` | 双确认清理本地运行事实 | 保留配置和外部历史文件 |

## 开发约束

1. 请求有连接/总超时，轮询有上限；403、对象不足、目标不一致或 BLOCKED 不得无限重试。
2. 不直接 UPDATE 状态、删除租约、伪造 SUCCEEDED/DELIVERED 或修改 artifacts/source_data.artifact。
3. 不输出 .env、Secret、密码、JWT、私钥、完整 definition 或敏感响应。
4. 真实外部写操作只走独立显式入口和预检；禁止 OSS/FTP 清目录。
5. 跨日通过 TASK_EXPIRED/TASK_REGENERATED 的 oldTaskId/newTaskId 核验，不再查询 superseded_by_task_id。
6. 修改参数/API/变量同步调用方、文档、测试和 AGENTS；不新增跳过验证的环境开关。

## 验证

```bash
bash -n scripts/*.sh
./scripts/mvp-test.sh
cd web && npm ci && npm run test && npm run build
```

真实顺序：启动 → bootstrap → 发布 Pipeline → preflight → real-e2e → verify-delivery。最终报告实际 deliveryId/taskId、三来源清单、合并包 size/SHA、sourceData.artifact 一致性、包名唯一性、OSS/FTP 结果、暂停续交、retry/rollover 和自助复核命令。模型/流程调整后必须执行本地或生产真实入口，skipped 或未执行阻断完成结论。
