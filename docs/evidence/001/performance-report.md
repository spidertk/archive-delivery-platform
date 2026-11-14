# 交付单 001 真实场景与容量评估报告

日期：2026-11-14  
交付机：8.222.180.98  
验证入口：`scripts/production-registry.sh archive-delivery-jun`

## 1. 最终结论

交付单 `deliveryId=3`、`deliveryBatch=001` 已完成首批 200 包和同单追加 500 包，最终 700 个任务全部 `SUCCEEDED`，主单为 `DELIVERED`。

- 业务日期：2026-11-15；截止时间为 Asia/Shanghai 02:00。
- 主单绑定 PipelineVersion：10；后续配置发布不改变历史主单绑定。
- 包名从 `batch_00001.tar.gz` 到 `batch_00700.tar.gz`，700 个名称全部唯一。
- 共映射 9000 个不同 dataId；按原始 `oss://bucket/key` 检查，重复 URI 为 0。
- 最终产物合计 41,698,892,079 字节（约 41.70 GB）。
- 全量清单包含 24,889 个有效源包，合计约 122.54 GB；本轮未把剩余来源误标记为已交付。
- 最终独立复核覆盖 SOURCE_GET、TARGET_GET、FTP_GET、SIZE_SHA、NO_PARTIAL；日志结果为 `REAL E2E PASSED deliveryId=3 tasks=700 sources=9000`。

## 2. 生产配置

下载和上传节点均使用 `https://oss-ap-southeast-1-internal.aliyuncs.com`。下载节点不再保存 bucket/prefix，完全使用提交接口的 `ossUri`：

```text
oss://sg-storage-20261013-6vmlk/internal/gpt-jayden-raw-20261113-v1/daily-accepted-20261113-v2/products/
```

当前实测机器为 4 vCPU、约 14 GiB 内存、295 GiB 系统盘。推荐保留当前交付并发，而不是继续堆高本机并发：

| 配置项 | 当前值 | 说明 |
| --- | --- | --- |
| Worker 容器 | 4 | 同机 group1 通用执行槽 |
| 组内 RUNNING 上限 | 3 | 实测 CPU 合计约 371%，4 vCPU 已接近打满 |
| 准备下载并发 | 8 | 来源发现/缓存下载使用有界线程池 |
| 任务租约 | 900 秒 | 避免大 repair 超过 120 秒后重复认领 |
| 节点调度周期 | 1 秒 | 减少节点间空等 |
| 复核后工作清理 | 开启 | 保留 manifest，清理任务大体积中间副本 |
| 外部复核并行度 | 4 | 每个任务仍完整执行 OSS/FTP 校验 |

## 3. 实测性能事实

本轮包含真实故障修复、重试、补源和一次磁盘保护暂停，因此总墙钟时间不能作为稳定环境的纯吞吐基准。

成功节点的数据库时间事实：

| 节点 | 平均耗时 | P95 |
| --- | ---: | ---: |
| repair | 16.17 秒 | 32.55 秒 |
| mergePackages | 3.30 秒 | 5.98 秒 |
| targetUpload | 2.01 秒 | 3.85 秒 |
| ftpDownloadVerify | 1.83 秒 | 3.19 秒 |
| sourceDownload | 1.34 秒 | 2.74 秒 |
| qualify | 0.35 秒 | 0.63 秒 |

repair 是主要优化点。增加 CPU 比单纯增加 Worker 更有效；OSS internal endpoint、同卷硬链接和复核后清理分别减少网络绕路、物理复制和磁盘占用。

## 4. 100 GB 容量与时间估算

以下按当前真实包大小分布、repair 主导和稳定修复后代码估算，不是一次无中断 100 GB 压测承诺。

| 配置 | 预计 100 GB 交付时间 | 说明 |
| --- | ---: | --- |
| 当前 4 vCPU、3 运行槽 | 约 4–6 小时 | 不含额外全量独立复核；小文件/request 数增加会继续拉长 repair |
| 8 vCPU、6 运行槽 | 约 2–3 小时 | 按 repair 近似扩展，仍需实测网络和磁盘是否成为新瓶颈 |
| 16 vCPU、12 运行槽 | 约 1–2 小时 | 只有在磁盘和网络同步升级时才值得评估，不应按 CPU 线性承诺 |

独立外部复核还会增加时间。700 包最终复核采用 4 路并行，实际耗时约二十多分钟；100 GB 全量复核建议预留 30–60 分钟。

磁盘不能只看最终文件。100 GB 级任务在源缓存、merge、repair、OSS 回验、FTP 回验和最终 FTP 之间会产生多份副本。本轮在暂停前曾达到约 95% 磁盘使用率；通过 697 个已验证任务的定向清理后恢复到约 199 GiB 可用。

建议：

- 500 GiB 数据盘是开启复核后清理时的最低评估起点，并设置 70% 预警、80% 暂停新提交的运维阈值。
- 1 TiB 更适合作为 100 GB 级生产交付和保留多批历史的推荐配置。
- 8 vCPU/16 GiB 内存可作为下一轮基准；内存不是当前主要瓶颈，无需为 6 槽盲目配置过大内存。

## 5. 本轮修复的真实问题

- 下载节点不再依赖固定 bucket/prefix，所有 OSS endpoint 使用内网地址。
- `PROD_TARGET_COUNT` 和命令行覆盖不再被 `.env` 默认值覆盖。
- 支持 DELIVERED 主单对原实体追加 targetCount，保留历史请求幂等和连续包号。
- 准备下载有界并行；规划从全局子集回溯改为连续快速路径加降序平衡装箱。
- 重试只排除已映射来源，未映射缓存可恢复复用。
- `requestMode=null` 按 `biz_request.stream` 或唯一响应文件推导并写回 STREAM/SYNC。
- 长任务租约调整为可配置 900 秒，避免 repair 重复认领。
- 压缩后低于最终下限时通过受控补源 API 追加来源并重跑，不修改历史 PipelineVersion。
- 补源后 requestId 号段可安全扩容，普通同次数重试仍复用。
- 外部复核支持有界并行和已验证副本生命周期清理。

## 6. group1/group2 扩展评估

group2 本轮没有可用服务器和真实交付证据，因此以下仅为架构评估。

- `worker_group` 与独立 RUNNING 配额能隔离任务认领和本地工作目录，但不能自动隔离共享 PG、PowerJob、OSS 账户限流或源站带宽。
- 当前全局准备入口仍是单调度链路；不同组可能排队等待来源发现和准备。
- 跨服务器不共享 `/data`，远端组未命中缓存时会回退真实 OSS，可能产生额外下载。

要实现最快且互不影响，目标架构应为每组独立 worker、数据盘和准备队列，并设置组级 OSS 速率/连接预算；共享 PG/PowerJob 仍需监控连接与锁。上线前应增加 group2 实机并发验收，不能把上述评估写成已通过。

## 7. 证据与复核

证据目录：`docs/evidence/001/`。

关键文件：

- `submit-200.json`、`submit-500.json` 与对应响应。
- `external-verification-200.log`、`external-verification-697-parallel.log`、`external-verification.log`。
- `database-facts.txt`、`tasks-api.json`、`ftp-sha256.txt`、`ftp-partial.txt`。
- `resource-samples.log`、`source-inventory.txt`。

生产服务器上的原证据目录为 `/opt/archive-delivery-platform/docs/evidence/001`。最终日志包含：

```text
REAL E2E PASSED deliveryId=3 tasks=700 sources=9000
Production verification passed deliveryId=3 batch=001
Golden production registry passed cases=archive-delivery-jun
```
