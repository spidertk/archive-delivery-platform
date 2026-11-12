#!/usr/bin/env python3
import os
import re
import socket
from pathlib import Path
from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer


def main():
    """本地和生产验证只开放白名单单账户 FTP；允许上传下载，禁止删除、重命名和匿名访问。"""
    account = os.environ["FTP_USER_NAME"]
    password = os.environ["FTP_USER_PASS"]
    home = os.environ["FTP_USER_HOME"]
    if not re.fullmatch(r"entity[123]|june|june-1|june-2", account):
        raise SystemExit("Only approved local or production verification accounts are allowed")
    Path(home).mkdir(parents=True, exist_ok=True)
    authorizer = DummyAuthorizer()
    authorizer.add_user(account, password, home, perm="elrmw")
    handler = FTPHandler
    handler.authorizer = authorizer
    handler.masquerade_address = socket.gethostbyname(socket.gethostname())
    handler.passive_ports = range(30000, 30010)
    handler.banner = "Archive delivery local verification FTP"
    server = FTPServer(("0.0.0.0", 21), handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
