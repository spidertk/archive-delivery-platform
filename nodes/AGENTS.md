# nodes 模块指南

## 模块职责

`nodes` 存放由 Java Worker 受监管调用的数据面节点，不是独立业务调度服务。

- `python/`：本地源 tar 安全检查、repair、请求映射、token/SHA 和产物信息。
- `shell/`：仅在确有需要时提供受控本地 FTP 下载验证辅助脚本；不再提供 SSH 跳板机中继或远程发布流程。

所有节点由 Pipeline 和 Java Worker 调用，不能自行发现任务、决定节点顺序、修改 PG 或绕过编号服务。核心链路是源 OSS 密钥直下载、本地 repair、交付 OSS 上传和本地 FTP 下载验证，不使用跳板机。

## 共同边界

1. 输入输出通过明确参数、文件和结构化结果传递，不读取业务环境变量回退配置。
2. 新增代码必须写业务注释，说明数据协议、用途、输入输出和失败边界。
3. 稳定约束包括路径穿越防护、size/SHA、staging、原子 rename、幂等和禁止清理历史目录。
4. 外部节点至少执行一次，同 URI/路径同 SHA 幂等成功，不同 SHA 必须失败，不得覆盖。
5. artifacts 只输出 artifactSeq、原始/新文件名、sizeBytes、sha256、token、file_mapping；Secret 不进入结果或 Manifest。
6. 新功能未上线按最新协议直接修改，不保留旧 run/archiveDate/productionLine 参数兼容。
7. 修改节点参数或结果 Schema 时，同步 Java NodeHandler、Pipeline Schema、部署、测试和子模块文档。
