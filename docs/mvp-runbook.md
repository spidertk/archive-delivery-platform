# Archive Delivery Local Production MVP 运维手册

## 1. 事实边界

本流程由本地 PostgreSQL 唯一记录业务事实，PowerJob 只触发 discovery、package tick 和 rollover。三条生产线目标为 LINE-1/LINE-2/LINE-3 = 4/3/3，legacyBatch/provider 分别为 `3/june`、`4/june-1`、`5/june-2`。

生产路径：源 OSS → 本机下载/repair/qualify/group → 杭州 OSS → 单包 CRR 等待 → 新加坡 OSS → 跳板 SSH 调 FTP 固定脚本 → 原子发布 → 本地 PG receipt。`Local*Node` 仅用于自动化测试，不能作为真实三地验收证据。

安全约束：

- 每线源执行并发不超过 1，三线共享全局 10 个输出槽。
- 外部执行至少一次，PG 用 lease + fence 防止过期 Worker 提交。
- 一个合格源 tar 只产出一个同名输出 tar；缺协议文件进入 BLOCKED，不产空包。
- `ftpCleanBeforeDownload=false` 不可被提交参数覆盖，禁止清理 FTP 历史目录。
- 不写旧生产 PG/Redis，不执行旧流程清空目录命令，不修改 PowerJob 核心。

## 2. 安装与启动

详见 `docs/install.md` 和 `docs/deploy.md`。准备 `deploy/.env` 后：

```bash
./scripts/preflight.sh
./scripts/mvp-up.sh
./scripts/bootstrap-powerjob.sh
curl -f http://127.0.0.1:8080/actuator/health
```

预检失败（403、SSH/跳板不可达、CRR 探测对象缺失）不得启动真实 E2E。PowerJob 控制台为 `http://127.0.0.1:7700`，业务 API/看板为 `http://127.0.0.1:8080`。

## 3. 测试与不可混淆的证据

```bash
python3 -m unittest nodes/python/test_archive_repair.py -v
./scripts/mvp-test.sh
cd web && npm ci && npm run test && npm run build
```

测试覆盖真实 PG 迁移、4/3/3 规划、repair 黄金规则、断点下载、全局并发、包级重试、CRR 等待、续跑、两个跨日 case 和看板聚合。`ARCHIVE_TEST_REAL_OSS=true` 前的真实小对象测试只有在凭据齐全时执行；skipped 不是生产通过。最终验收必须同时看到 HZ OSS、SG OSS、FTP 文件和本地 PG 的 10 个 DELIVERED。

## 4. 提交历史作业日 run

测试 Pipeline v3 根级策略固定为 `crrWaitSeconds=180`、`autoRollover=false`、`ftpCleanBeforeDownload=false`；OSS/FTP 连接信息位于各自节点，不再从业务环境变量读取。提交只接受 OSS URI、idempotencyKey 和可选 jobDate，不接受任何节点或策略参数：

```bash
./scripts/submit-run.sh \
  'oss://junflow-ysai-source-hz-20260906/_derived/ysai-sftp-20261027/delivery_plan_0005/' \
  'delivery-plan-0005-local-v1' \
  '20261030'
```

重复提交相同参数返回同一 runId；同键不同 URI/jobDate 返回 409。非法 URI、日期和额外策略字段返回 400。jobDate 省略时 API 使用 Asia/Shanghai 当前业务日期。所有 process/archive/delivery 日期、repair 日期、请求编号 namespace、远端路径和 cutoff 均从 run 的 jobDate 派生。

规划先分页发现为 DISCOVERED，再按 Key/发现顺序分配三线 4/3/3。未接收对象保持 backlog，不能通过改 `current_node` 伪造进度。

## 5. 包级重试与指定节点续跑

```bash
./scripts/resume-run.sh 1 sgDownload '杭州及CRR已完成，仅继续FTP下载'
# 指定包时追加逗号分隔 packageId：
./scripts/resume-run.sh 1 receipt '只补本地回执' 101,102
```

包级 retry API：

```bash
curl -fsS -H 'Content-Type: application/json' \
  -d '{"expectedFence":7,"reason":"FTP网络恢复"}' \
  http://127.0.0.1:8080/api/v1/packages/101/retry
```

只允许从当前失败节点继续。HZ_UPLOADED + 有效 HZ 路径/相同 SHA 可从 sgVerify；SG_VERIFIED + 有效 SG 路径可从 sgDownload；PUBLISHED + 最终路径只允许 receipt。无 repair Manifest 或回退已完成节点返回 409，不触发外部请求。重试只针对当前包当前节点，不重跑 download/repair，不改变 requestId 或映射。

## 6. 单包 CRR 与 FTP 安全

每个包从自己的 `hz_uploaded_at + crrWaitSeconds` 开始首次 SG Head，早完成的包不被同批慢包阻塞。SG 不存在是 WAITING，不增加 retry_count、不占执行槽；size/SHA 不一致立即 BLOCKED，超过节点配置上限报 `CRR_WAIT_TIMEOUT`。

FTP 使用 `ossutil64 cp` 下载到本次 run 专属 staging 的 `.partial`，校验 SHA 后原子 rename，再同文件系统发布。最终文件 SHA 相同可幂等成功，不同则冲突，禁止覆盖其他 run。发布前记录历史文件相对路径、size、mtime，验收后必须完全一致。

## 7. 跨日复盘

Case A：首日 12 个对象只准入 10 个，2 个保持 DISCOVERED；次日最老 backlog 先于新对象获得容量。未完成的 PLANNED 包 process_date 不变并继续占预留。

Case B：到期 READY/RUNNING/WAITING/BLOCKED 包的 deliveryDate/cutoff/rolloverCount 前进一天，DELIVERED 不变；RUNNING 不中断，groupId/groupSeq/包名/映射/currentNode 不变，流转包优先 claim。历史 v3 测试 run 因 `autoRollover=false` 不被真实当前时间自动改写。

人工入口与自动 Processor 调用同一事务：

```bash
curl -fsS -H 'Content-Type: application/json' \
  -d '{"expectedDeliveryDate":"2026-10-30","reason":"历史作业日复盘，转入2026-10-31"}' \
  http://127.0.0.1:8080/api/v1/runs/1/rollover
```

同一 expectedDeliveryDate 重放返回 0。需要自动流转时发布 `autoRollover=true` 的新 PipelineVersion 并切换绑定；在途 run 继续读取其绑定的旧版本。测试使用可控时钟/显式业务日期，不等待真实午夜，也不修改宿主机时间。

## 8. 看板与 PG 查询

看板提供 run/date/line/rolledOver/status/keyword 筛选、六阶段进度、backlog、活跃槽位、异常列表、包详情、时间轴、retry/resume。每 15 秒刷新，失败保留上次成功数据。

```bash
docker compose -f deploy/docker-compose.yml exec -T postgres \
  psql -U archive -d archive_delivery
```

```sql
SET search_path TO archive_delivery;
SELECT production_line_id,status,run_status,current_node,count(*)
FROM source_package GROUP BY 1,2,3,4 ORDER BY 1,2,3,4;
SELECT production_line_id,archive_date,delivery_date,status,run_status,count(*)
FROM output_package GROUP BY 1,2,3,4,5 ORDER BY 1,2,3,4,5;
SELECT output_package_id,new_file_name,size_bytes,sha256,hz_path,sg_path,published_path
FROM output_artifact ORDER BY output_package_id,artifact_seq;
SELECT source_package_id,output_package_id,node_id,attempt,status,error_detail
FROM package_node_run ORDER BY id;
```

## 9. 异常处理

先停止相关 PowerJob 新 claim，再排障；不要清库从头跑。

- OSS 403：检查对应 bucket/prefix 权限和 Endpoint，保留 runId/packageId/node/错误码。
- HZ 网络/5xx：当前包进入 GROUPED + RETRY_WAIT，按 5/10/20/40/80 秒退避，第五次 BLOCKED；权限和 SHA 冲突首次即 BLOCKED。
- CRR 超 2 小时：保留 WAITING/BLOCKED 事实，确认 CRR metadata 后用 resume/retry，不重新 repair。
- SSH/跳板失败：检查 BatchMode 私钥、跳板、固定脚本与远端 `ossutil64`；恢复后只续跑失败节点。
- FTP 磁盘不足：停止对应线，保留 staging 与错误，扩容后重试当前包。
- Stale fence：旧 Worker 已过期，停止旧实例，禁止强制覆盖新租约。

最终运行 `./scripts/verify-delivery.sh <runId>`，要求 10/10、无 `.partial`、无重复 requestId、三地 SHA 一致、历史文件不变且无密钥入库。
