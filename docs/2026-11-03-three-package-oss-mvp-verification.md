# 三包 OSS 最小 MVP 开发、部署与验证报告

> 验证时间：2026-11-03 20:03 CST
> 适用版本：Flyway V12、OSS-only PipelineVersion 4+
> 结论：代码改造、真实 V10→V12 本地迁移、部署和本地最小 MVP 已通过；真实外部 OSS 验收因 `InvalidAccessKeyId` 未能开始，不能记为生产通过。

## 1. 本次交付范围

- 核心模型切换为 `delivery_task/delivery_node_task/task_id`；旧 `delivery_run/package_node_run` 不再被运行代码读取。
- `source_request` 合并请求映射，`request_id_sequence/request_id_allocation` 负责 PG 事务编号。
- 输出包使用 `artifacts` 和 `delivery_metadata`，OSS URI 位于 `artifacts.locations.transfer`，不再有 HZ/SG/FTP 专用路径列。
- `PackageExecutionService` 统一 lease/fence、节点提交和任务投影；`ProductStatusService` 按 Pipeline 阶段动态派生产品状态。
- 源 OSS 按 key 选取前三份非空压缩包，三线 1/1/1，每包执行 `download/repair/qualify/group/transfer/receipt`。
- 生产交付只使用 `oss.upload → delivery.receipt`，不再启用 CRR、SSH、FTP；Worker 镜像和 Compose 不再包含 OpenSSH 与私钥挂载。
- 看板、retry/resume、rollover、真实验收脚本和研发手册均使用新模型。

未扩张实现完整资产管理 API、告警生命周期、通知、RBAC 或可视化编排；这些不属于本次最小 MVP。

## 2. 标准测试证据

| 命令/检查 | 结果 |
|---|---|
| `./scripts/mvp-test.sh` | BUILD SUCCESS；core 35、server 7、worker 4，共 46 项，0 失败/错误；标准入口 4 项条件测试 skipped |
| `python3 -m unittest nodes/python/test_archive_repair.py -v` | 6/6 通过，包含真实样本、确定性 SHA、缺协议文件和 Manifest 安全边界 |
| `cd web && npm ci && npm run test && npm run build` | 1/1 组件测试通过；TypeScript 与 Vite 生产构建通过 |
| `bash -n scripts/*.sh` | 通过 |
| `docker compose ... config --quiet` | 通过 |
| `git diff --check` | 通过，无空白错误 |

标准入口 skipped 的边界：`RealProductionEndToEndTest` 代表真实 OSS 验收，不计为通过；`LocalPostgresIntegrationTest` 在真实迁移重置时单独执行成功；`RetryEndToEndTest` 随后在本地 Compose PG 单独执行成功；`ProductionPreflightTest` 单独执行但因外部密钥失败，详见第 5 节。

前端构建另有既存的大 chunk 提示；`npm ci` 报告依赖漏洞和当前 Node 小版本 engine 提示。这些未导致测试/构建失败，本次未擅自升级依赖。

## 3. 真实持久库迁移与清数

对本机 Compose 中原 V10 持久库执行了正式迁移，而非只验证空库：

- 发现并修复旧排他约束阻挡双包血缘回填的问题。
- 为 2 个旧本地调试无 run 源包生成 `TASK-LEGACY-*` 快照，回填对应输出、生产组和 19 条节点历史。
- 最终 V11/V12 两次迁移成功，旧 ID、请求、产物和节点事实保留并转换。
- `reset-test-data.sh` 双确认后先停 server/Worker，再清空运行表；当时任务/源包/输出包/节点历史合计 0 行，Flyway 与配置保留。
- 随后本地故障重试测试重新生成一组干净的三包数据。

该脚本只能操作本地 Compose 的 `archive_delivery`，不是 ECS/生产升级工具，也不能代替迁移对账。

## 4. 本地最小 MVP 与部署证据

`./scripts/mvp-up.sh` 完成 server 和三个 Worker 镜像构建并启动七服务；`./scripts/bootstrap-powerjob.sh` 注册/更新三个调度任务；`GET /actuator/health` 返回 `{"status":"UP"}`，七服务均为 running。

本地故障重试后的 PG 事实：

```text
task=1:DELIVERED
groups=1:1/1:SEALED,2:1/1:SEALED,3:1/1:SEALED
outputs=3,delivered=3
requests=12,unique=12
download/repair/qualify/group/receipt/transfer 各有 3 个成功节点
transfer 另有 1 个注入 FAILED 节点
```

故障注入只影响第一个 transfer：包进入 RETRY_WAIT，第二次 attempt 成功；repair 没有重跑，请求号和分组事实未改变，最终 3/3 DELIVERED。该结果证明本地 PG/Python/Pipeline/重试链正确，但不冒充真实 OSS 证据。

## 5. 真实 OSS 预检结果与恢复步骤

已通过 API 发布 OSS-only `PipelineVersion 7`，发布过程未返回 Secret。执行 `./scripts/preflight.sh` 时，Docker、Compose、PG 和 active binding 检查通过，源 OSS List 返回：

```text
InvalidAccessKeyId: The OSS Access Key Id you provided does not exist in our records.
```

因此：

- 未执行 `real-e2e-test.sh` 的任务提交与交付上传。
- 没有真实 taskId、外部 size/SHA 或 3/3 OSS DELIVERED 证据。
- `verify-delivery.sh` 不得被记为通过；当前 active Pipeline 需要有效凭据后才能用于外部验收。

恢复步骤：

1. 由凭据负责人在本地 `deploy/pipeline-v4.local.json` 的 `download` 和 `transfer` 节点写入有效 AK/SK；不要把 Secret 输出到对话或提交 Git。
2. 通过草稿发布新 PipelineVersion；已发布的 version 7 不原地修改。
3. 先运行 `./scripts/preflight.sh`，确认三份源对象可读和交付 OSS 可写。
4. 设置本地清数双确认后运行 `./scripts/real-e2e-test.sh`：

   ```bash
   export ARCHIVE_ALLOW_TEST_DATA_RESET=true
   export ARCHIVE_CONFIRM_LOCAL_TEST_RESET=archive_delivery
   ./scripts/real-e2e-test.sh
   ```

5. 将真实 taskId、`verify-delivery.sh` 输出、三线各一包、六节点和 OSS size/SHA 补充到本报告；预检失败不得跳过。

## 6. 研发逐节点验证入口

- 安装与配置：`docs/install.md`
- 部署、迁移、重置和回滚：`docs/deploy.md`
- 发现、download、repair、qualify、group、transfer、receipt 的逐项 SQL/API 验证：`docs/mvp-runbook.md`
- 本地配置模板：`deploy/pipeline-v4.example.json`
- 受控清数入口：`scripts/reset-test-data.sh`

任何验收不得以 PowerJob 绿色 instance、LocalDeliveryNode、单元 fake 或 skipped 测试替代外部 OSS 事实。
