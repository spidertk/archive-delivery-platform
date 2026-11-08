# V16 delivery_mapping 与两厂商真实端到端验收

> 验证日期：2026-11-08
> 验证模式：LOCAL（真实 Compose、真实 OSS/FTP，不是模拟验收）
> 结论：通过
> deliveryId：3
> deliveryBatch：`real-e2e-1794134282`
> Flyway：16；实际提交绑定 PipelineVersion：8

## 1. 运行与提交流程

- 实际启动 PostgreSQL、MySQL、PowerJob、archive-server、三个 Worker 和三个 FTP 容器，server 健康检查通过。
- 来源：`oss://sg-storage-20261013-6vmlk/internal/gpt-delta-seven-20261105-accepted-20261106-v1/products/`。
- 提交仅使用 `ossUri/pipelineCode/deliveryDate/deliveryBatch/targets[vendorCode,targetCount]`，两个目标为 `LOCAL-FTP-1`、`LOCAL-FTP-2`，各 targetCount=1，maxPackageBytes=10485760。
- 两个任务实际进入 PAUSED，使用相同 deliveryBatch 和相同参数再次提交，deliveryId 保持 3；随后 rollover 生成新 taskId，恢复两个 FTP 后逐任务 retry，最终 DELIVERED。

## 2. 来源、合并和数据库事实

| dataId | 来源对象 | sizeBytes | SHA-256 |
|---|---|---:|---|
| 7 | `products/0002029c49e7acbf89eabc18cd9b6210050bd861c8cdcd9176d0499ee22b2b37.tar.gz` | 1499869 | `0002029c49e7acbf89eabc18cd9b6210050bd861c8cdcd9176d0499ee22b2b37` |
| 8 | `products/0030d07c45078df0296459f5f2d25d90724845aceb5ff0f5b4e09c23c124b01c.tar.gz` | 696521 | `0030d07c45078df0296459f5f2d25d90724845aceb5ff0f5b4e09c23c124b01c` |
| 9 | `products/00b2ca4611946b5c74d1099da41d0c1a29bd6897431905cdde4d7c494ec4f9bb.tar.gz` | 2158256 | `00b2ca4611946b5c74d1099da41d0c1a29bd6897431905cdde4d7c494ec4f9bb` |

发现阶段在下载前跳过超过 10 MiB 的对象。三份来源均真实 GET 并由独立验收再次下载核对 size/SHA。merge 保留请求目录，不生成 merged-source.tar.gz；repair 只最终封包一次。任务 file_mapping 仅含各来源 originalPath，不含 `source-00002__` 等人工键。

| 血缘 | taskId | entity | 来源 | metadataIds | 最终状态 |
|---|---|---|---|---:|---|
| 旧任务 | 7 | LOCAL-FTP-1 | data 7、8 | 6 | EXPIRED |
| 旧任务 | 8 | LOCAL-FTP-2 | data 9 | 13 | EXPIRED |
| 新任务 | 9 | LOCAL-FTP-1 | data 7、8 | 6 | SUCCEEDED |
| 新任务 | 10 | LOCAL-FTP-2 | data 9 | 13 | SUCCEEDED |

delivery_mapping 在新旧任务均完整复制。跨日日期从 2026-11-09 顺延到 2026-11-10，新旧关系由 TASK_EXPIRED/TASK_REGENERATED payload 保存。request_id_allocation 的 request_namespace 为 `real-e2e-1794134282`。

## 3. 外部 OSS/FTP 复核

| taskId | 交付 OSS URI | sizeBytes | SHA-256 |
|---|---|---:|---|
| 9 | `oss://hz-storage-20261013-6vmlk/archive-delivery/real-e2e-1794134282/3/entity-2/2026-11-10/batch_00001.tar.gz` | 2171883 | `6bb271a5ab34bcad29e439cf11069abb2721a3f804214a35346db3f01a09ff5c` |
| 10 | `oss://hz-storage-20261013-6vmlk/archive-delivery/real-e2e-1794134282/3/entity-3/2026-11-10/batch_00001.tar.gz` | 2150819 | `98d00c06aa370607bc75caf9b78dedf7d328b26b6ff3365f94c6d7144de6b31b` |

两件 OSS 均实际 GET 回验。两个 FTP 账户分别上传并重新下载，Worker 保留文件如下，独立 SHA 与任务 artifacts、delivery_node、交付 OSS 一致且无 `.partial`：

```text
6bb271a5ab34bcad29e439cf11069abb2721a3f804214a35346db3f01a09ff5c  /data/ftp-verify/3/2026-11-10/entity-2/batch_00001.tar.gz
98d00c06aa370607bc75caf9b78dedf7d328b26b6ff3365f94c6d7144de6b31b  /data/ftp-verify/3/2026-11-10/entity-3/batch_00001.tar.gz
```

`RealExternalVerificationTest#verifyRealDelivery` 实际输出：

```text
REAL VERIFIED deliveryId=3 taskId=9 deliveryEntityId=2 sources=2 size=2171883 sha256=6bb271a5ab34bcad29e439cf11069abb2721a3f804214a35346db3f01a09ff5c
REAL VERIFIED deliveryId=3 taskId=10 deliveryEntityId=3 sources=1 size=2150819 sha256=98d00c06aa370607bc75caf9b78dedf7d328b26b6ff3365f94c6d7144de6b31b
REAL E2E PASSED deliveryId=3 tasks=2 sources=3 maxPackageBytes=10MiB pause/resume/retry/rollover verified
```

## 4. 标准回归

- `python3 -m unittest nodes/python/test_archive_repair.py -v`：6/6 通过，覆盖目录合并、同名成员 originalPath、旧编号补号、无中间 tar、协议失败和临时清理。
- `./scripts/mvn.sh test`：BUILD SUCCESS；delivery-core 19 个测试通过，包含 V1–V16 迁移、全表全列注释、业务编码提交、批次幂等、暂停再提交、产物对、请求编号外键和三实体跨日状态。
- `npm ci && npm run test && npm run build`：前端 1 个测试通过，生产构建成功。
- `bash -n scripts/*.sh`、`git diff --check` 和两份 Compose config 校验通过。

真实外部测试在普通 Maven 套件中的 skipped 未计为通过证据；本记录仅依据上述实际 Compose/API/Worker/PG/OSS/FTP 执行结果。

## 5. 自助复核命令

```bash
# 主单、任务、映射和事件
 docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T postgres \
  psql -U archive -d archive_delivery -c "SET search_path TO archive_delivery; \
  SELECT delivery_id,delivery_batch,status,delivery_date FROM delivery_submit WHERE delivery_id=3; \
  SELECT task_id,delivery_entity_id,delivery_date,execution_status,new_file_name,size_bytes,artifacts,metadata_ids \
  FROM delivery_task WHERE delivery_id=3 ORDER BY task_id; \
  SELECT task_id,data_id,source_seq FROM delivery_mapping WHERE delivery_id=3 ORDER BY task_id,source_seq; \
  SELECT event_type,payload FROM delivery_event WHERE delivery_id=3 ORDER BY event_id;"

# 保留 FTP 文件的 size/SHA 与 .partial 检查
 docker run --rm -v deploy_archive_data:/data:ro alpine:3.22 \
  sh -c 'find /data/ftp-verify/3 -type f ! -name "*.partial" -exec sha256sum {} \;'

# 重新执行独立 OSS/FTP 核验（使用当前受控 Pipeline 配置，不输出 Secret）
./scripts/verify-delivery.sh 3
```
