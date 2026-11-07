SET search_path TO archive_delivery;

INSERT INTO pipeline_version(id,pipeline_id,version_no,status,definition,definition_sha256)
VALUES (4,1,4,'PUBLISHED',$pipeline$
{
  "pipelineCode":"archive-delivery",
  "version":4,
  "config":{"autoRollover":false},
  "nodes":[
    {"id":"download","scope":"SOURCE","type":"archive.download","stageCode":"ACQUIRE","displayName":"获取源包","config":{"endpoint":"","bucket":"","prefix":"","accessKeyId":"","accessKeySecret":""}},
    {"id":"repair","scope":"SOURCE","type":"python.repair","stageCode":"TRANSFORM","displayName":"修复归档","config":{"ruleSet":"fix-archive-legacy-v1"}},
    {"id":"qualify","scope":"SOURCE","type":"manifest.qualify","stageCode":"QUALIFY","displayName":"校验产物","config":{}},
    {"id":"group","scope":"SOURCE","type":"output.group","stageCode":"GROUP","displayName":"形成交付包","config":{}},
    {"id":"transfer","scope":"OUTPUT","type":"oss.upload","stageCode":"TRANSFER","displayName":"上传交付存储","config":{"endpoint":"","bucket":"","accessKeyId":"","accessKeySecret":"","contentMd5":true}},
    {"id":"receipt","scope":"OUTPUT","type":"delivery.receipt","stageCode":"RECEIPT","displayName":"提交交付回执","config":{}}
  ]
}
$pipeline$::jsonb,'0000000000000000000000000000000000000000000000000000000000000004')
ON CONFLICT(id) DO NOTHING;

INSERT INTO pipeline_version(id,pipeline_id,version_no,status,definition,definition_sha256)
VALUES (5,1,5,'PUBLISHED',$pipeline$
{
  "pipelineCode":"archive-delivery",
  "version":5,
  "config":{"autoRollover":false},
  "nodes":[
    {"id":"download","scope":"SOURCE","type":"archive.download","stageCode":"ACQUIRE","displayName":"获取源包","config":{}},
    {"id":"repair","scope":"SOURCE","type":"python.repair","stageCode":"TRANSFORM","displayName":"修复归档","config":{"ruleSet":"fix-archive-legacy-v1"}},
    {"id":"qualify","scope":"SOURCE","type":"manifest.qualify","stageCode":"QUALIFY","displayName":"校验产物","config":{}},
    {"id":"group","scope":"SOURCE","type":"output.group","stageCode":"GROUP","displayName":"形成交付包","config":{}},
    {"id":"transfer","scope":"OUTPUT","type":"local.upload","stageCode":"TRANSFER","displayName":"写入本地交付存储","config":{}},
    {"id":"receipt","scope":"OUTPUT","type":"delivery.receipt","stageCode":"RECEIPT","displayName":"提交交付回执","config":{}}
  ]
}
$pipeline$::jsonb,'0000000000000000000000000000000000000000000000000000000000000005')
ON CONFLICT(id) DO NOTHING;

SELECT setval('pipeline_version_id_seq',(SELECT max(id) FROM pipeline_version),true);
CREATE INDEX IF NOT EXISTS idx_source_task_execution ON source_package(task_id,execution_status,id);
CREATE INDEX IF NOT EXISTS idx_output_task_execution ON output_package(task_id,execution_status,id);
CREATE INDEX IF NOT EXISTS idx_output_task_rollover ON output_package(task_id,delivery_date,archive_date,production_group_id);
