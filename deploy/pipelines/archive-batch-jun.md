# 黄金流水线：OSS 批次创建与实体机器规划

## Review 元数据

- caseId：`archive-batch-jun`
- pipelineCode：`archive-batch`
- 负责人：归档交付平台维护者
- 变更方式：本地逻辑门禁通过后，经生产环境审批执行真实用例
- 安全边界：凭据只从服务器受控 `.env` 注入，不进入本文件、Git 或证据

## 不可变 Pipeline 定义

```json pipeline-definition
{
  "pipelineCode": "archive-batch",
  "version": 1,
  "nodeDefinitions": {
    "oss.download": {
      "enabled": true,
      "configSchema": {"type": "object", "required": ["endpoint", "accessKeyId", "accessKeySecret"]},
      "inputContract": {"type": "object"},
      "outputContract": {"type": "object"}
    }
  },
  "config": {
    "businessTimezone": "Asia/Shanghai",
    "deliveryCutoffTime": "02:00:00",
    "assignment": {
      "strategySource": "BATCH_PLANNING",
      "maxPackageBytes": 104857600,
      "entityWorkers": [
        {
          "vendorCode": "alibaba",
          "entityCode": "jun-2",
          "workers": [
            {"workerGroup": "group1", "maxPackagesPerRound": 6000}
          ]
        },
        {
          "vendorCode": "alibaba",
          "entityCode": "jun",
          "workers": [
            {"workerGroup": "group2", "maxPackagesPerRound": 100}
          ]
        }
      ]
    },
    "concurrency": {"maxRunningTasks": 8},
    "retry": {"maxAttempts": 5}
  },
  "nodes": [
    {
      "id": "sourceDownload",
      "type": "oss.download",
      "stageCode": "ACQUIRE",
      "displayName": "全量下载 OSS、归档 source_data 并规划 delivery_batch",
      "next": null,
      "config": {
        "endpoint": "https://oss-ap-southeast-1-internal.aliyuncs.com",
        "accessKeyId": "replace-access-key",
        "accessKeySecret": "replace-secret"
      }
    }
  ]
}
```

## 黄金线上用例

```json golden-case
{
  "caseId": "archive-batch-jun",
  "pipelineCode": "archive-batch",
  "ossUri": "oss://sg-storage-20261013-6vmlk/internal/gpt-sse-wire-fix-20261112-v1/products/",
  "description": "创建真实 OSS 批次，随后由 archive-delivery 两次各消费 100 个预规划包并核验外部文件。",
  "targets": [
    {
      "targetCount": 100,
      "vendorCode": "alibaba",
      "entityCode": "jun-2",
      "accountName": "root",
      "workerGroup": "group1",
      "host": "ftp-june",
      "hostRoot": "/srv/maas-sftp/maasData/archive/june-2",
      "port": 21,
      "rootPath": "/"
    }
  ],
  "expected": {
    "deliveryStatus": "DELIVERED",
    "taskStatus": "SUCCEEDED",
    "taskCount": 100,
    "nodeTypes": ["oss.download"],
    "externalChecks": ["SOURCE_GET", "TARGET_GET", "FTP_GET", "SIZE_SHA", "NO_PARTIAL"]
  }
}
```

## 人工 Review 检查

- 批次 Pipeline 是否只设置 maxPackageBytes 上限，不设置合并包下限，并维护实体到 Worker 的容量映射。
- 是否完整下载真实 OSS 原始包并按 SHA 维护 source_data.unique_key。
- 是否按每台机器 maxPackagesPerRound 轮询规划全部 delivery_batch 包。
- 两次交付是否消费不同包名，原始文件数、任务数、批次数和状态是否一致。
