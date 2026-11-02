ALTER TABLE pipeline_version ALTER COLUMN published_at DROP NOT NULL;
INSERT INTO pipeline_version(id,pipeline_id,version_no,status,definition,definition_sha256,published_at)
SELECT 3,pipeline_id,3,'DRAFT',definition,definition_sha256,NULL FROM pipeline_version WHERE id=2;
ALTER TABLE source_package DROP COLUMN definition_snapshot;
SELECT setval('pipeline_version_id_seq',(SELECT max(id) FROM pipeline_version),true);
