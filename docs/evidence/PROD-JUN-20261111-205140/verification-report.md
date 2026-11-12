# PROD-JUN-20261111-205140 生产验证报告

## 结论

**生产验证通过（DELIVERED）。** 本 case 为 alibaba/jun 单实体 targetCount=1，不包含暂停、retry 或跨日状态测试。

- 验证模式：生产实现，真实 Compose、PostgreSQL、PowerJob、OSS SDK 和 FTP。
- deliveryId：2；taskId：2；deliveryEntityId：1。
- PipelineVersion：5；Flyway 最新成功版本：23。
- deliveryDate：2026-11-12；cutoffAt：2026-11-12T10:00:00Z（UTC）。
- 完成时间：2026-11-11T12:52:43Z（UTC）。
- Worker：worker-1 / group1；六个节点全部 SUCCEEDED。

## 提交与来源

提交只包含 ossUri、pipelineCode、deliveryDate、deliveryBatch 和 targets；target 为 alibaba/jun × 1。

入选两个来源，mapping 顺序为 dataId 3、4，metadataIds 与来源 dataId 一致：

| dataId | 文件 | sizeBytes | SHA256 |
| --- | --- | ---: | --- |
| 3 | batch_00002.tar.gz | 3,034,797 | `606ccf9e47077c84f6cbd077dd69a04455cd0938572c6549bfd35e42bdfdec1a` |
| 4 | batch_00003.tar.gz | 6,878,486 | `9a77edee076258d1cfba9238ed13ece6e30e60d57b0cb8de3c488cd98d5b5eba` |

来源合计 9,913,283 字节；merge 请求数 25；repair entityCode/providerCode 为 jun；sourceDownload 两项均 cacheHit=true。独立验证再次真实 GET 两个来源并核对 size/SHA。

## 最终产物

- 文件名：batch_00001.tar.gz
- 大小：8,469,625 字节，处于 8–10 MiB 边界内。
- SHA256：`a16d8f829c64e11fa1eb6f1e24630fa3925ea15264f1bd664c581bd2bc312e87`
- repair 使用 PipelineVersion 固定的 gzipCompressLevel=5。

OSS URI：

```text
oss://sg-storage-20261013-6vmlk/june/2026-11-12-PROD-JUN-20261111-205140-june/batch_00001.tar.gz
```

FTP 宿主机文件：

```text
/srv/maas-sftp/maasData/archive/june/2026-11-12/PROD-JUN-20261111-205140/batch_00001.tar.gz
```

独立 OSS GET、FTP 重新下载和宿主机 SHA 均为上述值；`ftp-partial.txt` 为空。

## 节点事实

| processId | 节点 | 结果 |
| --- | --- | --- |
| 5 | sourceDownload | SUCCEEDED |
| 6 | mergePackages | SUCCEEDED |
| 7 | repair | SUCCEEDED |
| 8 | qualify | SUCCEEDED |
| 9 | targetUpload | SUCCEEDED |
| 10 | ftpDownloadVerify | SUCCEEDED |

最终任务 SUCCEEDED，主单 DELIVERED。

## 自助复核

```bash
cd /opt/archive-delivery-platform
cat docs/evidence/PROD-JUN-20261111-205140/database-facts.txt
cat docs/evidence/PROD-JUN-20261111-205140/external-verification.log
cat docs/evidence/PROD-JUN-20261111-205140/ftp-sha256.txt
cat docs/evidence/PROD-JUN-20261111-205140/ftp-partial.txt
sha256sum /srv/maas-sftp/maasData/archive/june/2026-11-12/PROD-JUN-20261111-205140/batch_00001.tar.gz
```

同目录还保存提交、API、Compose、应用日志和完整流水线执行日志；敏感配置扫描未发现 AK/SK、密码或服务器登录凭据。
