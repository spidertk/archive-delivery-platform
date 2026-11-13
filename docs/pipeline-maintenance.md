# AI 维护、Pipeline Review 与 CI 上线指南

## 1. 唯一配置来源

`deploy/pipelines/*.md` 是 Pipeline 和黄金用例的唯一人工 review 来源。每个文件必须同时包含：

1. `json pipeline-definition`：不可变 PipelineVersion 定义。
2. `json golden-case`：caseId、提交 targets、预期任务数、节点类型和生产外部证据。
3. 人工 Review 检查项。

不要直接编辑 `deploy/pipeline.production.json` 作为长期配置。它由 `scripts/pipeline_registry.py materialize <caseId>` 生成，真实 Secret 只在发布时从服务器受控 `.env` 注入。`deploy/golden-case.json` 同样是生成物，均不得提交。

当前注册 case：`archive-delivery-jun`。它承接已完成的 deliveryId=2 真实验证，但注册机制本身是否通过后续真实 case，必须以新的交付证据为准。

## 2. 本地验证：无真实外部文件

```bash
./scripts/local-pipeline.sh
```

本地门禁执行：

- Markdown 注册和黄金契约检查。
- Python repair 单元测试、Shell/JSON/Compose 静态检查。
- Maven 单元、Testcontainers、状态机和 `GoldenPipelineDefinitionTest`。
- 前端测试和生产构建。

`GoldenPipelineDefinitionTest` 从 Spring 容器读取真实 NodeHandler 注册表，确保每个节点类型有生产实现；随后用不访问 OSS/FTP 的替身执行同一个 `PipelineEngine` 和 DAG。它只证明代码逻辑、处理器注册和流程分派，**不是生产交付证据**。

本地入口不下载真实文件，不清理数据库或外部历史；任何真实生产写入只通过 `production-registry.sh` 和预检执行。

## 3. 生产黄金用例

```bash
# 一个 case
./scripts/production-registry.sh archive-delivery-jun

# 注册表中的全部 case
./scripts/production-registry.sh all
```

每个 case 独立执行：生成定义、发布 PipelineVersion、真实预检、提交、等待 DELIVERED、独立 OSS/FTP 复核和证据保存。任何一个 case 失败，注册级验证即失败，不能用其他 case 成功掩盖。

黄金用例至少包含：

- `SOURCE_GET`：独立读取入选源并核对 size/SHA。
- `TARGET_GET`：独立读取交付 OSS 并核对 size/SHA。
- `FTP_GET`：重新下载 FTP 文件并核对 size/SHA。
- `SIZE_SHA`：repair、OSS、FTP 和数据库 locator 一致。
- `NO_PARTIAL`：无 `.partial` 残留。

## 4. 新增或修改 Pipeline 的必做步骤

1. 实现或修改 NodeHandler，并同步 Repository、服务、API、前端、测试和模块 AGENTS。
2. 新增 `deploy/pipelines/<caseId>.md`，或在现有 MD 中修改定义和黄金用例。
3. 保证 caseId 全局唯一，targets 的 vendorCode/entityCode 唯一，expected.taskCount 等于 targets 合计，expected.nodeTypes 与 DAG 一致。
4. 为实体准备 workerGroup、可访问的 FTP endpoint 和受控凭据；新主机或挂载拓扑必须同时更新生产 Compose。
5. 执行 `./scripts/local-pipeline.sh`，不得增加跳过验证的开关。
6. 在 PR 中同时审查代码、MD、迁移、测试和文档，不由 AI 直接补写“生产通过”。
7. 合并后由生产 environment 审批，CI 同步指定 Git 提交并执行真实 case。
8. 核对实际 deliveryId/taskId、PipelineVersion、迁移版本、外部 URI、size/SHA 和证据，再关闭 review。

缺少 NodeHandler、黄金用例、外部检查或生产证据时，流程必须在本地或审批阶段停止。

## 5. CI 工作流

`.github/workflows/archive-delivery.yml` 分为两个 job：

- `local-validation`：PR、main 和人工触发均运行，不访问生产 Secret。
- `production-golden`：仅 main 或人工触发，且必须经过 GitHub `production` environment 审批。

需要配置：

- `PROD_HOST`：生产 SSH 主机。
- `PROD_USER`：生产 SSH 用户。
- `PROD_SSH_PRIVATE_KEY`：仅用于 CI 部署的受控私钥。

生产服务器 `.env` 和 OSS/FTP 凭据由管理员离线维护，不进入 GitHub 日志。CI 使用 `git archive HEAD`，因此部署的是已 review 的提交，不是开发者工作区或 AI 临时文件。

`workflow_dispatch.inputs.case` 可填单个 caseId 或 `all`。生产 evidence 会上传为 `production-golden-evidence` artifact，保留 30 天。

## 6. AI 维护约束

- 修改逻辑时必须同步 MD 定义、处理器、测试、部署和文档，禁止平行 JSON 配置漂移。
- 不把本地替身、Testcontainers、编译成功、PowerJob 绿色实例或 API ACCEPTED 写成生产通过。
- 不新增跳过预检、跳过生产验证或伪造状态的变量和分支。
- 不在 MD、CI、证据或日志中写入 AK/SK、密码、私钥或完整生产 definition。
- 真实外部写入只能来自明确的生产入口和预检；失败先查实际原因，不通过改库掩盖。
- 每个新 Pipeline 都必须可被发现、可被本地验证、可被生产执行、可独立复核。

## 7. 回退

回退使用已验证的 Git 提交重新部署，不直接修改数据库状态或删除外部历史。数据库先备份，已发布 PipelineVersion 不原地修改。需要新配置时发布新版本；旧 delivery 继续绑定原版本。
