# Python repair 节点指南

## 模块职责

本目录实现本地源 tar 的安全解包、请求目录合并、数据修复和最终封包。Python 不直接访问 PG，不决定编号、目标分配、流程顺序或交付状态；Java 负责 delivery_mapping、request_id_allocation、任务 facts 和 delivery_node 提交。

## 输入输出边界

```text
archive_merge.py:
  --source            每个原始源 tar.gz，按 source_seq 重复传入
  --target            输出的 merge/requests 工作目录
  --manifest          结构化 merge 结果

archive_repair.py:
  --source            merge/requests 工作目录，不再接受中间 tar
  --target            本地最终输出目录
  --delivery-date     当前 delivery_task.delivery_date
  --entity-code       当前任务 entity_code，写入协议 metadata.providerCode
  --start-number      Java/PG 已分配号段，源 requestId 缺失或为旧格式时使用
  --max-bytes         最终修复包上限
  --output-name       当前任务在 delivery 内唯一的 batch_NNNNN.tar.gz
  --original-name     首来源原始安全文件名
  --merge-manifest    merge 成员键、originalPath 和来源血缘清单
  --manifest          结构化 repair 结果
  --inspect           只检查合并目录中的请求数量
```

业务账户不是 FTP 密码，Secret 不得通过 CLI、标准输出或结果文件传递。

## 业务约束

1. 防止 tar 路径穿越、链接和非法成员；merge 对每个源包只解包一次，不生成 merged-source.tar.gz，不切分原始包。
2. repair 在私有 staging 副本修复，只有一次最终 tar/gzip 封存；缺协议文件或验证失败必须非零退出，不产空包。
3. 合法 39 位非空 requestId 保留，缺失或旧格式使用号段确定性补号并保留 originalRequestId；不同来源的同名目录以来源序号隔离成员键并保留 originalPath；目录名=requestId，requestTime 为 UTC 纳秒格式。
4. 相同输入、规则和 startNumber 必须产生一致 file_mapping 和 SHA；重试复用 Java 原 allocation。
5. repair manifest 的 artifacts 和 mappings 保留完整七字段与 originalPath/newRequestId 供复核；Java 在 delivery_task.artifacts 中只保存 dataId/sourceArtifact，不写 deliveryArtifact，metadataIds 只保存与 mapping 同顺序的 source_data.data_id。
6. merge/repair 清理本次临时目录和 .tmp，源包、merge 工作目录和有效最终包保留。
7. 新规则放在独立可测试函数中，注释说明协议输入输出和失败边界。

## 测试

```bash
python3 -m unittest nodes/python/test_archive_repair.py -v
./scripts/mvn.sh -pl delivery-worker -am test
```

单元测试覆盖三来源目录合并、无中间 tar、entityCode、requestId 保留、缺失补号、确定性、重复目录、协议失败、路径安全、10 MiB 小样本边界和临时文件清理；真实本地验收每包来源合计和最终产物均为 8–10 MiB。测试后清理 __pycache__ 和临时产物。
