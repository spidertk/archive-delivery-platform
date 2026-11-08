# 本地 FTP Shell 节点指南

## 模块职责

本目录只允许提供本地 FTP 下载验证所需的固定受控辅助脚本，由 Pipeline 的 ftp.download-verify NodeHandler 以 argv 调用。核心链路从交付 OSS 下载到本地 FTP 验证目录并核对 size/SHA，不使用 SSH 跳板机、远端发布或旧回执。

## 固定参数与安全边界

辅助脚本如确需保留，只能接受受控源 URI、expectedSha、固定 stagingDir、finalName、finalDir。Secret 由 Java 当前 Pipeline 节点受控注入，不写入 argv 日志、脚本或结果。

1. staging/finalDir 必须位于 delivery_task 直属实体的授权根目录。
2. 文件名只能使用 delivery_task 中已校验且与 source_data.artifact 对齐的 newFileName，禁止路径穿越和任意绝对路径。
3. 下载先写 `.partial`，校验 size/SHA 后原子 rename；同 SHA 幂等，不同 SHA 冲突。
4. 禁止 rm -rf、递归/通配符删除、rsync --delete、下载前清目录和任意 sh -c。
5. 脚本只返回脱敏结构化结果，供 Java 写 delivery_node.output_locator 和 delivery_event，不直接写 PG。
6. 无真实脚本需求时优先 Java 受控库实现，不保留旧流程包装。

## 开发与测试

新增或修改脚本必须写业务注释，说明下载验证、SHA、原子性和历史文件保护。修改参数同步 NodeHandler、Pipeline Schema、deploy、preflight、verify 和测试。

```bash
bash -n nodes/shell/*.sh
./scripts/mvn.sh -pl delivery-core -am test
./scripts/preflight.sh
```

真实 FTP 验证只能在明确授权的本地测试账户和目录执行，并确认历史文件 path/size/mtime 不变。
