# 本地生产 MVP 安装指南

## 前置条件

- macOS 与 Docker Desktop（支持 Compose v2）。
- Python 3.11；本机不需要安装 Maven 或 JDK，Java 17 由 `scripts/mvn.sh` 的构建容器提供。
- Node.js 24 与 npm，仅看板构建需要。
- 可用磁盘至少为源包总量的 3 倍，并额外预留 PostgreSQL、PowerJob MySQL 和 Docker 镜像空间。工作目录包含 10 个源包、repair 解压临时文件及输出包，不能使用系统临时目录空间不足的旧默认路径。
- 源 OSS 只读凭据、HZ/SG 凭据、三条线的跳板/FTP SSH 私钥。私钥只读挂载，不能写入镜像、数据库或 Git。

## 配置

```bash
cd archive-delivery-platform
cp deploy/env.example deploy/.env
chmod 600 deploy/.env
```

`deploy/.env` 只保留数据库、PowerJob 和本地运行配置。OSS/FTP 连接信息维护在 `deploy/pipeline-v3.local.json`：`download` 配置源 OSS，`hzUpload` 配置杭州 OSS，`sgVerify` 配置新加坡 OSS，`sgDownload/publish` 配置三线 SSH 与跳板。该文件已被 Git 忽略，执行 `chmod 600 deploy/pipeline-v3.local.json`，禁止提交其中的 AK/SK。私钥内容不写入 JSON，只使用 `/run/secrets/line-N-id_ed25519` 容器路径。

## 启动与预检

```bash
./scripts/mvp-up.sh
./scripts/bootstrap-powerjob.sh
./scripts/configure-pipeline.sh deploy/pipeline-v3.local.json
./scripts/preflight.sh
curl -f http://127.0.0.1:8080/actuator/health
```

初始化脚本从最新版本复制草稿（或直接修改未上线草稿），上线后原子切换 active binding；已上线版本不可原地修改。预检从当前绑定版本读取节点配置，检查 Docker、Compose、本地 PG、源 OSS List/Head/1 字节 Range Get、HZ 测试前缀 Put/Head/Delete、SG List/Head，以及三线 SSH 和远端 `ossutil64`。任何 403、SSH/跳板失败或对象缺失都会非零退出；此时不得开始真实 10 包 E2E。

服务地址：

- 业务 API 与看板：`http://127.0.0.1:8080`
- PowerJob 控制台：`http://127.0.0.1:7700`
- 本地 PG：`jdbc:postgresql://127.0.0.1:55432/archive_delivery`，schema 为 `archive_delivery`，账号密码取 `ARCHIVE_DB_USER/PASSWORD`。
- 三个 Worker 分别绑定 LINE-1/2/3，容器内端口均为 27777，不向宿主机发布。

干净机器按以下顺序验证，无需读取旧项目密码，也无需本机安装 Maven：

```bash
cp deploy/env.example deploy/.env
# 编辑 deploy/.env 和 deploy/pipeline-v3.local.json 后：
./scripts/mvp-up.sh
./scripts/bootstrap-powerjob.sh
./scripts/configure-pipeline.sh deploy/pipeline-v3.local.json
./scripts/preflight.sh
curl -f http://127.0.0.1:8080/actuator/health
```

## 测试

```bash
python3 -m unittest nodes/python/test_archive_repair.py -v
./scripts/mvp-test.sh
cd web && npm ci && npm run test && npm run build
```

真实 OSS 写测试只有显式设置 `ARCHIVE_TEST_REAL_OSS=true` 且凭据齐全时才运行。测试 skipped 不得写成生产验收通过。
