# Archive Delivery 安装指南

## 1. 当前基线

当前基线为 2026-11-07 实体目标升级版、V14 单向清空重建及 V15 部分交付状态。核心链路为杭州源 OSS 直下载并保留文件、本地 repair/qualify、三个实体各一个任务、另一 OSS Bucket 上传、三个本地 FTP 账户下载核验。跨日未完成主单为 PARTIALLY_DELIVERED，指定次日继续且全部成功后 DELIVERED。

需要 macOS、Docker Desktop/Compose v2、Python 3.11 和 Node.js 24/npm。Java 17/Maven 由构建容器提供。准备源 OSS 只读凭据、交付 OSS 读写凭据和三个仅用于本地验证的 FTP 密码；Secret 不进入 Git、日志、看板或对话。

## 2. 本地配置

```bash
cp deploy/env.example deploy/.env
cp deploy/pipeline.example.json deploy/pipeline.local.json
chmod 600 deploy/.env deploy/pipeline.local.json
```

编辑 deploy/.env 的数据库、PowerJob 和 LOCAL_FTP_ENTITY_1/2/3_PASSWORD。编辑 pipeline.local.json 的 sourceDownload/targetUpload OSS 配置，以及 ftpDownloadVerify.ftpCredential 的 entity1/entity2/entity3 密码。源 prefix 至少包含三份非空 `.tar.gz`，所有 `replace-` 占位必须移除。该文件已被 Git 忽略，不得打印或提交。

## 3. 构建、升级与启动

普通启动：

```bash
./scripts/mvp-up.sh
./scripts/bootstrap-powerjob.sh
./scripts/configure-pipeline.sh
./scripts/preflight.sh
curl -f http://127.0.0.1:8080/actuator/health
```

只有明确放弃旧数据后才允许 V14 升级：

```bash
ARCHIVE_UPGRADE_AUTHORIZED=2026-11-07 ./scripts/mvp-up.sh
```

V14 删除旧业务表并重建，不回填、不保留旧 ID、不创建兼容视图。未授权连接执行 V14 会失败，防止普通启动隐式清库。迁移完成后无需继续携带该变量。V15 随后增加 PARTIALLY_DELIVERED 状态，不清空 V14 之后的交付事实。

`configure-pipeline.sh` 发布集中 nodeDefinitions/ruleSets/DAG/执行配置的不可变 PipelineVersion，登记 OSS 来源并创建三个 FTP 实体；没有来源 binding 步骤。预检实际读取源 OSS、检查目标 Bucket 并登录三个 FTP 账户，失败不得提交。

## 4. 服务与测试

- API/看板：`http://127.0.0.1:8080`
- PowerJob：`http://127.0.0.1:7700`
- PG：`jdbc:postgresql://127.0.0.1:55432/archive_delivery`，schema `archive_delivery`
- 三个 Worker 只提供执行槽；FTP 服务不向宿主机开放端口。

```bash
./scripts/mvp-test.sh
cd web && npm ci && npm run test && npm run build
bash -n scripts/*.sh
```

Testcontainers 使用独立临时 PG。真实 OSS/FTP 测试只有 real-e2e-test.sh、preflight.sh 和 verify-delivery.sh 显式入口启用；标准测试中的 skipped 不代表外部通过。完整验收见 docs/mvp-runbook.md。
