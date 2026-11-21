# 20261115 批次生产验证报告

## 结论

本次 `archive-batch` + `archive-delivery` 生产验证通过。批次创建一次，jun 独立提交两次，每次 100 包，两个交付单均为 `DELIVERED`，SOURCE_GET、TARGET_GET、FTP_GET、SIZE_SHA、NO_PARTIAL 全部通过。

- 来源：`oss://sg-storage-20261013-6vmlk/internal/gpt-sse-wire-fix-20261112-v1/products/`
- 业务日期/批次：`2026-11-15` / `20261115`
- Flyway：V25
- 批次创建：`deliveryId=5`，`pipeline_version_id=21`
- 第一次交付：`deliveryId=6`，`pipeline_version_id=22`，100/100 `SUCCEEDED`
- 第二次交付：`deliveryId=7`，`pipeline_version_id=26`，100/100 `SUCCEEDED`
- 批次创建总单：`PROCESSING`，因为仍有 1,107 个规划包未消费，不提前标记全部交付。

## 数量与状态核对

| 项目 | 第一次 | 第二次 | 合计 |
| --- | ---: | ---: | ---: |
| 交付包名 | batch_00001–00100 | batch_00101–00200 | 200 |
| 成功任务 | 100 | 100 | 200 |
| 实际消费原始包 | 3,322 | 3,831 | 7,153 |
| 外部复核 | REAL E2E PASSED | REAL E2E PASSED | 全部通过 |

- 原始 OSS：23,000 个有效包，55,599,707,146 字节，无超限过滤。
- 初始规划：531 包。repair 超限时按完整原始包追加规划，776 次重规划后最终为 1,307 包；未切分原始文件、未放宽 100 MiB 上限。
- 最终已交付：200 包、7,153 条原始文件关系。
- 未消费：1,107 包、15,847 条原始文件关系；7,153 + 15,847 = 23,000。
- 最终产物：最小 104,692,565 字节，最大 104,857,515 字节，合计 20,962,914,143 字节，均在 8–100 MiB 内。
- FTP 宿主机最终文件：恰好 200 个 `batch_*.tar.gz`；`.partial` 数量为 0。全部 200 条 SHA-256 见 `ftp-sha256.txt`。

## 证据

- `create-batch.json`、`create-batch-response.json`、`batch-api.json`
- `submit-1.json`、`submit-response-1.json`、`submit-2.json`、`submit-response-2.json`
- `delivery-summary-1.json`、`delivery-summary-2.json`
- `delivery-api-1.json`、`delivery-api-2.json`
- `tasks-api-1.json`、`tasks-api-2.json`
- `external-verification-1.log`、`external-verification-2.log`
- `batch-packages-planned.json`、`batch-packages-final.json`、`batch-summary.json`
- `database-facts.txt`、`compose-services.txt`、`application.log`
- `ftp-sha256.txt`、`ftp-partial.txt`

## 复核命令

```bash
cd /opt/archive-delivery-platform
PROD_REUSE_SERVICES=true \
PROD_DELIVERY_BATCH=20261115 PROD_DELIVERY_DATE=2026-11-15 \
PROD_TARGET_COUNT=100 PROD_SUBMISSION_COUNT=2 \
PROD_OSS_URI='oss://sg-storage-20261013-6vmlk/internal/gpt-sse-wire-fix-20261112-v1/products/' \
./scripts/production-registry.sh archive-batch-jun

docker compose -f deploy/docker-compose.production.yml --env-file deploy/.env \
  exec -T postgres psql -U archive -d archive_delivery -P pager=off \
  -c "SELECT delivery_id,submission_type,status,pipeline_version_id FROM archive_delivery.delivery_submit WHERE delivery_batch='20261115' ORDER BY delivery_id;" \
  -c "SELECT status,count(DISTINCT new_file_name) AS packages,count(*) AS original_files FROM archive_delivery.delivery_batch WHERE batch_delivery_id=5 GROUP BY status ORDER BY status;"

find /srv/maas-sftp/maasData/archive/june/2026-11-15/20261115 \
  -maxdepth 1 -type f -name 'batch_*.tar.gz' -print0 | sort -z | xargs -0 sha256sum
find /srv/maas-sftp/maasData/archive/june/2026-11-15/20261115 \
  -maxdepth 1 -type f -name '*.partial'
```

本地完整门禁 `./scripts/local-pipeline.sh` 同步通过，包括 27 个 core 测试、2 个 Pipeline DAG 测试、9 个 Python 测试和前端测试/构建。
