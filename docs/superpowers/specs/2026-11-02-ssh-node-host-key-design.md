# SSH 节点主机公钥配置设计

## 背景

真实 PowerJob 验收已通过 OSS 下载、修复、杭州上传和新加坡核验，但 Worker 容器执行 `sgDownload` 时因没有目标 FTP 与杭州跳板机的受信主机公钥而失败。修复必须保留严格主机校验，不引入业务环境变量或宿主机隐式状态。

## 配置模型

在 Pipeline `publish` 节点的每条 `FtpNodeConfig.Line` 中新增：

- `hostKey`：目标 FTP 的标准 OpenSSH `known_hosts` 公钥行，必填。
- `jumpHostKey`：杭州跳板机的公钥行；配置 `jumpHost` 时必填。

节点定义保存在既有版本表中。主机公钥不是认证私钥，API 可按普通节点配置返回；既有私钥脱敏规则保持不变。上线校验拒绝空值、多行、非法 key type、缺少 base64 公钥或包含控制字符的值。

## SSH 执行

`SshCommandRunner` 为每次调用创建仅当前用户可写的临时目录和 `known_hosts`，写入该线跳板机公钥。Worker 只连接跳板机并调用固定 `ftp_exec.sh`；中继为 FTP 连接创建独立临时 `known_hosts`，写入目标公钥。Worker 与跳板机上的目标 SSH 均显式使用：

```text
-o UserKnownHostsFile=<临时文件>
-o StrictHostKeyChecking=yes
```

Worker 只使用该线跳板机私钥；FTP 目标连接只使用跳板机 `$HOME/.ssh/archive-delivery-target-id_ed25519`，不再向 Worker 分发目标私钥。命令结束或异常后删除临时文件。禁止 `StrictHostKeyChecking=no`、`accept-new` 和全局环境变量回退。

## 安全与故障行为

主机不在受信列表、公钥不匹配、字段缺失或格式非法时，调用必须失败，不得退化为交互确认或不校验。远端包冲突仍按 `RemoteConflictException` 阻断，不覆盖既有对象。临时文件只含公开主机密钥，不写日志。

## 验证

1. 单元测试覆盖每线公钥解析、缺失及非法字段、Worker 到跳板及跳板到目标的严格校验参数和临时文件清理。
2. 真实预检覆盖三线目标与跳板机，确认容器内不再出现 `Host key verification failed`，且本地无需目标私钥。
3. 继续当前三线各一包、包级重试、指定节点续跑、跨日看板和全量非生产回归。
4. 对远端同名不同内容冲突只读取证据；未获明确许可前不删除或覆盖远端对象。
