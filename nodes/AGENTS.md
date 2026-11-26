# nodes 模块指南

## 模块职责

`nodes` 存放由 Java Worker 受监管调用的数据面节点，不是独立业务调度服务。

- `python/`：源 tar 安全解包、请求目录合并、repair、最终封包、token/SHA 和 FTP 验证。
- `shell/`：仅必要的受控本地辅助脚本；禁止 SSH 跳板机中继。

所有节点由 Pipeline 和 Java Worker 调用，不能自行发现任务、决定顺序、修改 PG 或改变来源请求 ID。核心链路是源 OSS 直下载、本地目录合并、一次修复封包、交付 OSS 上传和本地 FTP 下载验证。

## 共同边界

1. 输入输出通过明确参数、文件和结构化结果传递，不读取业务环境变量回退配置。
2. 新代码写业务注释，说明协议、输入输出和失败边界。
3. 稳定约束包括路径穿越防护、size/SHA、staging、原子 rename、幂等和禁止清理历史目录。
4. 外部节点至少执行一次，同 URI/路径同 SHA 幂等成功，不同 SHA 失败，不得覆盖。
5. artifacts 只输出七字段；Secret 不进入结果或 Manifest。
6. 修改参数或结果 Schema 时同步 Java NodeHandler、Pipeline Schema、部署、测试和文档。

## 合并节点

archive_merge.py 按 delivery_batch.source_seq 解包并汇聚请求目录，输出 workspacePath；不再生成中间 tar/gzip。archive_repair.py 直接消费该目录并只封存最终交付包一次。merge/repair 临时目录和 .tmp 必须在重试前及异常后清理，日志不得包含 Secret。

Java 中断或超时必须回收数据面进程子树并有界等待退出；repair 使用任务私有 `.repair.lock` 串行化旧尝试与重试，持锁后才能清理 staging/.tmp 或写最终包。锁文件不作为缓存删除，业务内容、目录和 ID 的保留约束不变。

## 授权日期保留例外

仅 FTP 节点显式 cleanupOtherDeliveryDates=true 时按任务日期保留直属 FTP 日期目录，使用同机文件锁等待传输边界；不清理非日期目录、OSS、PG 或工作目录。默认禁止清历史的边界继续适用于未启用此策略的节点。

merge 通过 gzip/TAR 流完整读取拼接 gzip 成员和拼接 TAR 段，只跨越合法结束零块，坏头、截断和非请求顶层文件必须失败；每个源包只解包一次。最终协议校验按 TAR 流建立直属文件与 metadata 索引，禁止 getmembers 后逐请求 gzip 回卷。保留独立的原始目录名和 requestId/request_id 字段及值；requestTime 保留源日期和时分秒，格式为 `yyyy-MM-dd HH:mm:ss`。缺失或冲突 ID、重复目录、非法时间、空包、重复文件、路径穿越和链接均拒绝交付。

生产独立 FTP `--verify-only` 流式读取真实远端字节并核对 size/SHA，不产生第二份本地包；正常交付节点仍执行 STOR/RETR 和本地原子封存。

FTP 用任务分配的渠道文件名上传和回验。容量和日期清理由实体专用根与同机 retention 锁约束。
