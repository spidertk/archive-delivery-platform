# jun-2 聚合卡片与播报节奏验收

生产卡片确认时间：2026-11-22 18:13:07（北京时间）。

## 当前效果

企业微信原生 template_card/text_notice，一张日报合并全部 5 个可用渠道。主数字为总 TB 容量，各渠道仅保留文件夹数、文件数、GB/TB 大小。未显示完整 OSS URI、原始字节、内部 ID、纳秒时间或未变化目录。

| 渠道 | 非空文件夹 | 文件数 | 容量 |
| --- | ---: | ---: | ---: |
| ARCHIVE_SOURCE | 0 | 40,184 | 875.11 GB |
| ARCHIVE_SOURCE | 0 | 343 | 6.51 GB |
| Jayden-incoming-2.0 | 76,425 | 361,506 | 6.14 TB |
| jayden-incoming | 455 | 429,936 | 10.49 TB |
| incoming/temp-20260825-1007a693ea2acd68 | 11 | 10,013 | 7.78 TB |

当前只有一条 DAILY（notificationId=11），实际发送 attempts=0，HTTP 200 且 businessCode=0；应用真实 ACK 日志和 payload 均在 production.json。所有渠道待发数为 0。目录数与当前目录树逐项核对，文件数与容量与对象清单逐项核对。

## 策略与测试

北京时间每天首次全部渠道完整成功扫描发一次日报。每轮成功扫描持续有新增或覆盖至少 30 分钟才主动播报；无进数或观察失败重新计时。主动通知后每小时只汇总未报告增量及渠道累计，静默尾量报告一次后关闭；没有增量不发送空卡片。同周期多个渠道合并，变化目录最多六项并按渠道轮询取样。
完整本地门禁通过：Python 27 项、前端 2 项及构建、core 28 项、Worker 本地 2 项，及 Markdown/Shell/Compose 检查；最终 server 18 项通过。3 项真实 OSS/FTP 交付测试按本地模式跳过，未作为本次通知验收依据。
30 分钟、小时增量、跨日、未变目录过滤、重启去重、多渠道聚合、白名单及事务回滚用真实临时 PG 和可控时钟验证。线上没有实际连续进数，因此没有伪造 ACTIVE/HOURLY 消息充当生产证明；线上验收的是实际清单、目录树、原生日报及真实微信 ACK。

## 迁移与数据完整性

V27 已成功提交。初次发布暴露同轮 START/STOP 被统一为 HISTORY 时与旧唯一键冲突；事务整体回滚到 V26，恢复原 API 后，先删除旧来源/轮次唯一键再转换历史，补充同轮双事件及已发/待发的回归用例，重新测试和发布成功。没有 SQL 伪造 sent_at，也没有修改交付状态。
升级前通知的 notification_id、content、sent_at、attempts 与升级后 HISTORY 逐条完全一致。原有任务、提交及 Pipeline 定义 SHA 与发布前完全一致。只重建 API，未重启 Worker，未新建批次或交付，不做 OSS/FTP 写入或清理。
配置、旧 API 镜像、监控完整 schema/数据备份位于生产机 deploy/backups/INGEST-CARDS-20261122，私有目录内保留，不进入证据。

## 复核

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
curl -fsS http://127.0.0.1:8080/api/v1/ingest-sources/monitor
curl -fsS "http://127.0.0.1:8080/api/v1/ingest-sources/4/directories?limit=200"
docker compose -f deploy/docker-compose.production.yml --env-file deploy/.env exec -T postgres psql -U archive -d archive_delivery -c "SELECT notification_id,event_type,report_key,sent_at,attempts FROM archive_delivery.ingest_monitor_notification ORDER BY notification_id;"
```

JSONB 白名单 Schema：docs/schemas/ingest-monitor-card.schema.json。证据不包含 Webhook、AK/SK、密码或原始 Pipeline definition。
