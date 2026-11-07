# deploy 模块指南

## 模块职责

`deploy` 定义实体目标升级版本地验证拓扑、镜像、PowerJob MySQL 初始化和配置模板。本地 Compose 验证“源 OSS → 本地 repair → 交付 OSS → 本地 FTP 下载验证”；ECS 必须重新定义网络、存储、Secret、副本和端口。

业务 PG 由 V14 在显式授权环境清空重建，V15 增加 PARTIALLY_DELIVERED 状态；本目录不提供老数据兼容或回填。

## 文件职责

| 对象 | 职责 |
|---|---|
| `docker-compose.yml` | PostgreSQL、MySQL、PowerJob、server、三 Worker 和 ftp-entity-1/2/3 |
| `Dockerfile.server` | 前端与 server 构建、非 root 运行 |
| `Dockerfile.worker` | Worker、Python 和 FTP 工具，不含跳板配置 |
| `Dockerfile.ftp` / `ftp_server.py` | entity1/entity2/entity3 隔离账户，禁止删除和匿名访问 |
| `env.example` | 部署变量模板，不含真实 Secret |
| `pipeline.example.json` | 集中 nodeDefinitions/ruleSets/DAG/selection/assignment 的脱敏定义 |
| 本地 Pipeline 配置 | 受控维护节点 Secret，禁止提交、打印或 API 返回 |
| `.env` | 本地运行配置，禁止读取后输出、复制到对话或提交 |

## 运行约束

1. PG、PowerJob、API 和 FTP 管理端口只绑定回环地址；Worker 内部端口不发布。
2. 三 Worker 只是执行槽；交付去向由 delivery_task.delivery_entity_id 和 Pipeline 决定。
3. 启动顺序：数据库 → PowerJob → server/Worker/FTP → bootstrap → 发布 Pipeline → 预检。
4. 配置集中进 PipelineVersion；deploy 只注入受控值，不拆节点/规则/绑定配置表。
5. FTP 使用 `.partial`、SHA 和原子 rename；禁止清空历史或同名不同 SHA 覆盖。
6. 镜像构建跳过测试不能替代交付前标准回归。
7. 只有 `ARCHIVE_UPGRADE_AUTHORIZED=2026-11-07` 的显式升级命令允许 V14 清库。
8. ECS 不照搬 loopback、宿主路径或开发默认值。

## 验证

```bash
bash -n scripts/*.sh nodes/shell/*.sh
docker compose -f deploy/docker-compose.yml --env-file deploy/.env config
./scripts/mvp-up.sh
./scripts/bootstrap-powerjob.sh
./scripts/preflight.sh
curl -f http://127.0.0.1:8080/actuator/health
```

验证后检查 git status，不提交本地配置、日志、临时包或 Secret。真实通过还需三份源/交付 OSS 与 FTP 外部 size/SHA 和保留文件证据。
