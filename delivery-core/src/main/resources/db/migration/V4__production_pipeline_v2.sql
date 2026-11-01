SET search_path TO archive_delivery;

INSERT INTO pipeline_version(id,pipeline_id,version_no,status,definition,definition_sha256)
VALUES (2,1,2,'PUBLISHED',$pipeline$
{
  "pipelineCode":"archive-delivery",
  "version":2,
  "config":{
    "crrWaitSeconds":180,
    "autoRollover":false,
    "ftpCleanBeforeDownload":false
  },
  "nodes":[
    {"id":"download","scope":"SOURCE","type":"archive.download","config":{}},
    {"id":"repair","scope":"SOURCE","type":"python.repair","config":{"ruleSet":"fix-archive-legacy-v1"}},
    {"id":"qualify","scope":"SOURCE","type":"manifest.qualify","config":{}},
    {"id":"group","scope":"SOURCE","type":"output.group","config":{}},
    {"id":"hzUpload","scope":"OUTPUT","type":"oss.hz-upload","config":{"contentMd5":true}},
    {"id":"sgVerify","scope":"OUTPUT","type":"oss.sg-verify","config":{"pollSeconds":30,"maxWaitSeconds":7200}},
    {"id":"sgDownload","scope":"OUTPUT","type":"ssh.sg-download","config":{"commandTimeoutSeconds":900}},
    {"id":"publish","scope":"OUTPUT","type":"ssh.publish","config":{"commandTimeoutSeconds":60}},
    {"id":"receipt","scope":"OUTPUT","type":"delivery.receipt","config":{}}
  ]
}
$pipeline$::jsonb,'6c4a6d3f2fece8c97fccdd1fb8ba125a0117cfaacf2269f7d0c95feac17583a2')
ON CONFLICT(id) DO NOTHING;

UPDATE source_pipeline_binding SET pipeline_version_id=2,effective_from=now()
WHERE source_id=1 AND effective_to IS NULL;
SELECT setval('pipeline_version_id_seq',(SELECT max(id) FROM pipeline_version),true);
