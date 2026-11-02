UPDATE pipeline_version SET definition=jsonb_set(definition,'{config}',
  '{"crrWaitSeconds":180,"autoRollover":false,"ftpCleanBeforeDownload":false}'::jsonb,true)
WHERE id=1 AND version_no=1;
