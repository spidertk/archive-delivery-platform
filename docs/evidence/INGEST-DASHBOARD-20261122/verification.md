# jun-2 V29 两表看板验收

采集 2026-11-22T12:03:16.055381+00:00；发布 API 与只读网关，迁移 V29 成功。

## 当前结果

- 永久监控表仅 `ingest_monitor_record`、`ingest_monitor_notification`；旧 sample 重命名、旧 object 合入后直接删除，无旧表、备份表或兼容视图。来源状态继续复用 ingest_source。
- 对象快照与历史增量由 record_type、CHECK、部分索引隔离，已有采样 ID、清单、通知正文和真实 ACK 保留；未修改交付事实或不可变 Pipeline。
- 公网入口：[http://8.222.228.188/monitor](http://8.222.228.188/monitor)。独立页面包含当前存量、监控基线后累计/今日进数、真实目标桶与累计/今日交付、小时趋势及实际变化目录。
- 网关页面/两个资源/监控/趋势/变化 GET 从外部真实返回 200；POST 监控返回 403，管理及交付路径返回 404；完整管理 API 仍为 127.0.0.1:8080。
- 存储预估采用官方新加坡 Standard LRS US$0.0170/计费 GB/月；计费 GB=1024³ 字节，界面容量仍以十进制 GB/TB 展示。当前监控范围预估月存储费 **US$400.38**。没有计算下载费用，也未要求用户填写单价。

| 渠道 | 当前监控存量 | 今日进数 | 累计交付 | 预估月存储费 USD |
| --- | ---: | ---: | ---: | ---: |
| internal | 881.62 GB | 0.00 GB | 6.00 GB | 13.96 |
| Jayden-incoming-2.0 | 6.14 TB | 0.00 GB | 0.00 GB | 97.17 |
| jayden-incoming | 10.49 TB | 0.00 GB | 0.00 GB | 166.07 |
| incoming | 7.78 TB | 0.00 GB | 0.00 GB | 123.19 |

internal 的历史成功上传为 89 个唯一目标对象，共 6,003,554,194 bytes，目标桶 sg-storage-20261013-6vmlk；其余渠道暂无该系统的成功交付节点。当前本日没有新增数据或上传，不伪造进数，变化目录为空。incoming 仍扫描原 temp 子范围，internal 仍为原两个范围。

## 群卡实际确认

通知 ID=13，确认时间 2026-11-22T20:03:13.694580+08:00，失败重试 0 次。原生卡片点击自己的 IP 页面，不跳阿里云控制台。

```text
archive-server-1  | 2026-11-22 12:03:13.696  INFO 1 --- [   scheduling-1] c.p.a.s.s.i.IngestMonitorService         : Ingest card acknowledged notificationId=13 httpStatus=200 businessCode=0
```

## 验证边界

- 完整本地门禁通过：Python 27、core 28、server 25、Worker 本地黄金 2、前端 2 及类型检查/构建；手机修复后单独再次通过前端 3（含独立入口启动）及类型检查/构建；Shell、Compose、Markdown 注册检查通过。三个真实外部交付用例在本地 skipped，不作为生产交付通过。
- 真实临时 PostgreSQL 覆盖 V27→V28→V29 迁移、对象和样本形状、ACK 保留、目录/范围去重、30 分钟/小时/跨日/回滚、基线排除、今日重置、成功目标重试去重。费用测试覆盖官方价格、二进制计费 GB 和同桶合并。
- 本次线上只执行真实 OSS LIST 与已授权群通知；没有 OSS/FTP 文件写入或清理，没有伪造 ACTIVE/HOURLY 或节点结果。89 条任务及原提交/Pipeline SHA 与预检相同。
- 浏览器视觉预览未完成：in-app browser 建连超时，Chrome 返回 ERR_BLOCKED_BY_CLIENT；未绕过客户端限制。外部 HTTP 与静态资源、前端组件及构建均通过，详见 external-http.json。
- 预估为当前已监控存量持续整月的参考费用，不是实际账单；未扣免费额度、套餐或折扣，假设新加坡标准 LRS。未扫描的目标桶容量不以累计交付量替代。
- 私有回退材料保存在服务器 deploy/backups/INGEST-DASHBOARD-20261122/（权限 700，文件 600），未在业务库保留备份表。

## 复核

```bash
curl -fsS http://8.222.228.188/monitor
curl -fsS http://8.222.228.188/api/v1/ingest-sources/monitor
docker compose -f deploy/docker-compose.production.yml --env-file deploy/.env exec -T postgres psql -U archive -d archive_delivery -c "SELECT table_name,table_type FROM information_schema.tables WHERE table_schema='archive_delivery' AND table_name LIKE 'ingest_monitor_%' ORDER BY table_name"
```

## 证据

- [production.json](production.json)：真实模型、清单/样本汇总、指标、原生卡发送体和 ACK、历史确认、业务不变量。
- [preflight.json](preflight.json)：发布前状态。
- [external-http.json](external-http.json)：公网只读页面及路由边界检查。
- [pricing.json](pricing.json)：官方价格来源、地域/类型/单位、核对日期和抓取文件 SHA。
- [local-checks.json](local-checks.json)：本地门禁及 skipped 边界。
- [source-manifest.json](source-manifest.json)：发布源文件 SHA-256、镜像及断言结果，不含受控配置或 Secret。


## 手机白屏后续修复

用户手机已请求到 HTML，但超过 1 MB 的完整管理台脚本多次未完整接收，且没有发起监控数据查询。已拆出 monitor.html/monitor-main.ts 入口，只加载 Vue 和原生只读页面；管理台及完整组件库仍留在自己的入口。页面自带加载/资源失败提示，网关开启 JS/CSS/JSON gzip、页面 no-store，访问日志不保存企业微信附加查询参数。

最终公网手机请求方式验证：三个脚本/样式资源合计传输 **40,129 bytes**，gzip 确认开启，解压后资源完整，页面与四渠道数据接口成功。新增独立入口启动测试与原前端测试均通过（共 3），类型检查及构建通过。未再次向群发送重复消息，原卡片 URL 不变。详见 [mobile-fix.json](mobile-fix.json)。
