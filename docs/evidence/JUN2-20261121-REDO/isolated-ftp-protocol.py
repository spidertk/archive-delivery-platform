"""只在临时目录和容器回环地址验证 FTP 协议，不读取业务数据或凭据。"""
import ftplib
import hashlib
import json
import pathlib
import secrets
import sys
import tempfile
import threading

sys.path.insert(0, 'nodes/python')
from ftp_download_verify import transfer
from deploy.ftp_server import ArchiveFTPHandler
from pyftpdlib.authorizers import DummyAuthorizer
from pyftpdlib.servers import FTPServer

with tempfile.TemporaryDirectory() as temporary:
    root=pathlib.Path(temporary)
    home=root/'ftp'
    home.mkdir()
    (home/'2026-11-19').mkdir()
    (home/'2026-11-19/old.txt').write_text('isolated test')
    password=secrets.token_urlsafe(24)
    authorizer=DummyAuthorizer()
    authorizer.add_user('root',password,str(home),perm='elrmwdf')
    ArchiveFTPHandler.authorizer=authorizer
    server=FTPServer(('127.0.0.1',0),ArchiveFTPHandler)
    port=server.socket.getsockname()[1]
    worker=threading.Thread(target=lambda:server.serve_forever(timeout=0.1,blocking=True,handle_exit=False),daemon=True)
    worker.start()
    try:
        source=root/'source.tar.gz'
        source.write_bytes(b'isolated protocol test archive')
        request=dict(host='127.0.0.1',port=port,username='root',password=password,rootPath='/',
            remotePath='2026-11-21/batch_00001.tar.gz',sourcePath=str(source),
            downloadPath=str(root/'downloads/batch_00001.tar.gz'),expectedSize=source.stat().st_size,
            expectedSha256=hashlib.sha256(source.read_bytes()).hexdigest(),cleanupOtherDeliveryDates=True,
            deliveryDate='2026-11-21',retentionRoot=str(root/'retention'))
        result=transfer(request)
        assert result['removedDates']==['2026-11-19']
        (home/'2026-11-19').mkdir()
        (home/'2026-11-19/late.txt').write_text('late isolated old date')
        assert transfer(request)['removedDates']==['2026-11-19']
        assert (home/'2026-11-21/batch_00001.tar.gz').read_bytes()==source.read_bytes()
        readonly=dict(request,cleanupOtherDeliveryDates=False,remotePath='2026-11-21/missing.tar.gz',downloadPath=str(root/'missing.tar.gz'))
        try:
            transfer(readonly,verify_only=True)
            raise AssertionError('Read-only verification repaired a missing remote file')
        except ValueError as error:
            assert 'missing during read-only' in str(error)
        assert not (home/'2026-11-21/missing.tar.gz').exists()
        (home/'2026-11-20').mkdir()
        (home/'2026-11-20/link').symlink_to(home/'2026-11-21',target_is_directory=True)
        client=ftplib.FTP()
        client.connect('127.0.0.1',port)
        client.login('root',password)
        try:
            client.delete('/2026-11-20/link/batch_00001.tar.gz')
            raise AssertionError('Symlink deletion was allowed')
        except ftplib.error_perm:
            pass
        client.quit()
        assert (home/'2026-11-21/batch_00001.tar.gz').exists()
        assert not list(root.rglob('*.partial'))
        print(json.dumps({'result':'PASSED','checks':['MLSD_BINARY_MODE','DATE_CLEANUP','SAME_DATE_LATE_CLEANUP',
            'PARTIAL_ATOMIC_RENAME','IDEMPOTENCY','READ_ONLY_MISSING_REJECTED','SYMLINK_DELETE_REJECTED','NO_PARTIAL']}))
    finally:
        server.close_all()
        worker.join(2)
