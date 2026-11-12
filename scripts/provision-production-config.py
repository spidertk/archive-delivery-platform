#!/usr/bin/env python3
import os
import secrets
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
    # 非 Secret 生产定位可以安全补齐；已有值不覆盖，初始管理员密码随后由 bootstrap 轮换。
    defaults = {
        "COMPOSE_PROJECT_NAME": "archive_delivery_production",
        "ARCHIVE_WORKER_GROUP": "group1",
        "ARCHIVE_MAX_RUNNING_TASKS": "3",
        "ARCHIVE_PG_BIND_ADDRESS": "127.0.0.1",
        "POWERJOB_BIND_ADDRESS": "127.0.0.1",
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
        "PROD_PIPELINE_OBSERVATION_SECONDS": "1800",
    }
    for key, value in defaults.items():
        values.setdefault(key, value)
    for key in GENERATED_KEYS:
        if not values.get(key) or values[key].startswith("replace-") or "********" in values[key]:
            values[key] = secrets.token_urlsafe(30)
    path.write_text("\n".join(f"{key}={value}" for key, value in values.items()) + "\n", encoding="utf-8")
    os.chmod(path, 0o600)
    missing_oss = [key for key in OSS_KEYS
                   if not values.get(key) or values[key].startswith("replace-") or "********" in values[key]]
    if missing_oss:
        print("Local service secrets initialized. OSS credentials still required: " + ", ".join(missing_oss))
    else:
        print("Controlled production configuration is present; OSS values were not printed.")


if __name__ == "__main__":
    main()
