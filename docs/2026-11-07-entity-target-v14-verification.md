# V14 实体目标核心链路真实验收记录

> 验收时间：2026-11-07T21:22:15+08:00
> deliveryId：1
> PipelineVersion：2
> 最终结论：真实 OSS/FTP 核心链路通过

## 1. 验收口径

本次执行真实源 OSS 下载、本地 repair/qualify、三个直属实体任务、交付 OSS 上传与 GET 回验、本地 FTP 上传后重新下载，以及独立 `verify-delivery.sh` 复核。没有使用 SSH 跳板、fake/Local Node 或以 PowerJob 状态代替交付状态。

- `delivery_submit.status=DELIVERED`，targetTotal=3，三个 targets 均 assignedCount=completedCount=1。
- 最新 task 1、4、5 全部 SUCCEEDED；对应最终 ftp.download-verify delivery_node 均 SUCCEEDED。
- task 1 在恢复 FTP 后执行单任务 retry，最终 attempt 2 成功。
- rollover 使旧 task 2→新 task 4、旧 task 3→新 task 5；旧行 EXPIRED，新行日期为 2026-11-08，deliveryId/entity/targetSeq 保持不变。
- 独立验证器实际 GET 源/交付 OSS、重新登录三个 FTP 账户下载并核对 size/SHA，最终输出 `REAL E2E PASSED deliveryId=1 tasks=3 retry>=1 rollover>=1 OSS/FTP SHA verified`。
- 当前实体路径无 `.partial`；历史外部文件保留，未清目录。

## 2. 当前任务与外部产物

| 最新 taskId | deliveryEntityId | targetSeq | deliveryDate | sizeBytes | SHA-256 |
|---:|---:|---:|---|---:|---|
| 1 | 1 | 1 | 2026-11-07 | 54139210 | `b7a42241087212a445d6674a7e8900e004dd70243603cc4acd0f44f424a0dfbf` |
| 4 | 2 | 1 | 2026-11-08 | 13145240 | `62f813169b82993df9d77d44a29aab17a7003bad617307b1b1227e14899a9187` |
| 5 | 3 | 1 | 2026-11-08 | 40223988 | `a1bd488fcc610a4b15696f20c6b4d9d24d6cb63308d94f42f2e93895a4027130` |

交付 OSS 当前 URI：

```text
oss://hz-storage-20261013-6vmlk/archive-delivery/1/entity-1/2026-11-07/batch_00001.tar.gz
oss://hz-storage-20261013-6vmlk/archive-delivery/1/entity-2/2026-11-08/batch_00002.tar.gz
oss://hz-storage-20261013-6vmlk/archive-delivery/1/entity-3/2026-11-08/batch_00003.tar.gz
```

FTP 当前 URI：

```text
ftp://ftp-entity-1/1/2026-11-07/entity-1/batch_00001.tar.gz
ftp://ftp-entity-2/1/2026-11-08/entity-2/batch_00002.tar.gz
ftp://ftp-entity-3/1/2026-11-08/entity-3/batch_00003.tar.gz
```

## 3. 保留文件原始证据

OSS 源文件仍保留在 Worker 持久卷：

```text
828275e0f38dc001f6616ee8c5e9a88e125094dd2f1ddad71fd3e6dbb44c54e0  /data/work/delivery-1/data-1/source/batch_00001.tar.gz
881a27fc478c38a62438343e21d0ab0d55fb51ac82209a7d63328217fb9ef1ef  /data/work/delivery-1/data-2/source/batch_00002.tar.gz
b5c0225909b64219eb13c9797ca2966d673f0848dc88e1b4ac0771571abe40c1  /data/work/delivery-1/data-3/source/batch_00003.tar.gz
```

FTP 下载文件仍保留：

```text
b7a42241087212a445d6674a7e8900e004dd70243603cc4acd0f44f424a0dfbf  /data/ftp-verify/1/2026-11-07/entity-1/batch_00001.tar.gz
62f813169b82993df9d77d44a29aab17a7003bad617307b1b1227e14899a9187  /data/ftp-verify/1/2026-11-08/entity-2/batch_00002.tar.gz
a1bd488fcc610a4b15696f20c6b4d9d24d6cb63308d94f42f2e93895a4027130  /data/ftp-verify/1/2026-11-08/entity-3/batch_00003.tar.gz
```

## 4. 自助复核命令

只读查看本次三个 FTP 下载文件及 SHA：

```bash
docker run --rm -v deploy_archive_data:/data:ro alpine:3.22 \
  sh -c 'find /data/ftp-verify/1 -type f -path "*/entity-*" ! -name "*.partial" -exec sha256sum {} \;'
```

只读查看三份 OSS 源下载文件及 SHA：

```bash
docker run --rm -v deploy_archive_data:/data:ro alpine:3.22 \
  sh -c 'find /data/work/delivery-1 -type f -path "*/source/*" ! -name "*.partial" -exec sha256sum {} \;'
```

重新执行完整外部复核（会实际 GET OSS 并登录 FTP，但不清理历史文件）：

```bash
./scripts/verify-delivery.sh 1
```

## 5. 原始证据文件

- `evidence/2026-11-07-v14-real-e2e-execution.log`：真实预检、提交、retry、rollover 和第一次外部验证执行日志。
- `evidence/2026-11-07-v14-verify-delivery-1.log`：修正历史任务过度断言后的最终独立复核，退出码 0。
- `evidence/2026-11-07-v14-schema.txt`：V14 和废弃表不存在的原始查询。
- `evidence/2026-11-07-v14-database.txt`：提交、任务、节点 URI/本地路径/size/SHA 和 retry/rollover 事件。
- `evidence/2026-11-07-v14-summary.json`：DELIVERED 与三个实体目标 API 原始响应。
- `evidence/2026-11-07-v14-source-files-sha256.txt`：OSS 源文件只读 SHA 清单。
- `evidence/2026-11-07-v14-current-ftp-files-sha256.txt`：当前 FTP 文件只读 SHA 清单。
- `evidence/2026-11-07-v14-partial-files.txt`：空文件，证明当前 deliveryId 下无 `.partial`。

第一次完整脚本的三个最新任务均已通过外部校验，但验证器错误地要求“跨日前尚未执行上传的历史任务”也必须存在成功上传记录。验证器已改为仅在历史上传确实存在时比较新旧 URI；随后对同一 deliveryId=1 重新执行独立外部复核并通过，没有重跑或伪造交付状态。
