# Python repair 节点指南

## 模块职责

本目录实现本地源 tar 的安全解包、请求目录合并、数据修复和最终封包。Python 不直接访问 PG，不决定目标分配、流程顺序或交付状态；Java 负责 delivery_batch、任务 facts 和 delivery_node 提交。repair 不分配编号或改写来源请求 ID。

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
  requestId 不生成号段；保留来源 ID，缺失或冲突直接失败
  --max-bytes         最终修复包上限
  --gzip-level        最终 gzip 压缩级别 1–9，默认 9；由不可变 Pipeline 固定
  --output-name       当前任务在 delivery 内唯一的 batch_NNNNNNN.tar.gz
  --original-name     首来源原始安全文件名
  --merge-manifest    merge 成员键、originalPath 和来源血缘清单
  --manifest          结构化 repair 结果
  --inspect           只检查合并目录中的请求数量
```

业务账户不是 FTP 密码，Secret 不得通过 CLI、标准输出或结果文件传递。

Worker 镜像将本目录复制到 `/app/nodes/python/`，本地和生产 Compose 均把本目录整目录只读挂载到同一路径。package.merge、python.repair、ftp.download-verify 的 Pipeline `config.scriptPath` 只写 archive_merge.py、archive_repair.py、ftp_download_verify.py 等目录内相对路径，不支持容器或宿主机绝对路径。发布时检查脚本存在并拒绝目录穿越和非 .py 文件，Worker 执行前再次核对并以固定 argv 启动，不接受 Shell。新增脚本放入本目录即可被所有同机 Worker 使用，无需修改挂载或重启；生产建议使用版本化文件名并发布新 PipelineVersion，避免覆盖同名文件影响旧任务重试。

## 业务约束

1. 防止 tar 路径穿越、链接和非法成员；merge 对每个源包只解包一次，不生成 merged-source.tar.gz，不切分原始包。
2. repair 在私有 staging 副本修复，只有一次最终 tar/gzip 封存；缺协议文件或验证失败必须非零退出，不产空包。
   同一任务的 repair 全程持有目标目录 `.repair.lock` 排他锁，拿锁后才清理旧 staging 或 `.tmp`、修复与封包；等待旧进程退出后重试，锁文件不删除，进程结束由内核释放。禁止两个尝试并发清理同一任务工作区。
3. 原始 requestId/request_id 字段和值与请求目录名分别保持来源事实（支持 UUID 等真实格式），不要求目录等于 ID；缺失、冲突或重复 ID/目录直接失败，不生成替代 ID 或字段别名。requestMode=null 时按 biz_request.stream 布尔值或唯一实际响应文件确定性推导 STREAM/SYNC 并写回；拼接 gzip/TAR 必须读取全部成员，仅允许跳过结束零块，坏头、截断和非请求顶层文件直接失败；requestTime 校验源日历和时钟，保留源日期并规范为 `yyyy-MM-dd HH:mm:ss`。
4. 相同输入、规则和原始 requestId 必须产生一致 file_mapping 和 SHA；重试复用来源事实，不分配替代号段。
5. repair manifest 的 artifacts 和 mappings 保留完整七字段与 originalPath/requestId 供复核；timings 分别记录 copyWorkspace、repairRequests、gzipPack、verifyArtifact、sha256、cleanup 和 total 秒数，Java 同步写入节点 output_locator.timings。Java 在 delivery_task.artifacts 中只保存 dataId/sourceArtifact，来源顺序由 delivery_batch.source_seq 维护，不再保存 metadataIds。
6. merge/repair 清理本次临时目录和 .tmp。本地默认保留源包、merge 工作目录和有效最终包；生产可在 FTP size/SHA 复核后清理任务大体积中间副本，节点默认保留 merge/repair manifest、source-cache 和 FTP 回验文件。经授权的全量生产入口可在独立外部验收后按事实 size/SHA 白名单回收本提交 FTP 回验副本及源缓存；显式 PROD_EVICT_VERIFIED_SOURCE_CACHE=true 可忽略旧批次未消费规划引用，仍保护本批次未交付来源与所有已建未完成任务引用，删除前复查，保留 manifest、最终 OSS/FTP 和回收证据。节点自身不扩大清理范围。
7. 新规则放在独立可测试函数中，注释说明协议输入输出和失败边界。

## 测试

```bash
python3 -m unittest nodes/python/test_archive_repair.py -v
./scripts/maven-test.sh -pl delivery-worker -am test
```

单元测试覆盖三来源目录合并、多 gzip 成员和单 gzip 内拼接 TAR 完整读取、后段坏头/截断拒绝、原目录与 UUID/蛇形 ID 独立保留、正文逐字节不变、无中间 tar、entityCode、秒级原日期、缺失/冲突 ID 和非法时间拒绝、确定性、重复目录、协议失败、路径安全、10 MiB 小样本上限、分阶段计时和临时文件清理；合并不设置包下限。测试后清理 __pycache__ 和临时产物。

并发重试测试通过真实文件锁阻止两个 repair 子进程提前清理旧 staging，解锁后顺序完成且成品 SHA 相同；Java ProcessSupportTest 使用真实 Python 子进程验证中断/超时后不遗留写入者。

## FTP 日期保留与一次下载

ftp_download_verify.py 的固定 stdin 契约新增 cleanupOtherDeliveryDates(boolean)、deliveryDate(ISO 日期)、retentionRoot(同组共享本地锁目录)。ftp_retention.py 只删除直属 FTP 根下其他合法日期目录；日期切换取独占锁，传输持共享锁，同日每次传输仍扫描，后出现的其他日期目录也必须清理，旧日期回退失败。MLSD 未知类型或链接拒绝清理，FTP 根禁止部署符号链接。非日期目录始终保留。首次上传先 STOR .partial，唯一一次 RETR 同时校验远端和本地，成功才原子封存；同名不同 SHA 拒绝覆盖。

验证：`PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s nodes/python -p "test_*.py" -v`，包括日期清理、跨日期在途锁、旧日期重试、SHA 冲突、链接及中断清理。

独立外部验收必须调用 FTP 脚本 `--verify-only`；目标缺失或 SHA 不符立即失败，不允许补传文件后宣告通过。
该模式完整 RETR 并在内存累计 size/SHA，不落第二份回验包，也不输出不存在的本地下载产物；节点正常上传下载流程继续保留已核验本地副本。

2026-11-21 性能优化：merge 使用 gzip/TAR 流完整读取拼接成员；最终协议校验按 TAR 流建立直属文件与 metadata 索引，单次解压，禁止 getmembers 后逐请求 gzip 回卷。完整保留原始 requestId、源日期秒级 requestTime、providerCode、模式及必需文件检查，并拒绝空包、重复成员、路径穿越和链接。
