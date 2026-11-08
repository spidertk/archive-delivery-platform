# Archive Delivery Platform 实体目标升级版方案设计

> 修订日期：2026-11-08
> 状态：当前唯一实施基线
> 配套模型：`2026-11-06-archive-delivery-model-upgrade-design.md`
> 数据策略：不兼容、不回填老数据；V14 清空重建，V15/V16/V17 为当前增量

## 1. 目标与决策

本平台由 Pipeline 定义通用归档数据交付流程。PostgreSQL 是业务事实源，PowerJob 只触发通用入口，Worker 按提交绑定的不可变 PipelineVersion 推进节点。

1. 核心血缘为 `delivery_submit → source_data → delivery_mapping → delivery_task → delivery_node`。
2. 提交直接使用原始 ossUri、pipelineCode、deliveryDate、deliveryBatch、cutoffAt 和 vendorCode+entityCode targets，不暴露来源、版本或实体数据库 ID；同厂商多个实体整体分配，repair 接收 entityCode。
3. deliveryBatch 全局唯一，替代独立 idempotencyKey，并作为请求编号命名空间。
4. source_data 保存 original_file_name 和单个原始 artifact；任务以来源/交付产物对 artifacts[] 和 metadata requestId 列表保存完整溯源，sourceArtifact 必须与 source_data.artifact 完全一致。
5. 当前链路为源 OSS 直下载并保留 → 本地目录合并 → 本地 repair/一次封包 → qualify → OSS SDK 上传并 GET 回验 → FTP 上传并重新下载验证。
6. 不使用 SSH 跳板机、CRR、新加坡中转、远端 SSH 发布或旧回执链；新协议只新增 Pipeline 节点。
7. 支持节点边界暂停；暂停后以相同 deliveryBatch 和完全相同参数再次提交即可继续。
8. 截止未完成任务生成下一交付日新 taskId，完整映射与产物对不变，新旧关系写不可变事件。

## 2. 组件与边界

| 组件 | 负责 | 不负责 |
|---|---|---|
| delivery-server | 业务编码提交、查询、Pipeline/实体管理、pause/retry/resume/rollover | 直接执行外部节点 |
| PowerJob | 提交准备、任务推进、截止扫描触发 | 判断交付成功或定义 DAG |
| delivery-worker | 有界 claim、lease/fence、NodeHandler 调用 | 写死固定交付链 |
| PipelineEngine | 解析同版 DAG、选择处理器、校验后继 | 隐式增加节点 |
| Python | 安全解包、目录合并、repair、最终封包、FTP 下载与 SHA | 直接写 PG 或决定状态 |
| PostgreSQL | 提交、映射、任务、节点、事件和并发事实 | 暴露 Secret |

PowerJob 实例成功不等于交付成功。只有最终节点成功且外部 size/SHA 核验完成，任务才 SUCCEEDED；有效任务满足目标数量且全部成功，提交才 DELIVERED。

## 3. 集中 PipelineVersion 与优化后的 DAG

definition 集中 nodeDefinitions、ruleSets、nodes、selection、assignment、retry、concurrency、rollover 和节点 Secret。PUBLISHED 不可改，换配置发布新版本。

```text
sourceDownload → mergePackages → repair → qualify → targetUpload → ftpDownloadVerify
```

```yaml
config:
  businessTimezone: Asia/Shanghai
  deliveryCutoffTime: "18:00:00"
  rolloverStartNode: targetUpload
  selection: {orderBy: key, limit: 3, duplicatePolicy: FILTER_WITH_REASON}
  assignment: {strategySource: SUBMIT_TARGETS, maxPackageBytes: 10485760}
  concurrency: {maxRunningTasks: 3}
  retry: {maxAttempts: 5}
```

selection.limit 是扫描入选来源包数上限，targetCount 是合并后的任务数。发现阶段在下载前过滤单包超过 maxPackageBytes 的对象并记录 SOURCE_FILTERED，避免无效大流量。规划器按 vendorCode 将目标分组，在同一厂商内按 entityCode 顺序整体装包，为后续目标保留至少一个来源；当前包无法容纳下一完整源包时封包，最后目标满足后忽略后续来源，禁止切分文件。来源不足以形成全部目标时规划失败并置主单 BLOCKED，不再每 15 秒重复下载。

例如上限 10 MiB，来源依次为 1/2/3/4/12/1/5/3/2 MiB：12 MiB 下载前过滤；首任务为 1+2+3+4=10 MiB，第二任务为 1+5+3=9 MiB，末尾 2 MiB 不再纳入。包名按 delivery_id 跨实体连续生成 batch_00001、batch_00002。

### 3.1 合并/修复性能约束

旧实现为：merge 解包所有来源并生成 merged-source.tar.gz，repair 再解包该 tar 并生成最终 tar.gz，存在额外一次完整 gzip 压缩、一次完整 gzip 解压和中间文件 IO。

当前实现消除该中间包：

1. merge 按 delivery_mapping.source_seq 对每个来源只执行一次安全解包，把请求目录移动到任务受控 `merge/requests/`。不同来源的同名目录以来源序号生成唯一成员键，manifest 同时保留 memberKey、originalPath、sourceSeq 和 sourceFileName。
2. merge 输出 locator 使用 `workspacePath/manifestPath/requestCount/sourceCount/sourceBytes`，不再输出 artifactPath 或中间包 SHA。
3. repair 直接读取 merge 请求目录，在 repair 私有 staging 副本上修改 metadata 和目录名，避免失败污染 merge 结果。CLI 接收 `--entity-code`，协议 metadata.providerCode 写 entityCode；vendorCode 仅用于厂商分配和外部路径。合法 39 位 requestId 保留，缺失或旧格式使用 deliveryBatch 号段确定性补号，originalRequestId 始终保留。
4. 只有 repair 最终执行一次确定性 tar/gzip 封存，并检查 maxPackageBytes、协议结构、size/SHA。
5. 重试清理本次 `.archive_merge_*`、`.archive_repair_*` 和 `.tmp`；源包、merge 目录和有效最终包保留。

因此仍有一次必要的 staging 文件复制，但不再有重复压缩/解压；最终包大小约束没有被跳过。

## 4. 提交与操作 API

```text
POST /api/v1/deliveries
GET  /api/v1/deliveries/{deliveryId}
POST /api/v1/deliveries/{deliveryId}/pause
POST /api/v1/deliveries/{deliveryId}/resume
POST /api/v1/deliveries/{deliveryId}/rollover
GET  /api/v1/deliveries/{deliveryId}/tasks
POST /api/v1/tasks/{taskId}/retry
GET  /api/v1/tasks/{taskId}/timeline
GET  /api/v1/dashboard/targets?deliveryId={deliveryId}
```

提交请求只含以下字段，Jackson 拒绝未知字段：

```json
{
  "ossUri": "oss://source-bucket/archive/input/",
  "pipelineCode": "archive-delivery",
  "deliveryDate": "2026-11-09",
  "deliveryBatch": "BATCH-20261109-001",
  "cutoffAt": "2026-11-09T18:00:00+08:00",
  "targets": [
    {"targetCount": 1, "vendorCode": "alibaba", "entityCode": "jun"},
    {"targetCount": 1, "vendorCode": "alibaba", "entityCode": "jun2"}
  ]
}
```

服务端自动登记或解析 ossUri，选择该 pipelineCode 最新 PUBLISHED 版本，校验 vendorCode+entityCode 联合唯一、启用、协议支持和显式 cutoffAt 在未来。来源 ID、版本 ID、实体 ID、idempotencyKey、workerGroup、Secret 和节点列表均不是提交入参。

### 4.1 幂等、暂停与继续

- deliveryBatch 是全局唯一幂等键。规范化 ossUri/pipelineCode/deliveryDate/cutoffAt/targets 完全一致时返回原 deliveryId，不同参数返回 409。
- `POST /pause` 只接收 reason。暂停是节点边界操作，不强杀外部 IO；存在 RUNNING 任务时返回 409，调用方在当前节点完成或失败后重试。
- 可暂停任务转为 PAUSED，Worker 不再 claim；主单投影 PAUSED。
- 对暂停主单再次 POST 完全相同提交，将所有 PAUSED 任务改为 READY，从各自 current_node_id 继续，返回原 deliveryId。
- 原有 resume 仍可按 DAG/fromNode/taskIds 操作；retry 必须携带 expectedFence。

## 5. 执行、产物与本地文件

准备阶段真实发现候选对象，先按大小和 targets 计算入选集合，再只下载入选来源并将每个 source_data 独立入库。缓存按 sourceUri+sourceVersion 定位到同机 `work/source-cache/{identityHash}/{originalFileName}`，已存在且 size/SHA 匹配时不重复 OSS GET。sourceDownload 优先把该缓存校验复制到任务目录并记录 cacheHit=true；其他服务器执行组缓存不存在时回退 OSS。每个任务写入 delivery_mapping 和 artifacts 初始对；初始 deliveryArtifact 为 null，任务 size/token 等于原始来源合计。

repair 成功后：

- 每个 dataId 对应一对 sourceArtifact/deliveryArtifact，sourceArtifact 完全等于 source_data.artifact，同一任务的交付包对象写入各对。
- metadata_ids 保存修复后所有请求目录的 requestId，可定位 `<requestId>/metadata.json`，并与逐来源 file_mapping 的 requestId 全集一致。
- source_data 只更新 qualification/rule_version/request_namespace/updated_at，原始 original_file_name/size/SHA/artifact 不变。
- delivery_task.size_bytes/token_count 等于合并 deliveryArtifact 对应值，有效任务的 new_file_name 在同一 delivery_id 下唯一。
- delivery_node 保存 merge workspace、repair entityCode、qualify、OSS 和 FTP 的 size/SHA 与路径事实。

共享源缓存保存在 `work/source-cache/{identityHash}`；任务文件保存在 `work/delivery-{deliveryId}/task-{taskId}`。FTP 下载保存在受控 ftp-verify 根目录。所有外部下载使用 `.partial` 和原子 rename；不得清空历史目录或覆盖同名不同 SHA 文件。

worker_group 是单服务器本地执行域。Worker 只认领同组任务，组内共享本机 /data，跨服务器不共享目录；各组独立计算最大 RUNNING 数。

## 6. 状态、重试与跨日

任务状态：`READY/RUNNING/WAITING/RETRY_WAIT/BLOCKED/PAUSED/SUCCEEDED/EXPIRED/CANCELLED`。临时网络/IO 有界退避；权限、配置、路径或 SHA 冲突进入 BLOCKED。

跨日事务关闭旧任务活动节点，将旧任务置 EXPIRED，并创建指定下一交付日的新 taskId：

- 不变：delivery_id、entity/entity_code、worker_group、new_file_name、artifacts、metadata_ids、完整 delivery_mapping。
- 更新：delivery_date、cutoff_at、current_node_id=rolloverStartNode、retryCount=0。
- 血缘：TASK_EXPIRED/TASK_REGENERATED payload 保存 oldTaskId/newTaskId、日期和原因，不使用 superseded_by_task_id。
- 配额：新任务占下一交付日实体目标配额，旧 EXPIRED 不重复计数。
- 主单：未全部成功前 PARTIALLY_DELIVERED，全部成功后 DELIVERED。

## 7. 看板与时间轴

看板按 target_strategy 的 vendorCode+entityCode 聚合 targetCount、assignedCount、completedCount 和派生状态。任务展示 entityCode、vendorCode、workerGroup、new_file_name、sourceDataIds、artifacts 产物对、metadataIds、日期和动态阶段，不展示已删除任务列。

时间轴返回 nodes 与 events；processId 标识节点尝试。跨日时间轴通过事件中的 oldTaskId/newTaskId 追查，EXPIRED 仅作历史。

## 8. 安全与迁移

Secret 只在 Pipeline 对应节点维护；管理 API 脱敏，Worker 只获得当前节点配置。Secret 不进入实体、locator、artifacts、metadata_ids、事件、日志或前端。外部 IO、业务决策和事务提交分离，NodeHandler 不直接更新任务或提交状态。

V14 只在显式授权环境清空旧业务表；V15 增加 PARTIALLY_DELIVERED；V16 形成 delivery_mapping 和任务产物对；V17 形成 entityCode 提交/repair、source_data 单原始 artifact、original_file_name、来源时间戳、任务 entity_code 和 delivery 内有效包名唯一约束。普通启动不得隐式携带清库授权。

## 9. 验证与验收

标准回归：Python unittest、全部 Maven 测试、前端 Vitest/build、Shell 语法和 Compose 配置。模型集成测试验证废弃对象、全表全列注释、source_data 单 artifact、来源时间戳、任务来源产物一致性、包名唯一、事件不可变、复合业务键与 cutoffAt 提交、暂停再提交、请求编号外键和三实体各两个任务的跨日状态。节点测试证明缓存命中时不调用 OSS 下载服务。

本地真实核心 case 固定为：

1. 真实 Compose 启动 server/Worker/PG/PowerJob/FTP，执行实际 V1–V17 迁移。
2. 从 `oss://sg-storage-20261013-6vmlk/internal/gpt-delta-seven-20261105-accepted-20261106-v1/products/` 真实 GET 三份 tar.gz，本地保留并逐源核对 original_file_name/size/SHA/artifact。
3. 提交显式 cutoffAt，同 vendorCode=alibaba 的 jun/jun2 各 targetCount=1，三份来源按每包 10 MiB 上限整体装包形成两个任务，repair 输入为 entityCode。merge 保留请求目录且无 merged-source.tar.gz，repair/qualify 后各包大于 0 且不超过 10 MiB。
4. 三份来源各分配一次，sourceArtifact 完全等于 source_data.artifact，file_mapping 仅含本来源 originalPath，metadata_ids 与各 merge requestCount 一致，任务 size/token 与合并包一致，有效包名在 delivery_id 下唯一，request_namespace 等于 deliveryBatch。
5. 项目 OSS SDK 实际两件上传并 GET 回验；两个本地 FTP 账户分别上传并重新下载一件产物，size/SHA 与数据库一致且无 .partial。
6. 同一 case 实际覆盖节点边界暂停、相同批次再提交继续、跨日新 taskId 和单任务 retry；再以 local-other/jun3 复用相同来源形成第二个交付，所有 sourceDownload.cacheHit=true 且无重复源 OSS GET；两个主单最终均 DELIVERED。

三实体各两个任务的 DELIVERED → PARTIALLY_DELIVERED → 次日 DELIVERED 状态序列由小样本模型集成 case 覆盖；生产真实验收仍按生产 targets 和真实目标执行。skipped、fake、Local Node、PowerJob 绿色实例、仅 API 成功或历史记录不能作为真实通过证据。验收记录必须给出实际 deliveryId/taskId、迁移版本、启动日志、文件清单和 SHA 自助复核命令。
