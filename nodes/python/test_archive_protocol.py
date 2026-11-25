import io
import json
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))
from legacy_fix_archive import verify_output


class ArchiveProtocolTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.artifact = Path(self.temp.name) / 'archive.tar.gz'

    def tearDown(self):
        self.temp.cleanup()

    def archive(self, changes=None, missing=None, extra=None):
        # 目录顺序故意与 requestId 排序相反，流式校验不能回卷 gzip 查找 metadata。
        with tarfile.open(self.artifact, 'w:gz') as archive:
            for index in [2, 1]:
                request_id = '20261121123456' + f'{index:025d}'
                metadata = {'requestId': request_id, 'requestTime': '2026-11-21 12:34:56',
                            'providerCode': 'jun-2', 'requestMode': 'SYNC'}
                metadata.update(changes or {})
                for name, value in [('biz_response.json', b'x' * 4096), ('metadata.json', json.dumps(metadata).encode()), ('biz_request.json', b'{}')]:
                    if name == missing:
                        continue
                    member = tarfile.TarInfo(request_id + '/' + name)
                    member.size = len(value)
                    archive.addfile(member, io.BytesIO(value))
            if extra:
                archive.addfile(extra, io.BytesIO(b''))

    def test_shuffled_requests_validate_without_random_member_lookup(self):
        self.archive()
        with patch.object(tarfile.TarFile, 'getmembers', side_effect=AssertionError('gzip random lookup forbidden')):
            verify_output(self.artifact, 'jun-2')

    def test_id_date_provider_and_mode_checks_remain_enforced(self):
        for field, value in [('requestId', ''), ('requestTime', '2026-11-22T00:00:00.000000000Z'),
                             ('requestTime', '2026-02-30 12:34:56'),
                             ('providerCode', 'wrong'), ('requestMode', 'STREAM'), ('requestMode', None)]:
            with self.subTest(field=field, value=value):
                self.archive(changes={field: value})
                with self.assertRaisesRegex(ValueError, 'Protocol validation failed'):
                    verify_output(self.artifact, 'jun-2')

    def test_original_directory_and_id_are_independent_source_facts(self):
        self.archive(changes={'requestId': 'b3cbcd69-611f-4789-9282-0013597cc31c'})
        verify_output(self.artifact, 'jun-2')

    def test_required_protocol_files_cannot_be_missing(self):
        for name in ['metadata.json', 'biz_request.json', 'biz_response.json']:
            with self.subTest(name=name):
                self.archive(missing=name)
                with self.assertRaisesRegex(ValueError, 'Protocol validation failed'):
                    verify_output(self.artifact, 'jun-2')

    def test_links_and_unsafe_members_are_rejected(self):
        for name in ['../outside', '/absolute', 'safe/link']:
            member = tarfile.TarInfo(name)
            if name == 'safe/link':
                member.type = tarfile.SYMTYPE
                member.linkname = '/outside'
            self.archive(extra=member)
            with self.assertRaisesRegex(ValueError, 'unsafe archive member'):
                verify_output(self.artifact, 'jun-2')

    def test_empty_archive_is_not_a_valid_delivery(self):
        with tarfile.open(self.artifact, 'w:gz'):
            pass
        with self.assertRaisesRegex(ValueError, 'no request directories'):
            verify_output(self.artifact, 'jun-2')


if __name__ == '__main__':
    unittest.main()
