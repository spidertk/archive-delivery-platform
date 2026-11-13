# Archive Delivery Review

## 变更内容

- [ ] NodeHandler、Repository、服务、API、前端和模块 AGENTS 已同步。
- [ ] `deploy/pipelines/*.md` 的定义和黄金用例已同步，无平行配置漂移。
- [ ] 数据库迁移、Domain、Repository 和 JSONB 白名单已同步（如涉及）。
- [ ] 未写入 AK/SK、密码、私钥或真实生产 Secret。

## 本地门禁（不访问真实 OSS/FTP）

- [ ] `./scripts/local-pipeline.sh` 通过。
- [ ] 新增节点类型已有真实生产 NodeHandler。
- [ ] `GoldenPipelineDefinitionTest` 覆盖每个 Markdown Pipeline。
- [ ] 未用 fake、skipped 或本地替身冒充生产证据。

## 生产验收

- [ ] caseId 唯一，targets 与 expected.taskCount/nodeTypes 一致。
- [ ] 生产预检和黄金 case 已执行，或明确标记为尚未执行。
- [ ] 已记录实际 deliveryId/taskId、PipelineVersion 和迁移版本。
- [ ] 已核对 SOURCE_GET、TARGET_GET、FTP_GET、SIZE_SHA、NO_PARTIAL。
- [ ] 证据目录和回退方式可供 reviewer 复核。

## 风险与回退

- [ ] 已评估外部写入、磁盘、并发、安全组和历史数据影响。
- [ ] 未使用清库、清目录、SQL 改状态或同名不同 SHA 覆盖作为修复方式。
