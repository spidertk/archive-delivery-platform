# 20261115_v3 线上交付验证报告

验证日期：2026-11-16  
验证主机：`8.222.180.98`（4 vCPU、约 15 GiB RAM，4 个 group1 执行槽）  
来源：`oss://sg-storage-20261013-6vmlk/internal/gpt-sse-wire-fix-20261112-v1/products/`  
交付日期：`2026-11-16`  
迁移版本：V25

## 结论

真实生产用例通过。一次创建批次、两次独立提交各 200 包，共交付 400 包、13,485 个原始文件，最终产物合计 32,213,688,182 字节。SOURCE_GET、TARGET_GET、FTP_GET、SIZE_SHA、NO_PARTIAL 均通过，生产入口最终输出 `Golden production registry passed cases=archive-batch-jun`。

## 标识与数量

| 项目 | deliveryId | PipelineVersion | 状态 | 包数 | 原始文件数 | 最终字节数 |
| --- | ---: | ---: | --- | ---: | ---: | ---: |
| 批次创建 `20261115_v3` | 9 | archive-batch=34 | PROCESSING | 699 个规划包，400 个已交付 | 23,000 个已规划 | — |
| 第一次交付 | 10 | archive-delivery=35 | DELIVERED | 200/200 | 6,922 | 16,071,721,455 |
| 第二次交付 | 11 | archive-delivery=35 | DELIVERED | 200/200 | 6,563 | 16,141,966,727 |
| 合计 | — | — | 两次均通过 | 400 | 13,485 | 32,213,688,182 |

批次 9 保持 `PROCESSING` 是正确状态：699 个规划包中只要求本次消费 400 个，剩余 299 包、9,515 个原始文件尚未消费。批次总单只有在全部规划包交付后才应变为 `DELIVERED`。

## 外部事实

- FTP 目录：`/srv/maas-sftp/maasData/archive/june/2026-11-16/`。
- 实际 `batch_*.tar.gz` 恰好 400 个，与两批任务数及 `delivery_batch` 的 400 个 DELIVERED 包一致。
- `.partial=0`。
- 最终文件 SHA 清单位于 `ftp-sha256.txt`；逐任务 OSS/FTP 下载及 size/SHA 核验见 `external-verification-1.log`、`external-verification-2.log`。
- 两次各有 6 类节点、每类 200 次成功执行，共 2,400 个成功节点；没有生成超过 `batch_00699.tar.gz` 的重规划追加包。
- 无合并包最小字节限制，只有 100 MiB 上限。本批实际交付包最小 4,327,780 字节，最大 100,072,233 字节；1 KiB 尾包由本地单元测试覆盖，本批没有 1 KiB 的最终产物，不能冒充实测。

## 复核方式

```bash
cd /opt/archive-delivery-platform
PROD_REUSE_SERVICES=true \
PROD_DELIVERY_BATCH=20261115_v3 PROD_DELIVERY_DATE=2026-11-16 \
PROD_TARGET_COUNT=200 PROD_SUBMISSION_COUNT=2 \
PROD_OSS_URI='oss://sg-storage-20261013-6vmlk/internal/gpt-sse-wire-fix-20261112-v1/products/' \
./scripts/production-registry.sh archive-batch-jun
```

```bash
docker compose -f deploy/docker-compose.production.yml --env-file deploy/.env \
  exec -T postgres psql -U archive -d archive_delivery -P pager=off \
  -c "SELECT delivery_id,submission_type,status FROM archive_delivery.delivery_submit WHERE delivery_id IN(9,10,11) ORDER BY delivery_id;" \
  -c "SELECT delivery_id,execution_status,count(*),sum(size_bytes) FROM archive_delivery.delivery_task WHERE delivery_id IN(10,11) GROUP BY delivery_id,execution_status ORDER BY delivery_id;" \
  -c "SELECT status,count(DISTINCT new_file_name),count(*) FROM archive_delivery.delivery_batch WHERE batch_delivery_id=9 GROUP BY status;"
```

历史失败尝试不作为成功证据：批次 8 的旧规则规划未交付；第二次交付后的首次复核曾因 PostgreSQL 连接重置失败。修复为有界连接池后，两批均完成真实外部复核。性能数据见 `performance-report.md`。
