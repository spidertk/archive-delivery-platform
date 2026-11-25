# jun-2 渠道监控 V28 验收

采集时间：2026-11-22T11:03:22.855860+00:00。迁移 V28 成功；API 健康，未发生 OOM 或异常重启。

## 发布结果

- 监控永久表由 7 张减为 3 张：`ingest_monitor_object`、`ingest_monitor_sample`、`ingest_monitor_notification`。状态复用已有 `ingest_source`；删除目录树、状态、全局日报、通知渠道关系四表。
- 渠道由桶与第一级目录自动推导，同渠道登记范围聚合、覆盖范围去重；原 source_id/oss_uri 保留。只重建 API，未重新发布不可变 Pipeline。
- 简短 Markdown 摘要无点击 URL/阿里云登录入口，容量采用 GB/TB；只显示实际有新增/覆盖数据的目录。本次无自然进数，变化目录为空。

| 桶 | 渠道 | 来源 ID | 文件数 | 容量 |
| --- | --- | --- | ---: | ---: |
| sg-storage-20261013-6vmlk | internal | 1, 2 | 40,527 | 881.62 GB |
| sg-oss-external-channel-source | Jayden-incoming-2.0 | 4 | 361,506 | 6.14 TB |
| sg-oss-external-channel-source | jayden-incoming | 5 | 429,936 | 10.49 TB |
| sg-oss-external-channel-source | incoming | 6 | 10,013 | 7.78 TB |

`incoming` 仍只扫描原登记的 temp 子目录；`internal` 仍只扫描原来的两个范围，未扩大到整个桶。

## 实际通知

通知 ID=12，DAILY，发送确认时间 2026-11-22T19:03:19.398601+08:00，失败重试 0 次。API 实际日志：

```text
archive-server-1  | 2026-11-22 11:03:19.399  INFO 1 --- [   scheduling-1] c.p.a.s.s.i.IngestMonitorService         : Ingest card acknowledged notificationId=12 httpStatus=200 businessCode=0
```

当前实际发送正文：

```text
📦 渠道日报 · **11-22**

**internal**　40,527 文件 · 881.62 GB

**Jayden-incoming-2.0**　361,506 文件 · 6.14 TB

**jayden-incoming**　429,936 文件 · 10.49 TB

**incoming**　10,013 文件 · 7.78 TB
```

## 验证与边界

- 真实 LIST 清单与发布前一致；旧 11 条通知的 payload、sent_at、attempts 完整保留，归为只读 HISTORY。交付 89 条 SUCCEEDED、提交状态和不可变 Pipeline SHA 均与预检相同。
- 前端根路径及两个实际资源返回成功；渠道监控、小时趋势、变化目录 API 成功，未变化目录没有返回。
- 本地完整门禁通过：Python 27、core 28、server 当前 21、Worker 本地 2、前端 2 及构建；三个真实外部交付用例在本地跳过，不作为本次生产交付验收。最后补充跨桶同名格式测试，独立运行的 renderer 3 项通过。
- 30 分钟连续进数、小时未报告增量/静默尾量、跨日去重、事务回滚及七表迁移由真实临时 PG 测试覆盖。本次线上没有自然连续进数，未伪造 ACTIVE/HOURLY 或发送测试消息。
- 本次仅监控元数据 LIST 与已授权微信群通知，没有 OSS/FTP 文件写入或清理。私有回退材料位于服务器 deploy/backups/INGEST-MINIMAL-20261122/，不进入证据。

## 复核

在 jun-2 项目目录执行；只读查询，不读取受控 Secret：

```bash
docker compose -f deploy/docker-compose.production.yml --env-file deploy/.env exec -T postgres psql -U archive -d archive_delivery -c "SELECT table_name FROM information_schema.tables WHERE table_schema='archive_delivery' AND table_name LIKE 'ingest_monitor_%' ORDER BY table_name"
curl -fsS http://127.0.0.1:8080/actuator/health
curl -fsS http://127.0.0.1:8080/api/v1/ingest-sources/monitor
docker compose -f deploy/docker-compose.production.yml --env-file deploy/.env exec -T postgres psql -U archive -d archive_delivery -c "SELECT notification_id,event_type,source_ids,report_day,sent_at,attempts FROM archive_delivery.ingest_monitor_notification WHERE event_type<>'HISTORY' ORDER BY notification_id"
```

## 证据文件

- [production.json](production.json)：实际模型、源登记、清单汇总、趋势、变化目录、发送 payload/ACK、前端资源及业务不变量。
- [preflight.json](preflight.json)：发布前真实状态，用于逐项比较。
- [local-checks.json](local-checks.json)：本地检查及明确的 skipped 边界。
- [source-manifest.json](source-manifest.json)：发布源码 SHA-256 与检查结果；不包含 Secret、受控环境文件或镜像内容。
