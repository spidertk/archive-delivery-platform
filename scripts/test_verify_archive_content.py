"""以独立构造的拼接归档验证验收器能发现第一段截断、改正文和改 ID。"""
import gzip
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

from scripts.verify_archive_content import audit, formatted_time


class VerifyArchiveContentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def segment(self, directory, request_id, repaired=False, content=b'original response', id_field='requestId'):
        # 每段都有完整 TAR 结束块和独立 gzip 尾部，重现生产拼接结构。
        metadata = {id_field: request_id, 'requestTime': '2026-11-19T11:18:34.60359085+08:00',
                    'providerCode': None, 'requestMode': 'SYNC', 'extra': 'preserved'}
        if repaired:
            metadata.update(requestTime='2026-11-19 11:18:34', providerCode='jun-2')
        payload = io.BytesIO()
        with tarfile.open(fileobj=payload, mode='w') as archive:
            for name, data in [('metadata.json', json.dumps(metadata).encode()), ('biz_request.json', b'{"stream":false}'),
                               ('biz_response.json', content)]:
                member = tarfile.TarInfo(directory + '/' + name)
                member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
        return gzip.compress(payload.getvalue())

    def write(self, name, *segments):
        path = self.root / name
        path.write_bytes(b''.join(segments))
        return path

    def test_concatenated_source_is_read_completely_and_independent_names_are_preserved(self):
        source = self.write('source.tar.gz', self.segment('directory-one', 'uuid-one'), self.segment('directory-two', 'uuid-two'))
        final = self.write('batch_0000001.tar.gz', self.segment('directory-one', 'uuid-one', True),
                           self.segment('directory-two', 'uuid-two', True))
        result = audit([source], final, 'jun-2')
        self.assertEqual(2, result['requestCount'])
        self.assertEqual(6, result['sourceFileCount'])
        self.assertTrue(result['nonMetadataContentPreserved'])

    def test_first_segment_only_cannot_pass(self):
        source = self.write('source.tar.gz', self.segment('one', 'one'), self.segment('two', 'two'))
        final = self.write('batch_0000001.tar.gz', self.segment('one', 'one', True))
        with self.assertRaisesRegex(ValueError, 'directory set differs'):
            audit([source], final, 'jun-2')

    def test_snake_case_id_field_and_independent_directory_are_preserved(self):
        source = self.write('source.tar.gz', self.segment('original-directory', 'uuid-one', id_field='request_id'))
        final = self.write('final.tar.gz', self.segment('original-directory', 'uuid-one', True, id_field='request_id'))
        self.assertTrue(audit([source], final, 'jun-2')['requestIdsPreserved'])
        mutated = self.write('mutated.tar.gz', self.segment('original-directory', 'uuid-one', True))
        with self.assertRaisesRegex(ValueError, 'Metadata differs'):
            audit([source], mutated, 'jun-2')

    def test_changed_response_or_request_id_cannot_pass(self):
        source = self.write('source.tar.gz', self.segment('one', 'uuid-one'))
        for request_id, content in [('uuid-one', b'changed response'), ('replacement-id', b'original response')]:
            with self.subTest(request_id=request_id):
                final = self.write('final.tar.gz', self.segment('one', request_id, True, content))
                with self.assertRaises(ValueError):
                    audit([source], final, 'jun-2')

    def test_changed_directory_cannot_pass(self):
        source = self.write('source.tar.gz', self.segment('original-directory', 'uuid-one'))
        final = self.write('final.tar.gz', self.segment('new-directory', 'uuid-one', True))
        with self.assertRaisesRegex(ValueError, 'directory set differs'):
            audit([source], final, 'jun-2')

    def test_duplicate_archive_file_is_rejected(self):
        segment = self.segment('one', 'one')
        source = self.write('source.tar.gz', segment, segment)
        final = self.write('final.tar.gz', self.segment('one', 'one', True))
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            audit([source], final, 'jun-2')

    def test_invalid_source_calendar_and_timezone_are_rejected(self):
        for value in ['2026-02-30T12:00:00Z', '2026-11-19T25:00:00Z', '2026-11-19T12:00:00+24:00']:
            with self.subTest(value=value), self.assertRaises(ValueError):
                formatted_time(value)


if __name__ == '__main__':
    unittest.main()
