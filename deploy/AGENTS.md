# deploy 模块指南

## 模块职责

`deploy` 定义本地验证拓扑、镜像、PowerJob MySQL 初始化和配置模板。本地 Compose 验证“源 OSS → 本地目录合并/repair → 交付 OSS → 本地 FTP 下载验证”；ECS 必须重新定义网络、存储、Secret、副本和端口。

业务 PG 由 V14 在显式授权环境清空重建，V15 增加 PARTIALLY_DELIVERED，V16 形成 delivery_mapping 和任务产物对，V17 形成 entityCode 提交/repair、source_data 单原始 artifact、时间戳和 delivery 内包名唯一约束，V18 形成与 artifacts 同下标的逐来源 metadata_ids 二维数组，V19 将 deliveryArtifact 精简为不含 newFileName/file_mapping 的五字段对象，V20 删除 deliveryArtifact 并将 metadata_ids 改为同顺序 data_id 数组，V21 删除 selection/ruleSets 和重复 rule_version，V22 将三个 Python 节点的 scriptPath 纳入 PipelineVersion，V23 将路径统一为固定挂载目录内相对路径；不提供老数据兼容或平行实现。

## 运行约束

1. 本地 PG、PowerJob、API 和 FTP 管理端口默认只绑定回环地址；公网主子连接必须使用安全组限制对方 IP/32。
2. 同机三 Worker 是同一 worker_group 执行槽；一个组只对应一台服务器的本地 archive_data。
3. 启动顺序：数据库 → PowerJob → server/Worker/FTP → bootstrap → 发布 Pipeline → 预检。
4. 配置集中进 PipelineVersion；deploy 只注入受控值，不拆节点/规则/绑定配置表。
5. FTP 使用 .partial、SHA 和原子 rename；禁止清空历史或同名不同 SHA 覆盖。
6. 镜像构建跳过测试不能替代交付前标准回归；server/worker 使用 pom 优先复制和 `/root/.m2` BuildKit 缓存，避免源码或脚本变化重复下载依赖。新加坡生产实测 Maven Central 官方链路优于原阿里云镜像，`maven-settings.xml` 使用官方仓库。
7. 只有 `ARCHIVE_UPGRADE_AUTHORIZED=2026-11-07` 的显式升级命令允许 V14 清库。
8. 本地主要目标为 alibaba/jun、alibaba/jun2，缓存复用目标为 local-other/jun3；实体按 vendorCode+entityCode 联合唯一，cutoffAt 由 Pipeline 配置推导。

## 配置与验证

`pipeline.production.example.json` 与 `pipeline.example.json` 默认每包来源合计和最终产物均为 8–10 MiB，不配置来源条数上限或独立 ruleSets；targetCount 决定任务槽位，扫描到全部槽位满足后停止。两者 DAG 均为六节点，merge/repair/FTP 的 scriptPath 只写 `archive_*.py` 等相对路径；各 Compose 均将 `../nodes/python` 整目录只读挂载到 `/app/nodes/python`，新增脚本不需要调整挂载。merge 只输出请求目录，repair 接收 entityCode、写入协议 providerCode 并只最终封包一次；生产 repair 显式 `gzipCompressLevel=5`，不调整 8–10 MiB 验收边界。生产 OSS 使用 `{accountName}/{deliveryDate}-{deliveryBatch}-{accountName}` 分隔 june/june-2 等业务账户目录，FTP 使用 `{deliveryDate}/{deliveryBatch}`；包名在 delivery_id 内全局连续。

`docker-compose.production.yml` 在交付机启动 PG、PowerJob、archive-server、worker-1 和隔离 ftp-june，FTP 只开放 Compose 内网并挂载 `/srv/maas-sftp/maasData/archive/june`。`env.production.example` 只保存真实非 Secret 定位和占位凭据，实际 `.env` 与 `pipeline.production.json` 权限为 600 且不提交，并由 `.dockerignore` 排除在构建上下文之外；证据和服务器密码文档同样不得进入镜像。MySQL/PG/PowerJob 不再使用开发固定密码。

```bash
bash -n scripts/*.sh nodes/shell/*.sh
docker compose -f deploy/docker-compose.yml --env-file deploy/.env config
./scripts/mvp-up.sh
./scripts/bootstrap-powerjob.sh
./scripts/preflight.sh
```

验证后检查 git status，不提交本地配置、日志、临时包或 Secret。真实核心验收需从指定 sg-storage 下载足够来源形成两个 8–10 MiB 包，核对交付 OSS/FTP size/SHA、保留文件、暂停续交、retry 和跨日证据。
