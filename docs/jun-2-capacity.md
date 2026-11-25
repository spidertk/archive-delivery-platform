# jun-2 8 核 16G 生产资源配置

2026-11-21 主机扩容后，生产配置按 8 vCPU、16 GB 内存调整，于北京时间 2026-11-22 00:01 发布。此前 2 核小内存机器的 6.28 包/分钟为历史实测，新配置的吞吐待真实交付测量。

| 配置项 | 当前值 | 用途 |
| --- | --- | --- |
| 同机 Worker | worker-1 至 worker-8，group1 | 每次广播每个 Worker 推进一个节点，八个实例提供八路执行能力 |
| ARCHIVE_MAX_RUNNING_TASKS | 8 | PG 在同组事务锁内控制运行节点总数，不是每个 Worker 各运行八个 |
| ARCHIVE_WORKER_DB_POOL_SIZE | 4 | 八个 Worker 合计最多 32 个 PG 连接，为 API、验收和运维保留连接额度 |
| ARCHIVE_WORKER_JAVA_OPTIONS | -Xms128m -Xmx512m | 每个 Worker 有界 heap，为 Python、native 内存、数据库和文件缓存保留余量 |
| ARCHIVE_SERVER_JAVA_OPTIONS | -Xms128m -Xmx512m | API 有界 heap |
| ARCHIVE_PREPARATION_DOWNLOAD_THREADS | 16 | 批次创建时的有界 OSS 下载线程池，与交付运行并发独立 |
| ARCHIVE_TASK_LEASE_SECONDS | 900 | 大文件节点租约 |
| maxPackagesPerRound | 6000 | jun-2/group1 每轮分配容量，与并发数和吞吐无等价关系 |

八个 Worker 使用相同镜像 `archive-delivery-worker:production`，共享同机 archive_data，environment 通过同一锚点继承。所有实例必须保留 worker_group、租约、交付后清理和 retention 锁目录；不得将该组跨机部署。repair 保持 gzipCompressLevel=5 和 100 MiB 上限。

## 已部署主机的资源发布

先核对资源、迁移 V25、无 RUNNING 节点及在途批次准备，再私有备份受控配置和旧镜像标识。已有 `.env` 不会被 provision 覆盖，需明确更新上表资源项。配置变更使用相同应用镜像，无须重编译业务代码；首次切换共享镜像名时，将已核对的现有 worker-1 镜像标记为 `archive-delivery-worker:production`，再执行 `up --no-build`。后续源码部署由 Compose 构建共享镜像。

Pipeline 配置仍以 `deploy/pipelines/*.md` 为唯一 review 来源，concurrency.maxRunningTasks=8 通过正常 API 发布新的不可变版本；历史交付单绑定的版本保持不变。资源调整不创建批次或交付单，不触发黄金脚本的提交阶段，不重复执行历史清理。

发布后核对八个 Worker 应用启动并连接 PowerJob、API 健康、看板与静态资源、容器实际环境、OOM/重启和 PG 任务事实，记录脱敏证据。真实交付时用 observe-delivery.py 观察运行节点数、CPU、available/swap、节点耗时和磁盘；吞吐及 6000 包验收须按完整外部验证单独记录。

当前磁盘未随 CPU/内存一并扩容：根盘约 492 GiB，本次调整前剩余约 381 GiB。此前按样本估算的 6000 个交付包约 1.25 TB 数据保留需求仍然适用，8 核 16G 配置不能替代磁盘容量检查。

## 本次发布核验

archive-batch v4（PipelineVersion ID 7）和 archive-delivery v4（ID 8）已通过正常 API 发布，除并发声明外与原版本的业务配置一致，Secret 由服务端从草稿基线保留。历史版本 ID 5/6 的 SHA 未改变，交付单 3/4 仍绑定原版本。

八个 Worker 均已在 PowerJob 在线，实际 JVM 最大 heap 为 512 MiB，各占 4 个 PG 连接；连接池限制发布后 PG 总连接从 96 降为 48/100。API、看板和两个静态资源均正常，核验时可用内存约 10.25 GiB、swap 使用为 0，所有容器无 OOM 或重启。这些为空闲状态核验，不是八路交付负载结果。

本地门禁包括 Python 23 项、注册 4 项、Java 30 项、前端 1 项、V25 新建与重复迁移、Shell/Compose 检查和前端构建，全部通过；真实外部验证的 3 项按本地模式跳过，未作为生产交付证明。本次没有创建新批次或交付单，没有做吞吐压测。脱敏发布和运行核验见 [evidence/JUN2-20261122-8C16G/](evidence/JUN2-20261122-8C16G/)。
