#!/usr/bin/env python3
import os
import re
import socket
from pathlib import Path
from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.handlers import FTPHandler
from pyftpdlib.servers import FTPServer


class ArchiveFTPHandler(FTPHandler):
    """删除权限仍限于无链接路径，禁止历史日期链接指向当前交付目录。"""
    def safe_delete(self, path):
        # pyftpdlib 将 FTP 路径转换为真实根内路径；逐层拒绝链接，不能依赖 MLSD 类型。
        candidate = Path(path)
        root = Path(self.fs.root)
        if root not in candidate.parents or any(p.is_symlink() for p in [candidate, *candidate.parents] if p != root):
            self.respond("550 Retention cannot follow symbolic links or remove the account root.")
            return False
        return True

    def ftp_DELE(self, path):
        if self.safe_delete(path):
            return super().ftp_DELE(path)

    def ftp_RMD(self, path):
        if self.safe_delete(path):
            return super().ftp_RMD(path)


def main():
    """直属单账户根目录隔离；仅显式启用日期保留策略时开放清理权限。"""
    account = os.environ["FTP_USER_NAME"]
    password = os.environ["FTP_USER_PASS"]
    home = os.environ["FTP_USER_HOME"]
    if not re.fullmatch(r"entity[123]|june|june-1|june-2|root", account):
        raise SystemExit("Only approved local or production verification accounts are allowed")
    Path(home).mkdir(parents=True, exist_ok=True)
    authorizer = DummyAuthorizer()
    # f 支持 .partial 原子改名；d 仅供获授权的按日期清理节点使用。
    permissions = "elrmwf" + ("d" if os.environ.get("FTP_ALLOW_DATE_CLEANUP") == "true" else "")
    authorizer.add_user(account, password, home, perm=permissions)
    handler = ArchiveFTPHandler
    handler.authorizer = authorizer
    handler.masquerade_address = socket.gethostbyname(socket.gethostname())
    handler.passive_ports = range(30000, 30010)
    handler.banner = "Archive delivery local verification FTP"
    server = FTPServer(("0.0.0.0", 21), handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
