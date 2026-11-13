# scripts 模块指南

## 有效入口

| 脚本 | 职责与边界 |
| --- | --- |
| `local-pipeline.sh` | 默认本地逻辑门禁；不访问真实 OSS/FTP，不清理数据 |
| `production-registry.sh` | 按 caseId/all 执行生产黄金用例；任一失败即整体失败 |
| `production-pipeline.sh` | 单 case 的生产 Compose、V24、配置、预检、提交和证据 |
| `pipeline_registry.py` | 读取、校验和生成 Markdown Pipeline/黄金用例；MD 为唯一 review 来源 |
| `configure-pipeline.sh` / `configure-production-pipeline.sh` | 发布不可变 PipelineVersion 并登记来源/实体；不打印 Secret |
| `bootstrap-powerjob.sh` | 初始化三个通用调度；生产首次登录后轮换管理员密码 |
| `provision-production-config.py` | 补齐非 Secret 定位及缺失的随机服务密码；OSS 凭据由管理员配置 |
| `maven-test.sh` | 容器化 Maven 标准测试 |
| `resume-delivery.sh` | 按 deliveryId/fromNode/taskIds/reason 续跑；前置不足 409 |

## 约束

1. 请求必须有连接/总超时，轮询有上限；403、对象不足、目标不一致或 BLOCKED 不得无限重试。
2. 不直接 UPDATE 状态、删除租约、伪造 SUCCEEDED/DELIVERED 或修改 artifacts/source_data.artifact。
3. 不输出 `.env`、Secret、密码、JWT、私钥、完整 definition 或敏感响应。
4. 真实外部写只走生产入口和预检；禁止清目录、清库、跳过验证或新增 fake 成功分支。
5. 生产只同步已 review 的提交，证据保留实际 ID、迁移版本、外部文件和复核命令。

```bash
bash -n scripts/*.sh
python3 scripts/pipeline_registry.py validate
./scripts/local-pipeline.sh
```
