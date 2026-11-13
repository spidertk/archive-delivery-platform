# 黄金流水线：alibaba/jun 单包真实交付

## Review 元数据

- caseId：`archive-delivery-jun`
- pipelineCode：`archive-delivery`
- 负责人：归档交付平台维护者
- 变更方式：本地逻辑门禁通过后，经生产环境审批执行真实用例
- 安全边界：凭据只从服务器受控 `.env` 注入，不进入本文件、Git 或证据

## 不可变 Pipeline 定义

```json pipeline-definition
{
  "pipelineCode": "archive-delivery",
  "version": 1,
  "nodeDefinitions": {
    "oss.download": {
      "enabled": true,
      "configSchema": {"type": "object", "required": ["endpoint", "bucket", "accessKeyId", "accessKeySecret"]},
      "inputContract": {"type": "object"},
      "outputContract": {"type": "object"}
    },
    "package.merge": {
      "enabled": true,
      "configSchema": {"type": "object", "required": ["scriptPath"]},
      "inputContract": {"type": "object"},
      "outputContract": {"type": "object"}
    },
    "python.repair": {
      "enabled": true,
      "configSchema": {"type": "object", "required": ["scriptPath"]},
      "inputContract": {"type": "object"},
      "outputContract": {"type": "object"}
    },
    "manifest.qualify": {
      "enabled": true,
      "configSchema": {"type": "object"},
      "inputContract": {"type": "object"},
      "outputContract": {"type": "object"}
    },
    "oss.upload": {
      "enabled": true,
      "configSchema": {"type": "object", "required": ["endpoint", "bucket", "accessKeyId", "accessKeySecret"]},
      "inputContract": {"type": "object"},
      "outputContract": {"type": "object"}
    },
    "ftp.download-verify": {
      "enabled": true,
      "configSchema": {"type": "object", "required": ["scriptPath", "deliveryEntityFrom", "ftpCredential"]},
      "inputContract": {"type": "object"},
      "outputContract": {"type": "object"}
    }
  },
  "config": {
    "businessTimezone": "Asia/Shanghai",
    "deliveryCutoffTime": "18:00:00",
    "rolloverStartNode": "targetUpload",
    "assignment": {
      "strategySource": "SUBMIT_TARGETS",
      "minPackageBytes": 8388608,
      "maxPackageBytes": 10485760
    },
    "concurrency": {"maxRunningTasks": 3},
    "retry": {"maxAttempts": 5}
  },
  "nodes": [
    {
      "id": "sourceDownload",
      "type": "oss.download",
      "stageCode": "ACQUIRE",
      "displayName": "下载源 OSS 并保留",
      "next": "mergePackages",
      "config": {
        "endpoint": "https://oss-ap-southeast-1.aliyuncs.com",
        "bucket": "sg-oss-external-channel-source",
        "prefix": "jayden-incoming/maasData/archive/YY：@feiji777888/2026-11-09/",
        "accessKeyId": "replace-access-key",
        "accessKeySecret": "replace-secret"
      }
    },
    {
      "id": "mergePackages",
      "type": "package.merge",
      "stageCode": "MERGE",
      "displayName": "按 8–10 MiB 合并来源包",
      "next": "repair",
      "config": {"scriptPath": "archive_merge.py"}
    },
    {
      "id": "repair",
      "type": "python.repair",
      "stageCode": "TRANSFORM",
      "displayName": "生产协议修复并重打包",
      "next": "qualify",
      "config": {"scriptPath": "archive_repair.py", "gzipCompressLevel": 5}
    },
    {
      "id": "qualify",
      "type": "manifest.qualify",
      "stageCode": "QUALIFY",
      "displayName": "校验修复产物",
      "next": "targetUpload",
      "config": {}
    },
    {
      "id": "targetUpload",
      "type": "oss.upload",
      "stageCode": "TRANSFER",
      "displayName": "OSS SDK 上传并 GET 回验",
      "next": "ftpDownloadVerify",
      "config": {
        "endpoint": "https://oss-ap-southeast-1-internal.aliyuncs.com",
        "bucket": "sg-storage-20261013-6vmlk",
        "prefix": "{accountName}/{deliveryDate}-{deliveryBatch}-{accountName}",
        "accessKeyId": "replace-access-key",
        "accessKeySecret": "replace-secret"
      }
    },
    {
      "id": "ftpDownloadVerify",
      "type": "ftp.download-verify",
      "stageCode": "VERIFY",
      "displayName": "交付 FTP 上传下载核验",
      "next": null,
      "config": {
        "scriptPath": "ftp_download_verify.py",
        "deliveryEntityFrom": "TASK",
        "remotePathTemplate": "{deliveryDate}/{deliveryBatch}",
        "ftpCredential": {
          "june": "replace-ftp-password",
          "june-1": "replace-ftp-password",
          "june-2": "replace-ftp-password"
        }
      }
    }
  ]
}
```

## 黄金线上用例

```json golden-case
{
  "caseId": "archive-delivery-jun",
  "pipelineCode": "archive-delivery",
  "description": "从指定真实源前缀交付一个 alibaba/jun 合并包，独立核对源、目标 OSS 和 FTP 文件事实。",
  "targets": [
    {
      "targetCount": 1,
      "vendorCode": "alibaba",
      "entityCode": "jun",
      "accountName": "june",
      "workerGroup": "group1",
      "host": "ftp-june",
      "hostRoot": "/srv/maas-sftp/maasData/archive/june",
      "port": 21,
      "rootPath": "/"
    }
  ],
  "expected": {
    "deliveryStatus": "DELIVERED",
    "taskStatus": "SUCCEEDED",
    "taskCount": 1,
    "nodeTypes": [
      "oss.download",
      "package.merge",
      "python.repair",
      "manifest.qualify",
      "oss.upload",
      "ftp.download-verify"
    ],
    "externalChecks": ["SOURCE_GET", "TARGET_GET", "FTP_GET", "SIZE_SHA", "NO_PARTIAL"]
  }
}
```

## 人工 Review 检查

- DAG、节点配置、包大小、来源和交付路径是否改变，是否必须同步迁移和运维文档。
- 本地是否使用相同定义完成逻辑验证，未访问真实 OSS/FTP。
- 生产是否独立验证 SOURCE_GET、TARGET_GET、FTP_GET、SIZE_SHA 和 NO_PARTIAL。
- 是否保留实际 deliveryId/taskId、PipelineVersion、外部 URI、size/SHA 和证据目录。
