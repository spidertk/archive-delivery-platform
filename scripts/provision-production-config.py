#!/usr/bin/env python3
import os
import secrets
import shlex
from pathlib import Path

GENERATED_KEYS = (
    "ARCHIVE_DB_PASSWORD",
    "POWERJOB_MYSQL_ROOT_PASSWORD",
    "POWERJOB_USERNAME",
    "POWERJOB_PASSWORD",
    "POWERJOB_ADMIN_PASSWORD",
    "PROD_FTP_ENTITY_1_PASSWORD",
)
OSS_KEYS = (
    "OSS_SOURCE_ACCESS_KEY_ID",
    "OSS_SOURCE_ACCESS_KEY_SECRET",
    "TARGET_OSS_ACCESS_KEY_ID",
    "TARGET_OSS_ACCESS_KEY_SECRET",
)


def shell_value(value):
    quoted = len(value) >= 2 and value[0] == value[-1] and value[0] in ('"', "'")
    return value if quoted else shlex.quote(value)


def main():
    root = Path(__file__).resolve().parent.parent
    path = root / "deploy" / ".env"
    if not path.is_file():
        raise SystemExit("deploy/.env is missing; copy deploy/env.production.example first")
    lines = path.read_text(encoding="utf-8").splitlines()
    values = {}
    for line in lines:
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key] = value
    # 新部署按 jun-2 的 8 核 16G 补齐资源；已有容量和 Secret 不覆盖，扩容需明确修改受控配置。
    defaults = {
        "COMPOSE_PROJECT_NAME": "archive_delivery_production",
        "COMPOSE_PROFILES": "embedded-ftp",
        "ARCHIVE_WORKER_GROUP": "group1",
        "ARCHIVE_MAX_RUNNING_TASKS": "8",
        "ARCHIVE_WORKER_DB_POOL_SIZE": "4",
        "ARCHIVE_WORKER_JAVA_OPTIONS": "-Xms128m -Xmx512m",
        "ARCHIVE_SERVER_JAVA_OPTIONS": "-Xms128m -Xmx512m",
        "ARCHIVE_PREPARATION_DOWNLOAD_THREADS": "16",
        "ARCHIVE_TASK_LEASE_SECONDS": "900",
        "ARCHIVE_CLEANUP_WORK_AFTER_VERIFY": "true",
        "ARCHIVE_PG_BIND_ADDRESS": "0.0.0.0",
        "POWERJOB_BIND_ADDRESS": "127.0.0.1",
        "POWERJOB_PROTOCOL_BIND_ADDRESS": "127.0.0.1",
        "POWERJOB_SERVER_ADDRESS": "powerjob-server:7700",
        "POWERJOB_JVMOPTIONS": "-Dpowerjob.network.external.address=powerjob-server -Dpowerjob.network.external.port=10010",
        "POWERJOB_ADMIN_INITIAL_PASSWORD": "powerjob_admin",
        "PROD_ENTITY_COUNT": "1",
        "PROD_ENTITY_1_VENDOR_CODE": "alibaba",
        "PROD_ENTITY_1_ENTITY_CODE": "jun",
        "PROD_ENTITY_1_NAME": '"生产交付实体 jun"',
        "PROD_ENTITY_1_ACCOUNT": "june",
        "PROD_ENTITY_1_HOST": "ftp-june",
        "ENTITY_1_WORKER_GROUP": "group1",
        "PROD_FTP_ENTITY_1_PORT": "21",
        "PROD_FTP_ENTITY_1_ROOT_PATH": "/",
        "PROD_JUNE_HOST_ROOT": "/srv/maas-sftp/maasData/archive/june",
        "PROD_TARGET_COUNT": "1",
        "PROD_OSS_URI": "oss://sg-storage-20261013-6vmlk/internal/gpt-jayden-raw-20261113-v1/daily-accepted-20261113-v2/products/",
        "PROD_PIPELINE_OBSERVATION_SECONDS": "21600",
    }
    for key, value in defaults.items():
        values.setdefault(key, value)
    for key in GENERATED_KEYS:
        if not values.get(key) or values[key].startswith("replace-") or "********" in values[key]:
            values[key] = secrets.token_urlsafe(30)
    path.write_text("\n".join(f"{key}={shell_value(value)}" for key, value in values.items()) + "\n", encoding="utf-8")
    os.chmod(path, 0o600)
    missing_oss = [key for key in OSS_KEYS
                   if not values.get(key) or values[key].startswith("replace-") or "********" in values[key]]
    if missing_oss:
        print("Local service secrets initialized. OSS credentials still required: " + ", ".join(missing_oss))
    else:
        print("Controlled production configuration is present; OSS values were not printed.")


if __name__ == "__main__":
    main()
