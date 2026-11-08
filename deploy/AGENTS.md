# deploy 模块指南

## 模块职责

`deploy` 定义本地验证拓扑、镜像、PowerJob MySQL 初始化和配置模板。本地 Compose 验证“源 OSS → 本地目录合并/repair → 交付 OSS → 本地 FTP 下载验证”；ECS 必须重新定义网络、存储、Secret、副本和端口。

业务 PG 由 V14 在显式授权环境清空重建，V15 增加 PARTIALLY_DELIVERED，V16 形成 delivery_mapping 和任务产物对，V17 形成 entityCode 提交/repair、source_data 单原始 artifact、时间戳和 delivery 内包名唯一约束；不提供老数据兼容或平行实现。

## 运行约束

1. 本地 PG、PowerJob、API 和 FTP 管理端口默认只绑定回环地址；公网主子连接必须使用安全组限制对方 IP/32。
2. 同机三 Worker 是同一 worker_group 执行槽；一个组只对应一台服务器的本地 archive_data。
3. 启动顺序：数据库 → PowerJob → server/Worker/FTP → bootstrap → 发布 Pipeline → 预检。
4. 配置集中进 PipelineVersion；deploy 只注入受控值，不拆节点/规则/绑定配置表。
5. FTP 使用 .partial、SHA 和原子 rename；禁止清空历史或同名不同 SHA 覆盖。
6. 镜像构建跳过测试不能替代交付前标准回归。
7. 只有 `ARCHIVE_UPGRADE_AUTHORIZED=2026-11-07` 的显式升级命令允许 V14 清库。
8. 本地主要目标为 alibaba/jun、alibaba/jun2，缓存复用目标为 local-other/jun3；实体按 vendorCode+entityCode 联合唯一，提交显式携带 cutoffAt。

## 配置与验证

`pipeline.production.example.json` 默认 10 MiB maxPackageBytes、10 个来源包和 production-archive-v2 规则；`pipeline.example.json` 本地核心 case 为 3 个来源、每包 10 MiB、两个实体各一件。两者 DAG 均为六节点，merge 只输出请求目录，repair 接收 entityCode 并只最终封包一次，规则示例 providerCode 为 ENTITY_CODE。OSS 生产目录模板仍使用厂商 `{providerCode}`，FTP 使用 `{deliveryDate}`。

```bash
bash -n scripts/*.sh nodes/shell/*.sh
docker compose -f deploy/docker-compose.yml --env-file deploy/.env config
./scripts/mvp-up.sh
./scripts/bootstrap-powerjob.sh
./scripts/preflight.sh
```

验证后检查 git status，不提交本地配置、日志、临时包或 Secret。真实核心验收需指定 sg-storage 三份源 OSS、两个 10 MiB 上限内实体交付 OSS/FTP 的 size/SHA、保留文件、暂停续交、retry 和跨日证据。
