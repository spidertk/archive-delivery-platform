# jun-2 8 核 16G 生产配置发布记录

北京时间 2026-11-22 发布资源配置并恢复扩容后处于停止状态的服务。本次为资源与运行核验，没有创建批次或交付单，没有执行真实 OSS/FTP 交付或吞吐压测。

| 项目 | 调整前 | 当前 |
| --- | --- | --- |
| 同机 group1 Worker | 4 | 8 |
| 组内运行节点上限 | 3 | 8 |
| 批次 OSS 下载线程 | 8 | 16 |
| 每个 Worker/API heap | 64–256 MiB | 128–512 MiB |
| 每个 Worker PG 连接池 | 10 | 4 |
| jun-2 每轮分配容量 | 6000 | 6000 |

所有 Worker 复用原应用镜像 `sha256:0d4328fbbb34d936495eb3dafa0d51ee8fe2464f800a404cfb41a820a24a219e`，共享同机 archive_data、组内容量锁和 FTP retention 锁；租约 900 秒、gzip level=5、100 MiB 上限、成功后清理与日期保留策略不变。

发布前已确认 Flyway V25、无运行节点和未完成任务，并在服务器私有目录备份 PostgreSQL、受控配置、原镜像标识及变更文件。发布通过 Compose `up --no-build` 和正常 Pipeline API 完成，未直写任务状态或删除租约。

archive-batch v4 / ID 7 和 archive-delivery v4 / ID 8 已发布，并发声明为 8；除并发外的业务定义与原版本一致，节点 Secret 由服务端保留。原版本 ID 5/6 的 SHA 未变，已有 batchDeliveryId=3、deliveryId=4 及其 89 个任务、534 次节点执行仍成功并绑定原版本。

最终核验时，PowerJob 在线列表与八个实际 Worker 地址完全对应，每个 JVM 最大 heap 为 512 MiB、每个 Worker 有 4 个 PG 连接，PG 总连接为 48/100；API 为 UP，看板及 JS/CSS 返回 200。各容器 OOMKilled=false、RestartCount=0，过期租约和 PG 死锁均为 0。空闲状态可用内存约 10.25 GiB，swap 使用为 0，剩余磁盘约 380 GiB。

完整本地门禁覆盖 Python 23 项、Pipeline 注册 4 项、Java 30 项、前端 1 项，以及 V25 建库/重复迁移、Shell/Compose 静态检查和前端构建。真实外部验证的 3 项仅按本地模式跳过，不作为生产交付证明。最终日志见 [local-checks.log](local-checks.log)。

资源配置不能换算为吞吐提升倍数；新规格速度和 6000 包规模仍待完整真实交付验收。磁盘仍约 492 GiB，CPU/内存扩容未增加数据保留容量。

证据：[发布事实](deployment.json)、[不可变版本](pipeline-versions.json)、[最终运行配置](runtime-settings.json)、[空闲采样](resources.jsonl)。连接池调整前的脱敏快照另行保留，避免将 96/100 连接状态误认为最终配置。
