# nodes 模块指南

## 模块职责

`nodes` 存放由 Java Worker 受监管调用的数据面节点，不是独立业务调度服务。

- `python/`：源 tar 安全解包、请求目录合并、repair、最终封包、token/SHA 和 FTP 验证。
- `shell/`：仅必要的受控本地辅助脚本；禁止 SSH 跳板机中继。

所有节点由 Pipeline 和 Java Worker 调用，不能自行发现任务、决定顺序、修改 PG 或绕过编号服务。核心链路是源 OSS 直下载、本地目录合并、一次修复封包、交付 OSS 上传和本地 FTP 下载验证。

## 共同边界

1. 输入输出通过明确参数、文件和结构化结果传递，不读取业务环境变量回退配置。
2. 新代码写业务注释，说明协议、输入输出和失败边界。
3. 稳定约束包括路径穿越防护、size/SHA、staging、原子 rename、幂等和禁止清理历史目录。
4. 外部节点至少执行一次，同 URI/路径同 SHA 幂等成功，不同 SHA 失败，不得覆盖。
5. artifacts 只输出七字段；Secret 不进入结果或 Manifest。
6. 修改参数或结果 Schema 时同步 Java NodeHandler、Pipeline Schema、部署、测试和文档。

## 合并节点

archive_merge.py 按 delivery_mapping.source_seq 解包并汇聚请求目录，输出 workspacePath；不再生成中间 tar/gzip。archive_repair.py 直接消费该目录并只封存最终交付包一次。merge/repair 临时目录和 .tmp 必须在重试前及异常后清理，日志不得包含 Secret。
