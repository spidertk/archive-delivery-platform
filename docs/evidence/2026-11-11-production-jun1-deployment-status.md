# 2026-11-11 生产 jun×1 部署与阻断记录

## 结论

本次已执行真实服务器部署和 V1–V23 迁移，但**未执行真实交付提交，未产生 deliveryId/taskId，不能记为生产交付通过**。

阻断原因：`8.222.180.98:/opt/archive-delivery-platform/deploy/.env` 中 SOURCE_OSS_ACCESS_KEY_ID、SOURCE_OSS_ACCESS_KEY_SECRET、TARGET_OSS_ACCESS_KEY_ID、TARGET_OSS_ACCESS_KEY_SECRET 仍为 replace-* 或掩码。Pipeline 在发布前拒绝占位配置，符合预检失败不得继续的约束。

## 已核实事实

- 交付机：`8.222.180.98`，4 vCPU、14 GiB 内存，系统盘约 252 GiB 可用。
- 已安装并启动 Docker 29.1.3、Docker Compose 2.40.3。
- 代码部署于 `/opt/archive-delivery-platform`，受控配置权限为 600。
- PostgreSQL、MySQL、PowerJob Server、archive-server、worker-1、ftp-june 使用真实 `docker-compose.production.yml` 启动。
- Flyway 整数最高成功版本经 PostgreSQL 查询确认为 23。
- Archive API 实际返回 `{"status":"UP"}`。
- PowerJob 管理员初始密码已通过 `/pwjbUser/changePassword` 轮换为随机值。
- PowerJob App `archive-delivery` 实际 ID 为 1。
- 已保存三个真实通用调度：preparation STANDALONE 15 秒、task-tick BROADCAST 5 秒、rollover STANDALONE 30 秒。
- server、worker、FTP 镜像已使用官方 Maven/Node 基础镜像实际构建成功。
- 源 OSS URL 匿名访问实际返回 403，不能无凭据读取。

## 未完成阶段

- 真实 PipelineVersion 发布：因占位 Secret 被配置校验阻断。
- 源 OSS SDK 列举/GET、目标 OSS Bucket 权限和 FTP 预检：未进入。
- alibaba/jun targetCount=1 提交：未执行。
- OSS/FTP 文件、size/SHA 和最终 DELIVERED：未验证。

## 已完成的开发验证

- Python `test_archive_repair.py`：6/6 通过。
- 完整 Maven 回归：BUILD SUCCESS，core 30 个测试通过；外部真实测试未启用，不作为交付证据。
- 前端 Vitest：1/1 通过，生产构建成功。
- RemotePathService：june/june-2 accountName 目录拆分及本地路径测试通过。
- PowerJob 首次密码轮换和重复初始化通过模拟 API 开发检查；线上真实轮换和三个调度已成功。
- 生产 Compose、Shell、JSON、`.dockerignore` 和 Secret 初始化静态检查通过。

## 变量名修正

后续确认源凭据实际使用 `OSS_SOURCE_ACCESS_KEY_ID/SECRET`。脚本已从错误的 `SOURCE_OSS_*` 统一修正为该变量名；目标仍使用 `TARGET_OSS_ACCESS_KEY_ID/SECRET`。早期阻断记录保留，续跑时以新的变量检查和真实预检结果为准。

## 解锁步骤

管理员在 exact 文件中填入四项真实 OSS AK/SK，不使用 `********` 或 replace-*，然后执行：

```bash
cd /opt/archive-delivery-platform
python3 scripts/provision-production-config.py
./scripts/production-pipeline.sh
```

第一条命令只报告凭据是否仍缺失，不打印内容。真实脚本会保留 V23 和现有 PowerJob 配置，重新发布 Pipeline，预检通过后才提交 jun×1，并将成功或失败事实保存到 `docs/evidence/<deliveryBatch>/`。
