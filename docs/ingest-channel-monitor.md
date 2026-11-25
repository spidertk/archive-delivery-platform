# 渠道看板与两表监控

当前模型为 V29，监控业务集中在 `delivery-server/service/ingestmonitor`，不改变交付 DAG 和 V25 血缘。

## 数据模型

最终净新增 **2 张监控表**，复用已有 `ingest_source`；没有渠道关系表、完整目录树或兼容视图。

| 表 | 内容与约束 |
| --- | --- |
| `ingest_monitor_record` | 原 sample 重命名并合入 object；`record_type=OBJECT` 是最后成功的对象元数据，`SAMPLE` 是完整扫描总量与实际变化目录。两种形状有 CHECK，当前对象由 `(source_id, object_key)` 部分唯一索引约束 |
| `ingest_monitor_notification` | 聚合群卡、来源 ID 数组、日报日期、SAMPLE 游标、发送确认和退避重试 |
| `ingest_source`（已有） | 原 source_id/oss_uri 与自动 channel_code；monitor_* 保存范围总量、观察状态、连续进数和报告窗口 |

V29 在一个迁移事务中保留既有采样 ID，将当前对象迁入 record 后直接 DROP `ingest_monitor_object`；`ingest_monitor_sample` 通过重命名消失。V28 已删除的独立状态、全目录缓存、日报全局表、通知渠道关系表不会恢复。数据库中不保留旧表、备份表或兼容视图。生产回退材料只放在受控私有备份目录。

主要字段：

- record：`record_id`、`record_type`、`source_id`、`size_bytes`。OBJECT 使用 `object_key/etag/modified_at`；SAMPLE 使用 `observed_at/directory/object_count/added_*/changed_*/removed_*/is_baseline`。所有增量、趋势和游标查询显式限制 SAMPLE，快照比较/更新显式限制 OBJECT。
- notification：`notification_id`、唯一 `report_key`、`event_type`、`payload`、`source_ids bigint[]`、`report_day`、`record_watermark`、`created_at/sent_at/cancelled_at/next_attempt_at`、`attempts`。
- 状态字段使用 `monitor_report_record_id`；通知使用 `record_watermark`，两者都只推进到 SAMPLE 的最大记录 ID。

## 渠道与统计口径

渠道身份为 **桶 + 桶下第一级目录**，保留大小写。同桶同一级目录下的多个登记范围合并，父范围覆盖的子范围不重复扫描或累计；扫描不扩大到整桶或整个一级目录。

| 已登记范围 | 渠道 |
| --- | --- |
| sg-oss-external-channel-source / Jayden-incoming-2.0/ | Jayden-incoming-2.0 |
| sg-oss-external-channel-source / jayden-incoming/ | jayden-incoming |
| sg-oss-external-channel-source / incoming/temp-20260825-1007a693ea2acd68/ | incoming |
| sg-storage-20261013-6vmlk / internal/ 下两个原登记范围 | internal（合并） |

页面展示四种独立事实：

| 指标 | 依据 |
| --- | --- |
| 当前存量 | 最近完整成功 LIST 的文件数与字节，不冒充传输量 |
| 监控以来累计进数 | 首次基线之后，各范围总计 SAMPLE 的新增对象完整大小 + 覆盖对象当前大小，删除不冲减累计量 |
| 今日进数 | 北京时间 00:00 起的上述进数；首次存量基线不计入 |
| 累计/今日交付与目标桶 | 现有 delivery_node 的真实成功 `oss.upload` 脱敏输出，经 delivery_submit.source_id 归渠道；同目标 URI 的重复成功只计一次，按首次成功时间归日，不把回验或重试重复算成交付 |

只有实际新增/更新数据的文件所属目录进入 changes 列表；基线、无变化和只有删除的目录不显示。对象按页进入 PG 临时表，Java 不保留全桶清单；完整失败回滚，不能发布部分清单或误判删除。

## 只估算存储费

用户选择只估算 OSS 存储费。采用阿里云国际站[官方 OSS 价格](https://www.alibabacloud.com/en/product/oss/pricing?_p_lc=1)中新加坡 Standard LRS **US$0.0170 / GB / 月**，于 2026-11-22 核对 Singapore 地域表。

`预估月存储费（人民币） = 当前监控范围字节数 / 1073741824 × 0.0170 × 6.6777`

采用 2026-11-20 的[参考汇率](https://api.frankfurter.dev/v1/2026-11-20?base=USD&symbols=CNY)：1 USD=6.6777 CNY；该值为注明日期的估算快照，不标为实时或银行结算汇率。pricing 同时返回人民币单位价、美元官方原价和汇率日期/来源。页面全部桶、渠道金额以人民币显示，顶部突出总监控存储量存放一个月的预估总费用；API storageSummary 从原始容量计算总额，不对已舍入的桶金额重复求和。

页面明确显示“预估月存储费”，并列出地域、类型和官方价格链接。该值表示当前存量持续存放整月的参考费用，不是实际账单或本月已产生费用。免费额度、套餐折扣、实际存储类型差异未计入。仅估算实际监控到的来源存量；目标桶未扫描的容量不拿累计交付量替代。同桶渠道先聚合，不重复计算整桶费用。**不计算下载、请求、回验或其他费用，不要求用户填写单价或提供账单凭据。** 容量展示使用十进制 GB/TB，计费 GB 按 1024³ 字节换算。

## 页面与群卡

`/monitor` 为独立只读页面，展示桶容量与预估费用、渠道累计/今日进数、目标桶与累计/今日交付、小时趋势和实际进数目录。桌面双列、移动端单列；管理台仍有同一监控标签。公网通过独立 monitor.html/monitor-main.ts 构建入口，仅加载 Vue 与原生只读组件，不加载完整管理台和组件库；HTML 自带加载/资源失败提示，网关启用 JS/CSS/JSON gzip、页面 no-store，避免手机首屏大脚本下载不完整时白屏。访问日志不保存企业微信附加的确认查询参数。

群内使用原生 `template_card`：当前存量或本轮进数为主数字，最多四个渠道、三条实际进数目录，附全部监控存量的人民币整月预估总费和参考汇率；即使本轮只通知进数渠道，总费也包含静默渠道。点击卡片进入自己的受控 IP `/monitor` 页面，不跳转阿里云登录。更多细节在页面展开。

公网由 `monitor-web` 和 `deploy/monitor.nginx.conf` 提供，只放行 `/monitor`、静态资源和限定监控 GET；来源注册、交付写操作、实体/Pipeline 与管理路径均不代理。完整 API 8080 仍绑定回环。卡片 URL 不含认证信息、查询参数或 Secret。

调度保持：启动 30 秒后执行，每轮完成再等 60 秒；同库多 API 以 PG 会话全局锁互斥，Worker 不运行监控。北京时间每日首次全部完整成功扫描合并日报；连续成功进数满 30 分钟才主动通知，之后每小时只合并未报告增量，静默尾量报告后关闭，不发空消息。失败或无进数会重置尚未达标的观察窗口。

通知和游标同事务，只有企业微信 HTTP 200 且 errcode=0 才确认成功，失败保留原消息并退避；网络超时仍可能至少一次投递。历史正文、Markdown、原生卡片原样归档不投递；V29 首次成功周期发送一份新版实际日报，之后每天仍只有一条。

## API 与配置

```text
POST /api/v1/ingest-sources                         {ossUri, ossType}（管理端）
GET  /api/v1/ingest-sources/monitor
GET  /api/v1/ingest-sources/{sourceId}/trend?hours=24
GET  /api/v1/ingest-sources/{sourceId}/changes?hours=24&limit=200
```

monitor 返回逻辑渠道、进数/目标桶、bucketCosts、storageSummary、pricing、statisticsDate；sourceId 为渠道查询入口，任意同渠道来源均查询聚合事实。时段 1..720 小时，变化目录最多 200 项；时间输出 ISO-8601 UTC，页面今日口径固定 Asia/Shanghai。

```dotenv
ARCHIVE_INGEST_MONITOR_ENABLED=true
ARCHIVE_INGEST_MONITOR_POLL_INTERVAL_MS=60000
ARCHIVE_INGEST_MONITOR_CONTINUOUS_PERIOD=PT30M
ARCHIVE_INGEST_MONITOR_REPORT_INTERVAL=PT1H
ARCHIVE_INGEST_MONITOR_PIPELINE_CODE=archive-batch
ARCHIVE_INGEST_MONITOR_WEBHOOK_URL=
ARCHIVE_INGEST_MONITOR_MAX_OBJECTS=1000000
ARCHIVE_INGEST_MONITOR_DASHBOARD_URL=http://<public-ip>/monitor
ARCHIVE_MONITOR_BIND_ADDRESS=0.0.0.0
ARCHIVE_MONITOR_PORT=80
```

Webhook 仅 API 受控 .env；OSS 凭据仍从已发布 Pipeline 下载节点读取，不复制到来源、看板或证据。新功能不需要账单凭据。JSONB 白名单 Schema 在 [schemas/ingest-monitor-card.schema.json](schemas/ingest-monitor-card.schema.json)，当前发送器进一步验证跳转 URL 等于受控配置。

## 验收

本地临时真实 PG 验证 V27→V28→V29 的对象、采样 ID、字段及 ACK 保留，两表形状、覆盖去重、连续/小时/跨日规则、失败回滚、基线排除、今日重置与目标上传去重；费用测试验证官方单价及计费单位。前端验证费用、目标桶和错误后保留数据。公网需核对只读页面和真实资源可达，管理/POST 请求拒绝；只用真实 LIST 与实际微信群 ACK 验收，不伪造进数或节点结果。

当前发布已完成 jun-2 的 V29 两表迁移、IP 只读访问与真实微信 ACK，详见 [V29 验收](evidence/INGEST-DASHBOARD-20261122/verification.md)。历史证据：[V28](evidence/INGEST-MINIMAL-20261122/verification.md)。

人民币换算与总月费已发布，详见[人民币存储预估验收](evidence/INGEST-CNY-20261122/verification.md)。
