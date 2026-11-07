# Python repair 节点指南

## 模块职责

本目录实现本地源 tar 的数据面检查与修复。archive_repair.py 由 Pipeline 的 python.repair 节点启动，负责安全解包、稳定顺序处理、输出 tar、请求映射、token、SHA 和结构化结果；legacy_fix_archive.py 保存字段替换与响应协议规则。

Python 不直接访问 PG，不决定全局编号、目标分配、流程顺序或交付状态。Java 负责事务、request_id_allocation、source_data/delivery_task 事实和 delivery_node 提交。

## 输入输出边界

```text
--source            源 tar
--target            本地输出 tar
--delivery-date     当前 delivery_task.delivery_date
--delivery-account  当前任务直属实体的业务账户（如规则需要）
--start-number      Java/PG 已分配的幂等起始编号
--manifest          本地结构化结果文件
--inspect           只检查源包结构
```

业务账户不是 FTP 密码，Secret 不得通过 CLI、标准输出或结果文件传递。规则版本集中到 PipelineVersion.ruleSets，Python 不读取独立规则表。

## 业务约束

1. 防止 tar 路径穿越和非法成员；使用临时输出，完整成功后才封存最终文件。
2. 缺协议文件或验证失败必须非零退出，不产空包；确定性规则重试产生一致 file_mapping 和 SHA。
3. 相同输入、规则和 startNumber 必须一致，重试复用 Java 原 allocation，失败编号空洞不回收。
4. 结果支持 artifacts 七字段，不增加 locations 或 Secret。
5. 重复 requestId 不静默覆盖，输出规则码和原因，由 Java 写 FILTERED/filter_reason。
6. deliveryDate、deliveryAccount 和编号显式来自 Java，不读取机器日期或本地计数。
7. 新规则放在独立可测试函数中，注释业务来源、输入输出和失败语义。
8. 新协议直接按最新方案实现，规则变化使用黄金样本证明。

## 测试

```bash
python3 -m unittest nodes/python/test_archive_repair.py -v
./scripts/mvn.sh -pl delivery-worker -am test
```

测试覆盖 STREAM、SYNC、缺 requestTime、成员顺序、缺协议文件、大小边界、确定性、路径安全、token/SHA、file_mapping 和重复编号原因。测试后清理 __pycache__ 和临时解包产物。
