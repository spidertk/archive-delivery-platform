# jun-2 渠道监控生产验收

采集时间：2026-11-22 17:26:22（北京时间）。运行模式为真实只读 OSS LIST 与企业微信群播报；这是来源监控验收，不是新增批次交付验收。

## 实现与发布

核心业务集中在 delivery-server/service/ingestmonitor；API 与前端只提供薄查询展示。对象每页写 PG 事务临时表，完整扫描后在 PG 比较新增、覆盖和删除，不在 API heap 保存整桶清单。
现有 V25 文件 SHA 未改，Flyway V26 成功增量迁移并登记三个来源。发布前确认无 RUNNING 任务和在途批次准备，备份原 API 镜像、来源表及受控配置，备份位于生产机 deploy/backups/INGEST-MONITOR-20261122，私有 Secret 不进入证据。
每轮完整扫描完成后间隔 60 秒，静默阈值为 600 秒；实际周期包含全量 LIST 和 PG 统计时间。静默窗口在故障或中断恢复后保守重新计时。

## 三渠道真实结果

| sourceId | 渠道 | 文件数 | 字节数 | 静默计时起点（北京时间） | 停止汇总确认（北京时间） |
| --- | --- | ---: | ---: | --- | --- |
| 4 | Jayden-incoming-2.0 | 361,506 | 6,137,079,584,256 | 2026-11-22 17:12:49 | 2026-11-22 17:23:37 |
| 5 | jayden-incoming | 429,936 | 10,489,031,441,209 | 2026-11-22 17:13:24 | 2026-11-22 17:24:12 |
| 6 | incoming/temp-20260825-1007a693ea2acd68 | 10,013 | 7,781,090,133,857 | 2026-11-22 17:13:27 | 2026-11-22 17:24:13 |

三渠道均为 IDLE、无扫描错误、待发数为 0；已核对每个停止事件创建时间距静默起点至少 600 秒。开始与停止报告包含渠道完整 URI、轮次、数量、字节以及直属子目录新增/覆盖/移除统计；首次存量明确标为首次发现。
当前共 10 组逻辑播报、10 段，真实确认 10 段，覆盖新增三渠道及已有两个可用来源。发送实现只有 HTTP 200 且 errcode=0 后才写 sent_at；真实确认记录见 production-verification.json。重试仅调整待发时间，未手工写发送成功。
进程切换和群端拒绝期间消息保留，修复配置后真实重试成功；开始消息未因重启重复创建新轮次。

## 趋势与数据一致性

三渠道每小时 addedObjects - removedObjects 合计等于当前对象数；小时最后快照的数量和字节数与监控状态及已提交对象清单一致。首次快照后没有新增、覆盖和移除，不能把首次发现存量解释为当小时实际上传量。
完整 OSS 只读 LIST 预检见 oss-preflight.json；后续采样来自实际 SDK LIST。未做对象 GET/PUT、FTP 写入或业务目录清理。原有 89 个 SUCCEEDED 任务、全部 delivery_submit 与 PipelineVersion 的定义 SHA 保持不变。

## 服务与验证

API health 为 UP，看板根路径和全部静态资源 HTTP 200；监控与各来源 24 小时趋势接口均返回实际事实。API 容器无 OOM、restartCount=0；资源采样：archive_delivery_production-archive-server-1 0.06% 385.4MiB / 14.96GiB。
完整本地门禁通过：Python 27 项、core 28 项、Worker 本地 2 项、前端 2 项及构建、Markdown 注册、Shell/Compose 检查。最终监控模块 15 项测试通过，覆盖 V25→V26、重复迁移、完整分页、同大小覆盖/删除、接收/停止/续传、失败恢复、长扫描周期、Unicode 目录、事务回滚、跨副本互斥、微信业务错误和真实 Spring API 装配。
本地跳过的 3 个 OSS/FTP 生产交付测试没有作为本次监控通过依据。浏览器连接工具超时，页面视觉检查未执行；前端组件渲染、构建、在线页面与静态资源请求已验证。

## 复核命令

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
curl -fsS http://127.0.0.1:8080/api/v1/ingest-sources/monitor
curl -fsS "http://127.0.0.1:8080/api/v1/ingest-sources/4/trend?hours=24"
docker compose -f deploy/docker-compose.production.yml --env-file deploy/.env exec -T postgres psql -U archive -d archive_delivery -c "SELECT source_id,session_id,event_type,part_no,created_at,sent_at,attempts FROM archive_delivery.ingest_monitor_notification ORDER BY notification_id;"
```

证据不包含 webhook、AK/SK、密码或原始 Pipeline definition。

## 最新群地址切换

用户再次更换机器人地址后，先向最新群发送三个渠道的实际成功快照作为配置切换确认，真实 HTTP 200、errcode=0，再更新受控 .env 并重建 API 容器使配置生效；没有伪造新数据开始或停止事件。新地址确认见 webhook-rotation-ack.json，生产证据已在切换后重新采集。真实静默开始/停止的历史 ACK 保留，后续轮次只走最新地址。
