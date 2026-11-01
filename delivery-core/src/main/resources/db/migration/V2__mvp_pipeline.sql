SET search_path TO archive_delivery;

INSERT INTO production_line(id, line_code, provider_code, compute_host, ftp_host, target_root) VALUES
(1, 'LINE-1', 'june', '47.111.191.103', '8.219.218.145', 'june'),
(2, 'LINE-2', 'june-1', '8.136.128.184', '47.84.235.96', 'june-1')
ON CONFLICT(id) DO NOTHING;

INSERT INTO production_group(id, production_line_id, archive_date, group_no, target_count) VALUES
(1, 1, DATE '2026-10-30', 1, 5),
(2, 2, DATE '2026-10-30', 1, 5)
ON CONFLICT(id) DO NOTHING;

INSERT INTO pipeline(id, pipeline_code, description)
VALUES (1, 'archive-delivery', 'Two production lines, five packages per line')
ON CONFLICT(id) DO NOTHING;

INSERT INTO pipeline_version(id, pipeline_id, version_no, status, definition, definition_sha256)
VALUES (1, 1, 1, 'PUBLISHED', $pipeline$
{
  "pipelineCode": "archive-delivery",
  "version": 1,
  "nodes": [
    {"id":"download","scope":"SOURCE","type":"archive.download","config":{}},
    {"id":"repair","scope":"SOURCE","type":"python.repair","config":{}},
    {"id":"qualify","scope":"SOURCE","type":"manifest.qualify","config":{}},
    {"id":"group","scope":"SOURCE","type":"output.group","config":{}},
    {"id":"hzUpload","scope":"OUTPUT","type":"local.hz-upload","config":{}},
    {"id":"sgVerify","scope":"OUTPUT","type":"local.sg-verify","config":{}},
    {"id":"sgDownload","scope":"OUTPUT","type":"local.sg-download","config":{}},
    {"id":"publish","scope":"OUTPUT","type":"local.publish","config":{}},
    {"id":"receipt","scope":"OUTPUT","type":"delivery.receipt","config":{}}
  ]
}
$pipeline$::jsonb, '0000000000000000000000000000000000000000000000000000000000000001')
ON CONFLICT(id) DO NOTHING;

INSERT INTO ingest_source(id, source_code, source_type, bucket_ref, object_prefix)
VALUES (1, 'delivery-plan-0005', 'OSS', 'junflow-ysai-source-hz-20260906',
        '_derived/ysai-sftp-20261027/delivery_plan_0005/')
ON CONFLICT(id) DO NOTHING;

INSERT INTO source_pipeline_binding(id, source_id, pipeline_version_id)
VALUES (1, 1, 1)
ON CONFLICT(id) DO NOTHING;

INSERT INTO number_counter(namespace, next_value) VALUES ('customer-request:20261030', 1)
ON CONFLICT(namespace) DO NOTHING;

SELECT setval('production_line_id_seq', 2, true);
SELECT setval('production_group_id_seq', 2, true);
SELECT setval('pipeline_id_seq', 1, true);
SELECT setval('pipeline_version_id_seq', 1, true);
SELECT setval('ingest_source_id_seq', 1, true);
SELECT setval('source_pipeline_binding_id_seq', 1, true);
