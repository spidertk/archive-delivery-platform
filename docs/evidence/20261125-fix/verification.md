# jun-2 20261125 批次交付运行记录

本次执行位于 jun-2 线上 `8.222.228.188:/opt/archive-delivery-platform`。用户授权先交付两个包，独立验收通过后全量交付并检测、修复实际错误。全量仍在运行，不能标记全部交付通过。

来源为 `oss://sg-storage-20261013-6vmlk/internal/gpt-jayden-model-effort-products-20261124-v6/products/`，交付日期 `2026-11-25`，实体 `alibaba/jun-2`，Worker 组 `group1`。复用已规划的 `batchDeliveryId=8`、`deliveryBatch=20261125-fix`；6,819 个原始包合计 304,624,621,552 字节，规划 3,541 个交付包，原始包超限过滤数为 0。repair 追加包仍纳入后续消费。

迁移 V25～V29 均成功。批次绑定 archive-batch v10（PipelineVersion ID 19，SHA `2758886bd72e16acf6f43b4d58eedbb036d9abfd7fec3bb2ca1044290b3fb0ed`）；提交绑定 archive-delivery v10（ID 20，SHA `82706257bacbd45e891d90882ef4089eaf764a013371c83191ed5aede7c58303`）。本次故障修复未改写不可变版本。

试跑 `deliveryId=9` 已 DELIVERED。两个任务独立完成 SOURCE_GET、TARGET_GET、FTP_GET、SIZE_SHA、NO_PARTIAL，见 `external-verification-1.log`。内容比对收据为 `content-181.json`、`content-182.json`：总计 356 个请求、1,068 个文件，原始 ID、目录名及非 metadata 正文保持一致，源日期时间规范为秒级格式。

| taskId | 文件名 | 来源数 | sizeBytes | SHA256 |
| --- | --- | --- | --- | --- |
| 181 | batch_0000001.tar.gz | 3 | 71,233,013 | e8e76cbc342569c82d5c8eb8eab4a593e7aabb6742d13173d41887bd3a50ff97 |
| 182 | batch_0000002.tar.gz | 4 | 91,923,296 | 4514077745f7ae5781cca7c62cabfb3652ef88f0cda142831c756e5771deefb0 |

目标 OSS 为 `oss://sg-storage-20261013-6vmlk/root/2026-11-25-20261125-fix-root/<文件名>`；FTP 为 `ftp://ftp-june/2026-11-25/<文件名>`，宿主机文件位于 `/srv/maas-sftp/maasData/archive/june-2/2026-11-25/`。

全量首批 `deliveryId=10` 领取 100 包。并发触发 9 次 PG 死锁，3 个节点记录 FAILED，另有失败结果事务回滚后残留的 RUNNING 租约。根因为各 Worker 无序更新同一提交全部 delivery_batch 包的聚合 SQL。修复统一节点结果、retry/resume/pause、跨日和投影的父批次→任务/包→提交锁顺序，只更新状态变化的包；外部节点 IO 不持有父批次锁。

北京时间 2026-11-25 08:24:16，首批 `deliveryId=10` 已 DELIVERED，100 个任务、172 个源包通过独立真实外部验收（`full/external-10.log`、`full/verified-10.json`，验收日志 SHA `0957b04c3541887fc4231ed159eafe54c6d58aa343a447a339168aca09d6d692`）。加上试跑，共 102 个交付包、179 个源包已完成独立验收。六个残留租约对应任务均已正常到期认领并完成。第二批 `deliveryId=11` 已自动领取 100 包；此时修复后无新增节点失败，PG 死锁累计仍为修复前的 9，不能将剩余全部包标记通过。

Worker/API 已发布修复，八个 Worker 均使用镜像 `sha256:5c2d87a5e95eb2cd394dd6a9832fd8cfb3b9a65274f6214fe4eb7afa1c35cafb`。旧镜像、源码及私有数据库备份保留在受控 `deploy/backups/20261125-projection-deadlock/`，不复制到普通证据。既有租约等待正常到期认领，不删租约、不 SQL 伪造业务状态。重建 API 后网关缓存旧地址导致 `/monitor` 返回 502，已重载 Nginx 并确认恢复 200。

完整本地门禁日志为 `local-validation-projection-fix.log`：Python 31 项、注册 4 项、Java 61 项中 58 通过且 3 项外部测试按本地模式 skipped、前端 3 项及构建、Shell/Compose 和新建/重复迁移通过。另有 16 项续跑与独立内容验收测试通过。新增并发回归使用八个真实 PG 事务、两个提交和多来源包验证处理、重试、完成投影。本地 skipped 不作为生产交付证据。

运行快照 `runtime-after-projection-fix.json` 保留采样时间、实际 ID、版本与任务/包事实。后续线上日志为 `full-after-projection-fix.log`；每次独立验收日志、收据与任务事实保存在 `full/`，资源采样保存在 `resources-20261125.jsonl`。每次最多消费 100 包，DELIVERED 并独立验收通过后继续；本对话每 10 分钟巡检本次交付，直至完成或用户取消。

用户追加要求检查 FTP 包内目录是否当日全局唯一。北京时间 08:31:06 固化当日全部 202 个成品快照，08:34:45 完整扫描结束：16,399 个请求目录、16,399 个 requestId 均唯一，无跨包重复目录、重复 ID、包内重复文件成员或读取失败；结束时无快照后新增成品或 `.partial`。所有成品实际 size/SHA 均匹配成功交付节点事实。本次直接读取 FTP 容器实际绑定的宿主机日期目录，流式完整读取 gzip/TAR 拼接成员，不解包落盘、不改名、不修改交付状态。报告为 `ftp-directory-uniqueness-20261125-0831.json`，期望交付事实为 `ftp-day-expected-artifacts-20261125.json`，可复核脚本为 `audit-ftp-directory-uniqueness.py`。该通过只覆盖此快照，后续新包不能自动算作已检查。

2026-11-25 用户明确指示“如果磁盘容量低就清理掉无用缓存，首要的就是交付当天的作业”。本次运行已显式启用 `PROD_EVICT_VERIFIED_SOURCE_CACHE=true`，不再等待容量方案选择。低于 75 GiB 时逐文件回收本次已独立验收提交的可重建源缓存与 FTP 回验副本；允许忽略旧批次未消费规划引用，保护本批次未交付来源及所有已建未完成任务引用。核对本地 size/SHA、验证验收日志摘要后，删除前再次查询引用，新引用跳过留证。最终 OSS/FTP、manifest、数据库事实和租约保持保留；回收后仍低于 30 GiB 才停止新增提交。日志切换为 `full-after-cache-authorization.log`，每份 `full/local-copies-*.json` 记录显式开关、白名单及实际删除/跳过项。变更通过 22 项 Python 测试、Shell 语法和真实隔离 PostgreSQL 测试，后者覆盖旧规划引用、当前未交付来源、六种未完成任务状态和三种终态；临时 PG 容器已删除。此前 284 GiB 源缓存和约 99 GiB 初始可用空间为历史基线，不作为后续剩余空间值。

`cache-authorization-reclamation.json` 记录本次已回收 68,132,155,302 字节、1,293 个可重建本地文件，包含源缓存与 FTP 回验副本，不包含最终交付文件。该汇总是有时间戳的快照，后续回收继续追加逐文件审计。

用户继续询问性能提升后，使用资源与节点事实定位：近期平均 IO wait 为 42.97%，节点切换平均约 0.25～0.37 秒，repair 每包平均约 14～17 秒。先减少独立验收的根盘 IO：验收容器 `/tmp` 使用上限 2 GiB 的私有 tmpfs，允许 Maven 原生库映射所需的 exec，保留 nosuid/nodev；真实 SOURCE_GET/TARGET_GET/FTP_GET 与 size/SHA 流程不变，Worker 工作目录、压缩级别和并发未调整。实机挂载容量检查、完整真实预检通过。第一轮 tmpfs 验收 `deliveryId=16` 的 100 个任务、232 个源包全部通过，测试阶段 43.16 秒、含容器启动的完整验收进程 48.43 秒；此前 `deliveryId=11..15` 相应完整验收进程为 102～177 秒。详见 `verification-io-performance.json`。这是单轮观察，来源字节和文件缓存不同，尚不能据此宣称整个交付流程具有同等提速；继续记录后续完整轮次。切换续跑器时批次锁拒绝了一次重复启动，没有重复消费包或改变 Worker 状态。

2026-11-25 第 19 单出现 3 次 InterruptedException，随后任务 1125/1126 在旧 staging 清理中报 FileNotFoundError，共 5 条失败节点记录；全量入口按规则停止新增提交。原因为 ProcessSupport 中断退出时没有结束原 Python，旧进程与重试同时清理同一任务目录。修复为所有退出路径回收数据面进程子树并有界等待，执行服务临时清除中断标记以正常提交失败及 RETRY_WAIT 后恢复标记，repair 全程持任务私有 `.repair.lock` 后才清理和封包。没有放宽数据验证、包上限、ID 或目录保留规则。

修复通过完整本地门禁：Python 32 项、注册 4 项、Java 64 项中 61 通过和 3 项生产外部测试按本地模式 skipped、前端 3 项与构建、Shell/Compose 及迁移检查。Java 包含真实 Python 中断/超时回收测试和真实 PostgreSQL 中断后失败/重试持久化测试；Python 包含两个并发重试在锁释放前不得清理 staging、释放后成品 SHA 一致的测试。本地 skipped 不作为生产通过。日志为 `repair-interruption-local-validation.log`、`repair-interruption-python-tests.log`、`repair-interruption-process-tests.log`。

北京时间 09:59:50（UTC 01:59:50）在无 RUNNING 任务时切换八个 Worker，新镜像均为 `sha256:41824761aa332c25d01b651688df43dda5117f8951c88d94fcd13d0ebaf23eaf`，旧镜像与源码保留在受控备份。通过正常 resume 从 repair 恢复原任务 1125/1126；两者在 attempt=3 成功，并完成 qualify、OSS 上传和 FTP 回验，保持原 taskId/newFileName/来源关系。独立内容比对共 163 个请求、489 个文件通过（`repair-interruption-content-1125.json`、`repair-interruption-content-1126.json`）。失败与恢复事实分别为 `repair-interruption-failure-facts.json`、`repair-interruption-recovery-facts.json`，API 请求/响应和 Worker 镜像标识亦保留。

北京时间 10:02:40，`deliveryId=19` 的全部 100 个任务、212 个源包通过独立 SOURCE_GET、TARGET_GET、FTP_GET、SIZE_SHA、NO_PARTIAL，验收日志 SHA 为 `51072cfbbe184b1cd39b0aafa73883f58b7163b8751f317422c83e6235ca8943`（`full/external-19.log`、`full/verified-19.json`）。累计 1,002 个包已完成独立验收，第 20 单已自动开始；修复后无新增失败，PG 死锁累计仍为历史的 9。此为运行中进展，不能将剩余 2,539 个包记为通过。

线上复核命令：

```bash
cd /opt/archive-delivery-platform
curl -fsS 'http://127.0.0.1:8080/api/v1/dashboard/summary?deliveryId=8'
curl -fsS 'http://127.0.0.1:8080/api/v1/dashboard/summary?deliveryId=10'
tail -40 docs/evidence/20261125-fix/full-after-cache-authorization.log
docker compose -f deploy/docker-compose.production.yml --env-file deploy/.env ps
df -h /
```

仅在续跑器已退出且核对已有提交、验收收据和授权后，从原生产入口恢复，避免重复启动或重复 POST：

```bash
PROD_USE_PUBLISHED_PIPELINES=true PROD_DELIVER_ALL=true PROD_EVICT_VERIFIED_SOURCE_CACHE=true PROD_BATCH_WAVE_SIZE=100 \
PROD_DELIVERY_BATCH=20261125-fix PROD_DELIVERY_DATE=2026-11-25 \
PROD_TARGET_COUNT=2 PROD_SUBMISSION_COUNT=1 \
PROD_OSS_URI='oss://sg-storage-20261013-6vmlk/internal/gpt-jayden-model-effort-products-20261124-v6/products/' \
./scripts/production-registry.sh archive-batch-jun
```
