# jun-2 2026年11月21日交付验收报告

本轮真实交付验收通过。相同的 **343 个源包**全部交付，内含 **18,455 个请求目录**，规划为 **89 个交付包**。批次单 **3**、交付单 **4** 均为 `DELIVERED`，89 个任务及 534 次节点执行全部成功。生产注册入口退出码为 0，独立 OSS/FTP 复核通过。

## 交付事实

| 项目 | 实际结果 |
| --- | --- |
| 业务日期 | 2026-11-21，Asia/Shanghai |
| 交付批次 | JUN2-20261121-REDO |
| 实体与执行组 | alibaba/jun-2，group1 |
| 批次单与交付单 | batchDeliveryId=3，deliveryId=4 |
| 任务 ID | 90 至 178，完整清单见 final-facts.json |
| Pipeline | archive-batch v3 / ID 5；archive-delivery v3 / ID 6 |
| 数据库迁移 | Flyway V25，success=true，既有迁移文件保持一致 |
| 提交与完成时间 | 2026-11-21 03:52:37 至 2026-11-21 04:06:48 |
| 来源数量与大小 | 343 个，6,507,105,830 bytes，6.060 GiB |
| 最终数量与大小 | 89 个，6,003,554,194 bytes，5.591 GiB |
| 最大交付包 | 97,843,810 bytes，小于 100 MiB |
| Worker 容量与并发 | 每轮分配 6000 包；同组最多 3 个运行节点 |

来源为 `oss://sg-storage-20261013-6vmlk/internal/archive-delivery-migrations/DO_20261117_00001/remaining-343/products/`。新旧来源按 URI、size、SHA 比较，343 项全部一致；全部来源均且仅被本次批次关系消费一次。OSS 目标使用 `oss://sg-storage-20261013-6vmlk/root/2026-11-21-JUN2-20261121-REDO-root/`。FTP 物理目录为 `/srv/maas-sftp/maasData/archive/june-2/2026-11-21/`，文件名为 `batch_00001.tar.gz` 至 `batch_00089.tar.gz`。

## 真实外部验收

| 检查 | 结果与证据 |
| --- | --- |
| SOURCE_GET | 重新 GET 343 个真实源 OSS 对象，并核对 size/SHA |
| TARGET_GET | 重新 GET 89 个真实目标 OSS 对象，并核对 size/SHA |
| FTP_GET | 独立下载 89 个现有 FTP 文件，使用只读验收模式 |
| SIZE_SHA | 89 个交付包的节点、目标 OSS、FTP 与本地回验事实一致 |
| NO_PARTIAL | FTP 无 `.partial`；独立回验无 `.partial` |
| 数量和血缘 | 343 个来源 → 343 条批次关系 → 89 个任务，无剩余未交付包 |
| 中间副本清理 | 89/89 个 FTP 节点记录 workCopyCleaned=true，保留 manifest、source-cache 和回验文件 |

独立 FTP 验收在文件缺失或 SHA 不符时直接失败。验收采用真实 SDK 与 FTP 服务，未使用替身、SQL 写成功状态或 PowerJob 实例状态代替文件证据。

主要证据：[外部复核日志](external-verification-1.log)、[最终数据库及文件事实](final-facts.json)、[FTP SHA 清单](ftp-sha256.txt)、[FTP 残留检查](ftp-partial.txt)、[注册入口日志](pipeline-execution.log)。日志最终结果为 `REAL E2E PASSED deliveryId=4 tasks=89 sources=343 maxPackageBytes=104857600`。

## 旧测试流水清理与节点变更

根据本次明确授权，先预检、确认无运行任务及有效租约，并在服务器私有备份目录保留旧配置与业务流水。随后清除旧交付单 1/2、343 条来源、89 个任务、172 次节点尝试、266 个事件、29 条号段分配及相关批次关系。事件不可变触发器在独占事务内处理后恢复，最终 `tgenabled=O`。FTP 的 2026-11-19、2026-11-22 两个目录共 841 个文件、57,078,074,045 bytes 已删除，并清理旧单工作与回验目录。来源缓存继续保留，OSS 历史对象保留。

FTP 节点现已在每次上传下载前检查日期目录，清理其他合法日期目录，保留当前日期和非日期目录。共享传输锁、独占清理锁及单包锁保护并发和重试；日期前进后，旧日期请求拒绝清理较新目录。上传先写 `.partial`，下载校验通过后原子改名。独立协议测试还覆盖同日后来出现的旧目录、链接保护和只读验收缺失文件失败。

本轮一并修复同组并发容量竞争、重复 FTP 回验下载、gzip 反复扫描、classic Docker builder 兼容和 API 静态资源打包，并对 Worker/API JVM 设置可配置 heap 上限。最终四个 Worker 应用启动正常，API、看板和静态资源返回 HTTP 200。支持性验证包括 Java/PG/DAG 30 项、Python/注册 27 项、前端 1 项及构建；这些逻辑测试与真实外部验收分别记录。

证据：[清理事实](reset-facts.json)、[删除文件清单](deleted-ftp-inventory.json)、[原来源清单](previous-source-manifest.json)、[隔离 FTP 协议测试](isolated-ftp-protocol.log)、[运行配置](runtime-settings.json)、[Java 测试](local-java-tests.json)、[Python 测试](python-tests-final.log)、[源码清单](source-manifest.json)。

## 复核

在 jun-2 的 `/opt/archive-delivery-platform` 执行：

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
curl -fsS 'http://127.0.0.1:8080/api/v1/dashboard/summary?deliveryId=4'
sha256sum -c docs/evidence/JUN2-20261121-REDO/ftp-sha256.txt
```

重新执行真实源、目标和 FTP 下载复核使用同目录 [recheck.sh](recheck.sh)。完整版本、URI、SHA、taskId 与节点计时见 [final-facts.json](final-facts.json)。本轮生产交付通过；6000 包容量配置的后续规模验收见 [性能报告](performance-report.md)。
