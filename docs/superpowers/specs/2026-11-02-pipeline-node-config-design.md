# Pipeline 节点化配置与版本管理设计

## 背景

当前 OSS 和 FTP 连接信息来自环境变量，执行节点又把 Pipeline 定义复制到 `source_package.definition_snapshot`。这形成两套配置来源，也不便于在上线前维护节点配置。

本次将连接信息迁入 Pipeline v3 对应节点，统一通过 `pipeline_version_id` 读取版本定义。真实 OSS AK/SK 按需求直接存入 Pipeline JSON 和 PostgreSQL，不再使用 OSS/FTP 业务环境变量，也不再复制 `definition_snapshot`。

## 配置模型

Pipeline 根级配置继续保存 `crrWaitSeconds`、`autoRollover` 和 `ftpCleanBeforeDownload`。节点配置如下：

- `download`：源 OSS `endpoint`、`bucket`、`prefix`、`accessKeyId`、`accessKeySecret`。
- `hzUpload`：杭州 OSS `endpoint`、`bucket`、`accessKeyId`、`accessKeySecret` 及现有内容校验配置。
- `sgVerify`：新加坡 OSS `endpoint`、`bucket`、`accessKeyId`、`accessKeySecret` 及现有等待配置。
- `sgDownload`、`publish`：三条生产线的 `host`、`user`、`key`、可选 `jumpHost/jumpUser/jumpKey`、固定脚本和命令超时。密钥内容不进入 Pipeline，只保存容器内只读密钥路径。

类型化配置读取器负责 JSON 转换和校验，替代 `OssDeliveryProperties`、`FtpDeliveryProperties` 及源 OSS 环境属性。bucket 不允许包含 `oss://`；远程根目录和脚本保持固定；host、user、密钥路径继续使用白名单校验；`ftpCleanBeforeDownload=true` 继续拒绝上线。

## 版本生命周期

`pipeline_version.status` 使用 `DRAFT` 和 `PUBLISHED`：

1. 从指定版本复制下一个草稿，原版本不变。
2. 草稿允许修改；上线前执行完整结构、安全和连接配置校验。
3. 上线时重新计算规范化定义的 SHA-256，并将版本置为 `PUBLISHED`。
4. 已上线版本不可修改。后续变更必须复制新草稿。
5. 上线与数据源 active binding 切换在同一事务完成。既有 run、source、output 保留原 `pipeline_version_id`，新 run 使用新绑定版本。

run、source、output 继续保存 `pipeline_version_id`。数据库迁移删除 `source_package.definition_snapshot`，仓库查询改为关联 `pipeline_version.definition`。发现阶段通过 `delivery_run.pipeline_version_id` 读取 `download` 配置；source/output 执行、指定节点续跑和跨日均读取各自绑定版本。

## 维护 API

提供以下本地后端接口：

- `GET /api/v1/pipelines/{code}/versions`：版本列表。
- `GET /api/v1/pipeline-versions/{id}`：读取定义，AK/SK 始终脱敏。
- `POST /api/v1/pipelines/{code}/versions`：从指定版本创建下一个草稿。
- `PUT /api/v1/pipeline-versions/{id}/definition`：修改草稿；脱敏或省略的密钥字段保留原值，显式新值用于轮换。
- `POST /api/v1/pipeline-versions/{id}/publish`：校验、上线并切换 active binding。

API 不提供私钥内容或明文 AK/SK 读取能力。请求、异常、SQL 和节点日志不得输出完整 definition 或密钥。看板与 manifest 不展示这些连接字段。

本地初始化脚本通过 API 将真实配置写入 v3 草稿并上线。真实密钥不写入 Git 中的 Pipeline JSON、迁移 SQL 或脚本。

## 执行与部署

`OssTransferService`、源下载器、发现服务和 `SshCommandRunner` 显式接收当前节点配置，不再读取全局 OSS/FTP 环境属性。杭州、新加坡及三条 FTP 线路只能使用当前绑定版本中对应节点的配置。

Compose 删除 OSS/FTP 业务环境变量和启动时对应配置校验。Worker 无需外部凭据即可注册 PowerJob；上线校验与真实预检负责阻止不完整配置进入业务执行。

SSH 宿主机目录到容器的只读挂载属于部署机制，不作为节点业务配置来源。Pipeline 使用 `/run/secrets/line-N-id_ed25519` 容器路径。

`preflight.sh` 从 PostgreSQL 读取 active binding 的已上线版本，使用节点配置执行源 OSS 读、杭州测试写、新加坡 HEAD 和三线 SSH/ossutil 检查。预检不再 source OSS/FTP 环境变量，失败时停止，不修改交付历史文件。

## 数据迁移

新增迁移创建不含真实密钥的 v3 草稿，并删除 `definition_snapshot` 列及所有相关读写。既有 v2 定义没有连接配置，不允许把缺少节点配置的版本用于新的生产执行；本地验收前先上线完整的 v3。

此次变更新增密钥入库是有意决策。最终验收要求由“无密钥入库”调整为“密钥只存在于受控 Pipeline 版本存储，API 明文响应、日志、manifest 和看板不得泄露”。

## 测试与验收

自动化测试覆盖：

- 草稿修改、上线不可变、新版本复制、SHA 和 binding 原子切换。
- API 脱敏、保留原密钥和显式轮换。
- 无 `definition_snapshot` 后 source/output 查询与执行。
- resume/rollover 保持原版本，新 run 使用新绑定版本。
- 每个节点只使用自己的 OSS/FTP 配置，无环境变量回退。
- 配置缺失、非法路径、非固定脚本和危险 FTP 清理均被拒绝。

完成全部非生产回归后，依次执行真实 PowerJob 三 Worker 调度冒烟、一次有界预检和真实 10 包验收。只有实际 instance、三线注册和 10/10 DELIVERED 均有证据时，才勾选相应计划步骤。
