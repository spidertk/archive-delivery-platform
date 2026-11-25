# scripts 模块指南

## 有效入口

| 脚本 | 职责与边界 |
| --- | --- |
| `local-pipeline.sh` | 默认本地逻辑门禁；Maven 使用 clean test 清除已删除迁移与测试的构建残留，不访问真实 OSS/FTP，不清理业务数据 |
| `production-registry.sh` | 按 caseId/all 执行生产黄金用例；任一失败即整体失败 |
| `production-pipeline.sh` | V25 批次基线及 V29 来源监控迁移检查、发布 archive-batch/archive-delivery、预检、创建一次批次、一次或多次独立提交和证据采集；未声明 COMPOSE_PROFILES 的旧配置默认启用 embedded-ftp，显式空值复用外部 FTP |
| `production-remaining.py` | 仅由生产入口的 PROD_DELIVER_ALL=true 调用；同批次逐轮最多 100 包续跑到无剩余包，PG 只读，每轮真实外部验收后在可用空间低于 75 GiB 时按 size/SHA 白名单回收源缓存和本提交 FTP 回验副本，保留 manifest 与最终 OSS/FTP；显式 PROD_EVICT_VERIFIED_SOURCE_CACHE=true 可忽略其他批次尚未消费的规划引用，仍保护本批次未交付来源与所有已建未完成任务引用 |
| `pipeline_registry.py` | 读取、校验和生成 Markdown Pipeline/黄金用例 |
| `configure-pipeline.sh` / `configure-production-pipeline.sh` | 发布不可变 PipelineVersion 并登记来源/实体；不打印 Secret |
| `bootstrap-powerjob.sh` | 初始化并启用三个通用调度，`concurrency=1`，`maxInstanceNum=0/instanceTimeLimit=0` 保持 PowerJob 基线语义；长准备期间的子实例容量提示不能单独作为故障证据 |
| `provision-production-config.py` | 按 jun-2 8 核 16G 补齐非 Secret 资源默认值及缺失随机服务密码；已有配置不覆盖 |
| `maven-test.sh` | 容器化 Maven 标准测试 |
| `resume-delivery.sh` | 按 deliveryId/fromNode/taskIds/reason 续跑 |
| `cleanup-test-worker-data.sh` | 仅测试机：无未完成任务时清理 Worker 中间数据，保留 PG、最终 OSS/FTP 和证据 |
| `observe-delivery.py` | 交付机有界只读采样 CPU、available/swap、磁盘、容器和 PG 任务事实；不读取或输出 Secret、不改变状态 |

`archive-batch-jun` 的生产登记同时校验 `alibaba/jun-2/group1` 与扩展 `alibaba/jun/group2` 容量映射；扩展机只执行 `deploy/docker-compose.worker.yml`，不在本机创建 PipelineVersion 或实体关系。

## 约束

已规划批次可用 `PROD_USE_PUBLISHED_PIPELINES=true` 通过同一生产入口续跑；该模式复用当前运行服务和已发布版本，只检查服务健康、V29 与真实外部预检，不重建 Compose、不重新发布 Pipeline 或修改 PowerJob。BATCH_CREATE 仍通过原参数 API 核对，已有提交继续核验，禁止重复 POST 多消费包。

真实外部验收容器将 `/tmp` 放在上限 2 GiB 的私有 tmpfs，避免最多八个 100 MiB 目标 OSS 回验包反复写根盘；保留逐字节 SOURCE_GET/TARGET_GET/FTP_GET、size/SHA 和原文件比对，日志与证据仍落受控 evidence。该内存空间仅存活于验收容器，不用作 Worker 工作目录、长期缓存或最终成品；运行前核对主机可用内存，速度必须以完整验收实测判断。

1. 请求必须有连接/总超时，轮询有上限；403、对象不足、目标不一致或 BLOCKED 不得无限重试。
2. 不直接 UPDATE 状态、删除租约、伪造 SUCCEEDED/DELIVERED 或修改 source_data.artifact。
3. 不输出 `.env`、Secret、密码、JWT、私钥、完整 definition 或敏感响应。
4. 真实外部写只走生产入口和预检；禁止清目录、清库、跳过验证或 fake 成功分支。`cleanup-test-worker-data.sh` 只允许在明确授权的测试机运行，且必须无未完成任务，不得用于正式生产清理。
5. 证据保留批次创建 ID、每次提交 ID、迁移版本、外部文件、原始文件/批次包/任务数量和复核命令。
6. 生产资源默认值为组内并发 8、下载线程 16、Worker/API 堆上限 512 MiB、每 Worker 数据库连接池上限 4；扩容已有主机必须显式更新受控 `.env` 并核对实际实例数，provision 不会覆盖旧值。资源发布不能自动创建交付单或复用历史清理授权。
7. 全量续跑必须已有用户全量交付授权，并由 production-registry.sh 经过真实预检；PROD_BATCH_WAVE_SIZE 为 1..100。任何 BLOCKED、验收缺失/失败、SHA 不符或可用空间不足 30 GiB 都停止新增提交。本机批次锁防重复启动，恢复时先核对已有提交及验收日志摘要，不用重复 POST 代替 retry/resume；回收仅作用于本次已核验文件，不清空目录、不改 PG 或最终交付文件。
8. 用户于 2026-11-25 明确授权低磁盘优先清理无用缓存保障当天作业，本次批次 8 运行显式启用 PROD_EVICT_VERIFIED_SOURCE_CACHE=true。只有本次已独立验收提交的源缓存可回收；本批次未交付包及任何 READY/RUNNING/WAITING/RETRY_WAIT/PAUSED/BLOCKED 任务引用均保护。逐文件核对 size/SHA，删除前再次查询引用，审计记录显式开关、完整白名单、实际删除和跳过项；以后缺缓存按原 URI/版本/size/SHA 回退真实 OSS，不改 PG locator。未开启此开关仍保护所有未交付规划引用。

```bash
bash -n scripts/*.sh
python3 scripts/pipeline_registry.py validate
./scripts/local-pipeline.sh
```

## 日期保留验证

local-pipeline.sh 发现并运行全部 nodes/python/test_*.py，包括 FTP 日期保留、一次回验、跨日期锁和失败清理；仍不访问真实业务 FTP/OSS。本次 jun-2 旧测试流水清理仅按 2026-11-21 明确授权执行，先备份和核对无 RUNNING，不新增自动清库入口。
