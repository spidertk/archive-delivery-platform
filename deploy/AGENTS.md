# deploy 模块指南

## 职责

维护生产 Compose、镜像、PowerJob MySQL 初始化、受控配置模板和 Markdown Pipeline 注册。当前只保留生产拓扑和有效模板，不维护旧 MVP Compose 或平行 example JSON。

## 基线

- `pipelines/*.md` 是 Pipeline 和黄金用例唯一 review 来源；`pipeline.production.json`、`golden-case.json` 由 `pipeline_registry.py materialize` 生成，权限 600，不提交、不进入镜像。
- `docker-compose.production.yml` 启动 PG、MySQL、PowerJob、archive-server、worker-1 和隔离 ftp-june；FTP 只开放 Compose 内网并挂载 jun 授权目录。
- `env.production.example` 只含非 Secret 定位和占位值，实际 `.env` 权限 600，由管理员离线维护。
- `V24__golden_delivery_baseline.sql` 是唯一迁移；全新库创建完整模型，已有 V23 模型幂等升级。
- server/worker 使用官方 Maven/Node 基础镜像、pom 优先复制和 `/root/.m2` BuildKit 缓存；新加坡生产使用 Maven Central 官方仓库。

## 约束

1. 一个 worker_group 只对应一台服务器的本地 archive_data；公网主子连接按对方 IP/32 放通。
2. 配置集中进 PipelineVersion，不拆节点/规则/绑定配置表，不保存 Secret 到 MD、证据或日志。
3. FTP 使用 `.partial`、SHA 和原子 rename；禁止清空历史或同名不同 SHA 覆盖。
4. 生产执行顺序：数据库与 PowerJob → server/Worker/FTP → bootstrap → 发布 Pipeline → 真实预检 → 黄金交付。
5. 只有生产预检通过才提交，fake/skipped 不算通过；禁止 `down -v`、reset 或清目录。

```bash
bash -n scripts/*.sh
python3 scripts/pipeline_registry.py validate
docker compose -f deploy/docker-compose.production.yml --env-file deploy/env.production.example config --quiet
```
