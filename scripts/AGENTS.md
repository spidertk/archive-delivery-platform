# scripts 模块指南

## 有效入口

| 脚本 | 职责与边界 |
| --- | --- |
| `local-pipeline.sh` | 默认本地逻辑门禁；Maven 使用 clean test 清除已删除迁移与测试的构建残留，不访问真实 OSS/FTP，不清理业务数据 |
| `production-registry.sh` | 按 caseId/all 执行生产黄金用例；任一失败即整体失败 |
| `production-pipeline.sh` | V25、发布 archive-batch/archive-delivery、预检、创建一次批次、一次或多次独立提交和证据采集；未声明 COMPOSE_PROFILES 的旧配置默认启用 embedded-ftp，显式空值复用外部 FTP |
| `pipeline_registry.py` | 读取、校验和生成 Markdown Pipeline/黄金用例 |
| `configure-pipeline.sh` / `configure-production-pipeline.sh` | 发布不可变 PipelineVersion 并登记来源/实体；不打印 Secret |
| `bootstrap-powerjob.sh` | 初始化三个通用调度 |
| `provision-production-config.py` | 补齐非 Secret 定位及缺失随机服务密码 |
| `maven-test.sh` | 容器化 Maven 标准测试 |
| `resume-delivery.sh` | 按 deliveryId/fromNode/taskIds/reason 续跑 |
| `cleanup-test-worker-data.sh` | 仅测试机：无未完成任务时清理 Worker 中间数据，保留 PG、最终 OSS/FTP 和证据 |
| `observe-delivery.py` | 交付机有界只读采样 CPU、available/swap、磁盘、容器和 PG 任务事实；不读取或输出 Secret、不改变状态 |

## 约束

1. 请求必须有连接/总超时，轮询有上限；403、对象不足、目标不一致或 BLOCKED 不得无限重试。
2. 不直接 UPDATE 状态、删除租约、伪造 SUCCEEDED/DELIVERED 或修改 source_data.artifact。
3. 不输出 `.env`、Secret、密码、JWT、私钥、完整 definition 或敏感响应。
4. 真实外部写只走生产入口和预检；禁止清目录、清库、跳过验证或 fake 成功分支。`cleanup-test-worker-data.sh` 只允许在明确授权的测试机运行，且必须无未完成任务，不得用于正式生产清理。
5. 证据保留批次创建 ID、每次提交 ID、迁移版本、外部文件、原始文件/批次包/任务数量和复核命令。

```bash
bash -n scripts/*.sh
python3 scripts/pipeline_registry.py validate
./scripts/local-pipeline.sh
```

## 日期保留验证

local-pipeline.sh 发现并运行全部 nodes/python/test_*.py，包括 FTP 日期保留、一次回验、跨日期锁和失败清理；仍不访问真实业务 FTP/OSS。本次 jun-2 旧测试流水清理仅按 2026-11-21 明确授权执行，先备份和核对无 RUNNING，不新增自动清库入口。
