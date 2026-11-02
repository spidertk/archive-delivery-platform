# SSH Node Host Key Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让三条 Worker 仅通过 Pipeline 节点中的受信主机公钥完成严格 SSH 校验，并继续真实 MVP 全部验收。

**Architecture:** 在 `FtpNodeConfig.Line` 保存目标和跳板机公钥；`SshCommandRunner` 为每次调用创建私有临时 `known_hosts`，目标和 ProxyCommand 均强制严格校验并在 finally 清理。本地真实配置由已验证的宿主机 `known_hosts` 补齐，经现有草稿/上线 API 发布新版本。

**Tech Stack:** Java 17、Jackson、JUnit 5、OpenSSH、Docker Compose、现有 Pipeline API 与 shell 验收脚本。

**当前进度：** Task 1–3 全部完成。Worker 非 root 工作目录、真实公钥导入、Pipeline v4 发布、目标和跳板机严格 SSH 校验均已生效；真实 10 包、故障重试、指定节点续跑及跨日 case 全部通过。run 6 已通过 HZ/SG/FTP 全量复核和 5,999 个历史 FTP 文件基线比较，最终 Maven 全量回归、敏感信息、shell 语法和差异检查通过，所有步骤已按证据勾选。

---

### Task 1: 节点公钥模型与上线校验

**Files:**
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/pipeline/FtpNodeConfig.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/pipeline/PipelineNodeConfigTest.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/pipeline/ProductionPipelineTest.java`
- Test: `delivery-server/src/test/java/com/powerjob/archive/server/api/PipelineControllerTest.java`

- [x] **Step 1: 写节点公钥缺失和非法格式的失败测试**

在 `PipelineNodeConfigTest` 的三线夹具加入合法公钥，再增加独立用例：

```java
@Test
void validatesSshHostPublicKeys() {
    String valid = "sg-1 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBc2sS5hVY5+Z7sF4N0Zt6D0gL9R7F4m3P9vH2yQ6bJ8kL1n";
    assertThat(FtpNodeConfig.validateHostKey(valid)).isSameAs(valid);
    for (String invalid : new String[]{null, "", "sg-1 ssh-ed25519", "sg-1 not-a-key AAAA",
            "sg-1 ssh-ed25519 AAAA\nother ssh-ed25519 AAAA"}) {
        assertThatThrownBy(() -> FtpNodeConfig.validateHostKey(invalid))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

实现测试时可使用符合 Base64 的固定伪公钥，不使用真实私钥或真实公钥作为断言秘密。

- [x] **Step 2: 运行测试确认失败**

Run: `./scripts/mvn.sh -pl delivery-core -Dtest=PipelineNodeConfigTest test`

Expected: 编译失败，提示 `validateHostKey` 尚不存在。

- [x] **Step 3: 扩展 Line 并实现最小校验**

把记录签名改为：

```java
public record Line(String host, String user, String key, String jumpHost, String jumpUser, String jumpKey,
                   String hostKey, String jumpHostKey, String remoteRoot,
                   String downloadScript, String publishScript) {
```

在 `validate()` 中调用：

```java
validateHostKey(hostKey);
if (!blank(jumpHost)) {
    if (blank(jumpUser) || blank(jumpKey)) {
        throw new IllegalArgumentException("FTP jumpUser/jumpKey are required with jumpHost");
    }
    validateHostKey(jumpHostKey);
}
```

`validateHostKey` 要求单行三个空白分隔字段，主机字段非空，key type 以 `ssh-`、`ecdsa-` 或 `sk-` 开头，第三字段可由 `Base64.getDecoder().decode` 解码；任何异常统一抛 `IllegalArgumentException("Invalid SSH host public key")`。

- [x] **Step 4: 更新全部测试夹具并跑 core/server 定向回归**

为 `ProductionPipelineTest`、`PipelineControllerTest` 和 `PipelineNodeConfigTest` 的三线配置添加相同伪公钥字段。运行：

```bash
./scripts/mvn.sh -pl delivery-core -Dtest=PipelineNodeConfigTest,ProductionPipelineTest test
./scripts/mvn.sh -pl delivery-server -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=PipelineControllerTest test
```

Expected: 全部 PASS，草稿上线拒绝缺失公钥。

- [x] **Step 5: 提交节点模型**

```bash
git add delivery-core delivery-server && git commit -m "feat: configure ssh host keys on pipeline nodes"
```

### Task 2: 每次 SSH 调用的严格临时 known_hosts

**Files:**
- Modify: `delivery-core/src/main/java/com/powerjob/archive/core/service/SshCommandRunner.java`
- Test: `delivery-core/src/test/java/com/powerjob/archive/core/service/SshCommandRunnerTest.java`

- [x] **Step 1: 写目标与 ProxyCommand 参数及清理的失败测试**

让 fake SSH 在退出前复制 `UserKnownHostsFile` 内容和参数到测试临时目录。断言：

```java
assertThat(argv).contains("UserKnownHostsFile=", "StrictHostKeyChecking=yes", "IdentitiesOnly=yes");
assertThat(capturedKnownHosts).containsExactly(line().hostKey(), line().jumpHostKey());
assertThat(knownHostsPath).doesNotExist();
```

`line()` 改为同时提供 `hostKey`、`jumpHostKey` 和跳板机字段。另保留现有参数安全、超时及固定脚本测试。

- [x] **Step 2: 运行测试确认当前实现失败**

Run: `./scripts/mvn.sh -pl delivery-core -Dtest=SshCommandRunnerTest test`

Expected: FAIL，当前参数不含严格主机校验且新构造器字段不匹配。

- [x] **Step 3: 实现临时文件和 finally 清理**

在 `run` 中创建权限 `rw-------` 的临时 `known_hosts`，内容为目标公钥行，使用跳板机时追加跳板机公钥行。目标参数追加：

```java
command.add("-o");
command.add("UserKnownHostsFile=" + knownHosts);
command.add("-o");
command.add("StrictHostKeyChecking=yes");
```

ProxyCommand 同步追加两个 `-o` 参数。将进程执行包在 `try/finally` 中，最终删除 `known_hosts` 及其空临时目录；不改既有 65/66 冲突分类和超时处理。

- [x] **Step 4: 运行定向与 core 全量测试**

Run: `./scripts/mvn.sh -pl delivery-core test`

Expected: 全部 PASS，无临时 known_hosts 遗留。

- [x] **Step 5: 提交严格校验实现**

```bash
git add delivery-core && git commit -m "fix: verify ssh hosts with node scoped known hosts"
```

### Task 3: Worker 工作目录、真实配置与完整验收

**Files:**
- Modify: `deploy/Dockerfile.worker`
- Modify: `docs/mvp-runbook.md`
- Modify: `docs/superpowers/plans/2026-11-01-archive-delivery-local-production-mvp.md`
- Modify: `docs/superpowers/plans/2026-11-02-pipeline-node-config.md`
- Modify: `docs/superpowers/plans/2026-11-02-ssh-node-host-key.md`

- [x] **Step 1: 验证并提交非 root 工作目录修复**

保留 Dockerfile 中创建并授权 `/data/work` 的修改，运行：

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build worker-line-1 worker-line-2 worker-line-3
for line in 1 2 3; do docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -T worker-line-$line test -w /data/work || exit; done
```

Expected: 三行命令均返回 0；`Dockerfile.worker` 作为独立修复提交。

- [x] **Step 2: 从已验证本机配置补齐真实公钥并发布**

仅处理 Git 忽略的 `deploy/pipeline-v3.local.json`，不打印文件或私钥。对每线 `host`、`jumpHost` 使用 `ssh-keygen -F <host> -f ~/.ssh/known_hosts` 获取本机已验证公钥，去掉注释行并选择 `ssh-ed25519` 行写入对应 `hostKey/jumpHostKey`；任一字段找不到则停止询问，不使用 `ssh-keyscan` 的未认证结果替代。随后运行：

```bash
./scripts/configure-pipeline.sh
./scripts/preflight.sh
```

Expected: 发布新 Pipeline 版本；源/HZ/SG OSS 与三线 SSH 全部 PASS。

- [x] **Step 3: 处理当前 run 的远端冲突并重新验收**

读取两个冲突键的 HZ/SG 元数据、当前 request mapping 和 FTP 基线，不覆盖、不删除既有对象。只有确认对象属于本次可丢弃测试且获得用户许可后才清理；否则保留阻塞并向用户报告。恢复后执行 `./scripts/real-e2e-test.sh`，不创建绕过冲突的重复 run。

Expected: 仅由验收脚本创建全新 run，不手工重复提交或改写节点状态；PowerJob 调度 4/3/3 共 10 包 DELIVERED，SHA、业务日期、CRR 等待、历史 FTP path/size/mtime 全部符合原计划。

- [x] **Step 4: 执行剩余真实与自动化 case**

使用包级 retry 和 `resume-run.sh` 验证只重试当前节点、前置成功节点不重复执行；在测试时钟下重放两个跨日 case 并检查 API 看板。运行：

```bash
./scripts/mvp-test.sh
cd web && npm ci && npm run test && npm run build
cd .. && git diff --check
```

Expected: 所有非跳过 case PASS；真实验收脚本和真实重试/续跑均有实际证据。

- [x] **Step 5: 检查敏感信息、同步计划并提交**

查询版本列表、包详情、timeline 与日志，确认不包含真实 AK/SK 或私钥；`git status --short` 仅保留预期源码/计划及用户原有未跟踪文档。按实际结果勾选三份计划和原 MVP Task 15，提交：

```bash
git add delivery-core delivery-server delivery-worker deploy docs scripts
git commit -m "test: complete strict ssh and delivery acceptance"
```
