SET search_path TO archive_delivery;

ALTER TABLE delivery_run RENAME TO delivery_task;
ALTER TABLE delivery_task RENAME COLUMN run_code TO task_code;
ALTER TABLE delivery_task RENAME COLUMN oss_uri TO source_uri;
ALTER TABLE delivery_task ADD COLUMN source_id bigint REFERENCES ingest_source(id);
ALTER TABLE delivery_task ADD COLUMN discovery_completed_at timestamptz;
ALTER TABLE delivery_task ADD COLUMN completed_at timestamptz;
ALTER TABLE delivery_task ALTER COLUMN status TYPE varchar(40);
UPDATE delivery_task t SET source_id=s.id FROM ingest_source s
WHERE s.bucket_ref=t.bucket_name AND s.object_prefix=t.object_prefix;
UPDATE delivery_task SET status='PLANNING',discovery_completed_at=COALESCE(discovery_completed_at,updated_at)
WHERE status='DISCOVERED';
ALTER TABLE delivery_task ALTER COLUMN source_id SET NOT NULL;
ALTER TABLE delivery_task DROP COLUMN bucket_name;
ALTER TABLE delivery_task DROP COLUMN object_prefix;
ALTER TABLE delivery_task ADD CONSTRAINT ck_delivery_task_status CHECK (status IN
  ('SUBMITTED','DISCOVERING','PLANNING','PROCESSING','WAITING','RETRYING','BLOCKED','DELIVERED',
   'COMPLETED_WITH_CANCELLATIONS','CANCELLED'));

-- V10 本地调试允许无 run 的源包；为每个此类资产创建可追溯 legacy task，禁止回填时丢弃节点历史。
INSERT INTO delivery_task(task_code,source_id,source_uri,idempotency_key,job_date,pipeline_version_id,status,discovery_completed_at)
SELECT 'TASK-PENDING-LEGACY-'||s.id,s.source_id,'oss://'||s.bucket_name||'/'||s.object_key,
  'legacy-source-'||s.id,COALESCE(s.process_date,s.discovered_at::date),s.pipeline_version_id,'PLANNING',s.discovered_at
FROM source_package s WHERE s.run_id IS NULL;
UPDATE delivery_task SET task_code='TASK-LEGACY-'||id WHERE task_code LIKE 'TASK-PENDING-LEGACY-%';
UPDATE source_package s SET run_id=(SELECT t.id FROM delivery_task t WHERE t.idempotency_key='legacy-source-'||s.id)
WHERE s.run_id IS NULL;
UPDATE output_package p SET run_id=(SELECT s.run_id FROM source_package s WHERE s.id=p.source_package_id)
WHERE p.run_id IS NULL;
UPDATE production_group g SET run_id=(SELECT s.run_id FROM output_package p JOIN source_package s ON s.id=p.source_package_id
  WHERE p.production_group_id=g.id LIMIT 1) WHERE g.run_id IS NULL;

ALTER TABLE source_package RENAME COLUMN run_id TO task_id;
ALTER TABLE output_package RENAME COLUMN run_id TO task_id;
ALTER TABLE production_group RENAME COLUMN run_id TO task_id;
ALTER TABLE operation_audit RENAME COLUMN run_id TO task_id;

ALTER TABLE number_counter RENAME TO request_id_sequence;
ALTER TABLE request_id_sequence ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE number_reservation RENAME TO request_id_allocation;

ALTER TABLE package_node_run RENAME TO delivery_node_task;
ALTER TABLE package_event RENAME COLUMN run_id TO node_task_id;

ALTER TABLE source_package RENAME COLUMN current_node TO current_node_id;
ALTER TABLE source_package RENAME COLUMN run_status TO execution_status;
ALTER TABLE source_package RENAME COLUMN object_version TO source_version;
ALTER TABLE source_package ADD COLUMN source_uri varchar(1280);
ALTER TABLE source_package ADD COLUMN provider_metadata jsonb NOT NULL DEFAULT '{}';
ALTER TABLE source_package ADD COLUMN definition_snapshot jsonb;
ALTER TABLE source_package ADD COLUMN current_node_task_id bigint REFERENCES delivery_node_task(id);
ALTER TABLE source_package ADD COLUMN waiting_reason varchar(128);
ALTER TABLE source_package ADD COLUMN completed_at timestamptz;
UPDATE source_package SET source_uri='oss://'||bucket_name||'/'||object_key,
  provider_metadata=jsonb_strip_nulls(jsonb_build_object('schemaVersion',1,'etag',object_etag)),
  definition_snapshot=(SELECT definition FROM pipeline_version pv WHERE pv.id=source_package.pipeline_version_id),
  waiting_reason=CASE WHEN production_line_id IS NULL THEN 'CAPACITY' ELSE NULL END,
  execution_status=CASE WHEN production_line_id IS NULL THEN 'WAITING' ELSE execution_status END,
  completed_at=CASE WHEN execution_status='SUCCEEDED' THEN updated_at ELSE NULL END;
ALTER TABLE source_package ALTER COLUMN source_uri SET NOT NULL;
ALTER TABLE source_package ALTER COLUMN definition_snapshot SET NOT NULL;
ALTER TABLE source_package DROP COLUMN status;
ALTER TABLE source_package DROP COLUMN bucket_name;
ALTER TABLE source_package DROP COLUMN object_key;
ALTER TABLE source_package DROP COLUMN object_etag;
ALTER TABLE source_package ADD CONSTRAINT ck_source_execution_status CHECK (execution_status IN
  ('READY','RUNNING','WAITING','RETRY_WAIT','BLOCKED','SUCCEEDED','CANCELLED'));
CREATE UNIQUE INDEX uk_source_task_asset ON source_package(task_id,source_uri,source_version) WHERE task_id IS NOT NULL;
CREATE UNIQUE INDEX uk_source_unscoped_asset ON source_package(source_uri,source_version) WHERE task_id IS NULL;

ALTER TABLE output_package RENAME COLUMN current_node TO current_node_id;
ALTER TABLE output_package RENAME COLUMN run_status TO execution_status;
ALTER TABLE output_package RENAME COLUMN delivered_at TO completed_at;
ALTER TABLE output_package ADD COLUMN definition_snapshot jsonb;
ALTER TABLE output_package ADD COLUMN current_node_task_id bigint REFERENCES delivery_node_task(id);
ALTER TABLE output_package ADD COLUMN waiting_reason varchar(128);
ALTER TABLE output_package ADD COLUMN artifacts jsonb NOT NULL DEFAULT '[]';
ALTER TABLE output_package ADD COLUMN delivery_metadata jsonb NOT NULL DEFAULT '{"schemaVersion":1}';
UPDATE output_package p SET definition_snapshot=(SELECT definition FROM pipeline_version pv WHERE pv.id=p.pipeline_version_id),
  delivery_metadata=jsonb_build_object('schemaVersion',1,'receipt',
    CASE WHEN p.receipt_id IS NULL THEN NULL ELSE jsonb_build_object('receiptId',p.receipt_id) END),
  artifacts=COALESCE((SELECT jsonb_agg(jsonb_build_object(
      'artifactSeq',a.artifact_seq,
      'originalFileName',a.original_file_name,
      'newFileName',a.new_file_name,
      'sizeBytes',a.size_bytes,
      'sha256',a.sha256,
      'metadata',a.metadata,
      'locations',jsonb_strip_nulls(jsonb_build_object(
        'hzUpload',CASE WHEN a.hz_path IS NULL THEN NULL ELSE jsonb_build_object('uri',a.hz_path,'sizeBytes',a.size_bytes,'sha256',a.sha256) END,
        'sgVerify',CASE WHEN a.sg_path IS NULL THEN NULL ELSE jsonb_build_object('uri',a.sg_path,'sizeBytes',a.size_bytes,'sha256',a.sha256) END,
        'sgDownload',CASE WHEN p.sg_download_path IS NULL THEN NULL ELSE jsonb_build_object('uri',p.sg_download_path,'sha256',a.sha256) END,
        'publish',CASE WHEN a.published_path IS NULL THEN NULL ELSE jsonb_build_object('uri',a.published_path,'sha256',a.sha256) END
      ))
    ) ORDER BY a.artifact_seq) FROM output_artifact a WHERE a.output_package_id=p.id),'[]'::jsonb);
ALTER TABLE output_package ALTER COLUMN definition_snapshot SET NOT NULL;
ALTER TABLE output_package DROP COLUMN status;
ALTER TABLE output_package DROP COLUMN hz_path;
ALTER TABLE output_package DROP COLUMN sg_source_path;
ALTER TABLE output_package DROP COLUMN sg_download_path;
ALTER TABLE output_package DROP COLUMN published_path;
ALTER TABLE output_package DROP COLUMN receipt_id;
ALTER TABLE output_package DROP COLUMN hz_uploaded_at;
ALTER TABLE output_package ADD CONSTRAINT ck_output_execution_status CHECK (execution_status IN
  ('READY','RUNNING','WAITING','RETRY_WAIT','BLOCKED','SUCCEEDED','CANCELLED'));
ALTER TABLE output_package ADD CONSTRAINT ck_output_artifacts_array CHECK (jsonb_typeof(artifacts)='array');
DROP TABLE output_artifact;

CREATE TABLE source_request (
  id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  task_id bigint REFERENCES delivery_task(id),
  source_package_id bigint NOT NULL REFERENCES source_package(id),
  output_package_id bigint REFERENCES output_package(id),
  allocation_id bigint REFERENCES request_id_allocation(id),
  member_key varchar(256) NOT NULL,
  original_request_id varchar(256),
  new_request_id varchar(256),
  request_namespace varchar(128) NOT NULL DEFAULT '',
  original_locator jsonb NOT NULL DEFAULT '{}',
  output_locator jsonb NOT NULL DEFAULT '{}',
  qualification varchar(32) NOT NULL DEFAULT 'PENDING',
  filter_rule_code varchar(128),
  filter_reason varchar(1024),
  affected_count bigint NOT NULL DEFAULT 0 CHECK (affected_count>=0),
  rule_version varchar(128),
  mapping_rule_version varchar(128),
  discovered_at timestamptz NOT NULL DEFAULT now(),
  grouped_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(source_package_id,member_key),
  UNIQUE(request_namespace,new_request_id),
  CHECK (qualification IN ('PENDING','QUALIFIED','QUALIFIED_WITH_SKIPS','UNQUALIFIED'))
);
INSERT INTO source_request(id,task_id,source_package_id,output_package_id,allocation_id,member_key,
  original_request_id,new_request_id,request_namespace,original_locator,output_locator,qualification,
  filter_rule_code,filter_reason,rule_version,mapping_rule_version,discovered_at,grouped_at)
SELECT sm.id,sp.task_id,sm.source_package_id,rm.output_package_id,rm.reservation_id,sm.member_key,
  COALESCE(rm.original_request_id,sm.original_request_id),rm.new_request_id,COALESCE(ra.namespace,''),
  jsonb_build_object('schemaVersion',1,'sourceUri',sp.source_uri,'memberPath',sm.original_path),
  jsonb_build_object('schemaVersion',1,'manifest',sm.repaired_manifest),sm.qualification,
  sm.filter_rule_code,sm.filter_reason,sm.rule_version,rm.mapping_rule_version,rm.discovered_at,rm.grouped_at
FROM source_member sm
JOIN source_package sp ON sp.id=sm.source_package_id
LEFT JOIN request_mapping rm ON rm.source_member_id=sm.id
LEFT JOIN request_id_allocation ra ON ra.id=rm.reservation_id;
SELECT setval('source_request_id_seq',COALESCE((SELECT max(id) FROM source_request),1),true);
DROP TABLE request_mapping;
DROP TABLE source_member;
CREATE INDEX idx_source_request_ungrouped ON source_request(discovered_at,id)
  WHERE output_package_id IS NULL AND qualification IN ('QUALIFIED','QUALIFIED_WITH_SKIPS');

ALTER TABLE delivery_node_task ADD COLUMN task_id bigint REFERENCES delivery_task(id);
ALTER TABLE delivery_node_task ADD COLUMN node_scope varchar(16);
ALTER TABLE delivery_node_task ADD COLUMN stage_code varchar(64);
ALTER TABLE delivery_node_task ADD COLUMN heartbeat_at timestamptz;
ALTER TABLE delivery_node_task ADD COLUMN pid bigint;
ALTER TABLE delivery_node_task ADD COLUMN progress_completed bigint NOT NULL DEFAULT 0 CHECK(progress_completed>=0);
ALTER TABLE delivery_node_task ADD COLUMN progress_total bigint NOT NULL DEFAULT 0 CHECK(progress_total>=0);
ALTER TABLE delivery_node_task ADD COLUMN progress_bytes bigint NOT NULL DEFAULT 0 CHECK(progress_bytes>=0);
ALTER TABLE delivery_node_task DROP CONSTRAINT IF EXISTS package_node_run_check;
DROP INDEX IF EXISTS uk_source_node_attempt;
DROP INDEX IF EXISTS uk_output_node_attempt;
UPDATE delivery_node_task SET node_scope=CASE WHEN source_package_id IS NOT NULL THEN 'SOURCE' ELSE 'OUTPUT' END;
UPDATE delivery_node_task n SET task_id=p.task_id FROM output_package p
WHERE n.output_package_id=p.id AND n.task_id IS NULL;
UPDATE delivery_node_task n SET source_package_id=p.source_package_id FROM output_package p
WHERE n.output_package_id=p.id AND n.source_package_id IS NULL;
UPDATE delivery_node_task n SET task_id=s.task_id FROM source_package s
WHERE n.source_package_id=s.id AND n.task_id IS NULL;
UPDATE delivery_node_task n SET output_package_id=(SELECT min(p.id) FROM output_package p WHERE p.source_package_id=n.source_package_id)
WHERE n.node_scope='SOURCE' AND n.output_package_id IS NULL
  AND (SELECT count(*) FROM output_package p WHERE p.source_package_id=n.source_package_id)=1;
UPDATE delivery_node_task n SET stage_code=COALESCE((SELECT node->>'stageCode'
  FROM pipeline_version pv,jsonb_array_elements(pv.definition->'nodes') node
  WHERE pv.id=(SELECT pipeline_version_id FROM source_package WHERE id=n.source_package_id)
    AND node->>'id'=n.node_id LIMIT 1),n.node_id);
ALTER TABLE delivery_node_task ALTER COLUMN task_id SET NOT NULL;
ALTER TABLE delivery_node_task ALTER COLUMN source_package_id SET NOT NULL;
ALTER TABLE delivery_node_task ALTER COLUMN node_scope SET NOT NULL;
ALTER TABLE delivery_node_task DROP CONSTRAINT IF EXISTS package_node_run_check;
ALTER TABLE delivery_node_task ADD CONSTRAINT ck_node_scope_output CHECK(node_scope IN('SOURCE','OUTPUT')
  AND (node_scope<>'OUTPUT' OR output_package_id IS NOT NULL));
ALTER TABLE delivery_node_task ADD CONSTRAINT ck_node_task_status CHECK(status IN
  ('RUNNING','SUCCEEDED','WAITING','FAILED','CANCELLED'));
DROP INDEX IF EXISTS uk_source_node_attempt;
DROP INDEX IF EXISTS uk_output_node_attempt;
CREATE UNIQUE INDEX uk_source_node_task_attempt ON delivery_node_task(source_package_id,node_id,attempt) WHERE node_scope='SOURCE';
CREATE UNIQUE INDEX uk_output_node_task_attempt ON delivery_node_task(output_package_id,node_id,attempt) WHERE node_scope='OUTPUT';
CREATE INDEX idx_node_task_output ON delivery_node_task(task_id,output_package_id,started_at,id);
CREATE INDEX idx_node_task_running ON delivery_node_task(status,heartbeat_at) WHERE status='RUNNING';

CREATE TABLE delivery_node_task_output (
  node_task_id bigint NOT NULL REFERENCES delivery_node_task(id),
  output_package_id bigint NOT NULL REFERENCES output_package(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(node_task_id,output_package_id)
);

ALTER TABLE package_event ADD COLUMN task_id bigint REFERENCES delivery_task(id);
ALTER TABLE package_event ADD COLUMN source_package_id bigint REFERENCES source_package(id);
ALTER TABLE package_event ADD COLUMN output_package_id bigint REFERENCES output_package(id);
ALTER TABLE package_event ADD COLUMN production_group_id bigint REFERENCES production_group(id);
ALTER TABLE package_event ADD COLUMN dedup_key varchar(256);
ALTER TABLE package_event ADD COLUMN category varchar(32) NOT NULL DEFAULT 'LIFECYCLE';
ALTER TABLE package_event ADD COLUMN severity varchar(16) NOT NULL DEFAULT 'INFO';
ALTER TABLE package_event ADD COLUMN node_id varchar(64);
ALTER TABLE package_event ADD COLUMN stage_code varchar(64);
ALTER TABLE package_event ADD COLUMN reason_code varchar(128);
ALTER TABLE package_event ADD COLUMN affected_count bigint NOT NULL DEFAULT 0 CHECK(affected_count>=0);
ALTER TABLE package_event ADD COLUMN trace_id varchar(128);
ALTER TABLE package_event ADD COLUMN worker_instance_id varchar(128);
ALTER TABLE package_event RENAME COLUMN event_time TO occurred_at;
ALTER TABLE package_event ADD COLUMN recorded_at timestamptz NOT NULL DEFAULT now();
UPDATE package_event e SET task_id=n.task_id,source_package_id=n.source_package_id,
  output_package_id=n.output_package_id,node_id=n.node_id,stage_code=n.stage_code,
  worker_instance_id=n.worker_instance_id,dedup_key='legacy-event-'||e.id,
  category=CASE WHEN e.event_type='FAILED' THEN 'SYSTEM' ELSE 'LIFECYCLE' END,
  severity=CASE WHEN e.event_type='FAILED' THEN 'ERROR' ELSE 'INFO' END
FROM delivery_node_task n WHERE n.id=e.node_task_id;
ALTER TABLE package_event ALTER COLUMN task_id SET NOT NULL;
ALTER TABLE package_event ALTER COLUMN dedup_key SET NOT NULL;
ALTER TABLE package_event ADD CONSTRAINT uk_package_event_dedup UNIQUE(task_id,dedup_key);
ALTER TABLE package_event ADD CONSTRAINT ck_package_event_category CHECK(category IN
  ('LIFECYCLE','PROGRESS','DATA_QUALITY','OPERATION','SYSTEM','SECURITY'));
ALTER TABLE package_event ADD CONSTRAINT ck_package_event_severity CHECK(severity IN('INFO','WARN','ERROR','CRITICAL'));
CREATE INDEX idx_package_event_output ON package_event(task_id,output_package_id,occurred_at DESC,id);

ALTER TABLE ingest_source ADD COLUMN source_name varchar(128);
ALTER TABLE ingest_source ADD COLUMN channel_code varchar(64) NOT NULL DEFAULT 'ALIBABA_CLOUD';
ALTER TABLE ingest_source ADD COLUMN asset_locator jsonb;
ALTER TABLE ingest_source ADD COLUMN refresh_status varchar(16) NOT NULL DEFAULT 'IDLE';
ALTER TABLE ingest_source ADD COLUMN last_refresh_started_at timestamptz;
ALTER TABLE ingest_source ADD COLUMN last_refreshed_at timestamptz;
ALTER TABLE ingest_source ADD COLUMN last_refresh_business_date date;
ALTER TABLE ingest_source ADD COLUMN deleted_at timestamptz;
ALTER TABLE ingest_source ADD COLUMN deleted_by varchar(64);
ALTER TABLE ingest_source ADD COLUMN delete_reason varchar(1024);
ALTER TABLE ingest_source ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE ingest_source ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
UPDATE ingest_source SET source_name=source_code,
  asset_locator=jsonb_build_object('schemaVersion',1,'bucket',bucket_ref,'prefix',object_prefix);
ALTER TABLE ingest_source ALTER COLUMN source_name SET NOT NULL;
ALTER TABLE ingest_source ALTER COLUMN asset_locator SET NOT NULL;
ALTER TABLE ingest_source DROP COLUMN bucket_ref;
ALTER TABLE ingest_source DROP COLUMN object_prefix;
ALTER TABLE ingest_source ADD CONSTRAINT ck_ingest_source_deleted CHECK(deleted_at IS NULL OR enabled=false);

ALTER TABLE production_line ADD COLUMN metadata jsonb NOT NULL DEFAULT '{}';
ALTER TABLE production_line ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE production_line ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
UPDATE production_line SET metadata=jsonb_build_object('provider',provider_code,'legacyBatch',legacy_batch);
ALTER TABLE production_line DROP COLUMN compute_host;
ALTER TABLE production_line DROP COLUMN ftp_host;
ALTER TABLE production_line DROP COLUMN target_root;

ALTER TABLE pipeline ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE pipeline_version ADD COLUMN created_by varchar(64) NOT NULL DEFAULT 'system';
ALTER TABLE pipeline_version ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE source_pipeline_binding ADD COLUMN created_by varchar(64) NOT NULL DEFAULT 'system';
ALTER TABLE operation_audit ADD COLUMN source_package_id bigint REFERENCES source_package(id);
ALTER TABLE operation_audit ADD COLUMN source_id bigint REFERENCES ingest_source(id);
ALTER TABLE operation_audit ADD COLUMN operator_id varchar(64) NOT NULL DEFAULT 'system';

CREATE TABLE ingest_source_refresh (
  id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  source_id bigint NOT NULL REFERENCES ingest_source(id),
  trigger_type varchar(16) NOT NULL CHECK(trigger_type IN('MANUAL','SCHEDULED')),
  refresh_mode varchar(16) NOT NULL CHECK(refresh_mode IN('INCREMENTAL','FULL')),
  business_date date NOT NULL,
  status varchar(16) NOT NULL CHECK(status IN('RUNNING','SUCCEEDED','FAILED')),
  discovered_count bigint NOT NULL DEFAULT 0 CHECK(discovered_count>=0),
  discovered_bytes bigint NOT NULL DEFAULT 0 CHECK(discovered_bytes>=0),
  started_at timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz,
  error_code varchar(128),
  error_detail text
);
CREATE UNIQUE INDEX uk_source_one_running_refresh ON ingest_source_refresh(source_id) WHERE status='RUNNING';
CREATE TABLE ingest_source_daily_stat (
  id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  source_id bigint NOT NULL REFERENCES ingest_source(id),
  stat_date date NOT NULL,
  object_count bigint NOT NULL CHECK(object_count>=0),
  object_bytes bigint NOT NULL CHECK(object_bytes>=0),
  new_object_count bigint NOT NULL CHECK(new_object_count>=0),
  removed_object_count bigint NOT NULL CHECK(removed_object_count>=0),
  successful_refresh_count integer NOT NULL DEFAULT 0 CHECK(successful_refresh_count>=0),
  failed_refresh_count integer NOT NULL DEFAULT 0 CHECK(failed_refresh_count>=0),
  last_refreshed_at timestamptz NOT NULL,
  UNIQUE(source_id,stat_date)
);

WITH facts AS (
  SELECT t.id,t.status AS old_status,t.discovery_completed_at,
    (SELECT count(*) FROM source_package s WHERE s.task_id=t.id AND s.production_line_id IS NULL) AS unplanned,
    (SELECT count(*) FROM source_package s WHERE s.task_id=t.id AND s.execution_status IN('READY','RUNNING')) +
    (SELECT count(*) FROM output_package p WHERE p.task_id=t.id AND p.execution_status IN('READY','RUNNING')) AS active,
    (SELECT count(*) FROM source_package s WHERE s.task_id=t.id AND s.execution_status='WAITING') +
    (SELECT count(*) FROM output_package p WHERE p.task_id=t.id AND p.execution_status='WAITING') AS waiting,
    (SELECT count(*) FROM source_package s WHERE s.task_id=t.id AND s.execution_status='RETRY_WAIT') +
    (SELECT count(*) FROM output_package p WHERE p.task_id=t.id AND p.execution_status='RETRY_WAIT') AS retrying,
    (SELECT count(*) FROM source_package s WHERE s.task_id=t.id AND s.execution_status='BLOCKED') +
    (SELECT count(*) FROM output_package p WHERE p.task_id=t.id AND p.execution_status='BLOCKED') AS blocked,
    (SELECT count(*) FROM output_package p WHERE p.task_id=t.id) AS outputs,
    (SELECT count(*) FROM output_package p WHERE p.task_id=t.id AND p.execution_status='CANCELLED') AS cancelled,
    (SELECT count(*) FROM output_package p WHERE p.task_id=t.id AND p.execution_status NOT IN('SUCCEEDED','CANCELLED')) AS unfinished,
    (SELECT count(*) FROM source_request r WHERE r.task_id=t.id AND r.output_package_id IS NULL
      AND r.qualification IN('QUALIFIED','QUALIFIED_WITH_SKIPS')) AS ungrouped
  FROM delivery_task t
), projected AS (
  SELECT id,CASE
    WHEN old_status='CANCELLED' THEN 'CANCELLED'
    WHEN discovery_completed_at IS NULL AND old_status='DISCOVERING' THEN 'DISCOVERING'
    WHEN discovery_completed_at IS NULL THEN 'SUBMITTED'
    WHEN outputs>0 AND unplanned=0 AND ungrouped=0 AND unfinished=0 AND cancelled>0 THEN 'COMPLETED_WITH_CANCELLATIONS'
    WHEN outputs>0 AND unplanned=0 AND ungrouped=0 AND unfinished=0 THEN 'DELIVERED'
    WHEN blocked>0 THEN 'BLOCKED'
    WHEN active>0 THEN 'PROCESSING'
    WHEN retrying>0 THEN 'RETRYING'
    WHEN waiting>0 THEN 'WAITING'
    ELSE 'PLANNING' END AS status
  FROM facts
)
UPDATE delivery_task t SET status=p.status,
  completed_at=CASE WHEN p.status IN('DELIVERED','COMPLETED_WITH_CANCELLATIONS','CANCELLED')
    THEN COALESCE(t.completed_at,now()) ELSE NULL END,updated_at=now()
FROM projected p WHERE p.id=t.id;

COMMENT ON TABLE delivery_task IS '一次交付处理快照；status 仅由 DeliveryTaskProjectionService 根据源包、请求和输出包事实聚合。';
COMMENT ON COLUMN delivery_task.status IS '聚合状态：SUBMITTED/DISCOVERING/PLANNING/PROCESSING/WAITING/RETRYING/BLOCKED/DELIVERED/COMPLETED_WITH_CANCELLATIONS/CANCELLED。';
COMMENT ON COLUMN delivery_task.source_uri IS '提交时固化的源 OSS URI；历史任务不随后续资产刷新改变。';
COMMENT ON COLUMN source_package.execution_status IS '统一执行状态：READY/RUNNING/WAITING/RETRY_WAIT/BLOCKED/SUCCEEDED/CANCELLED；SUCCEEDED 仅表示源 Pipeline 完成。';
COMMENT ON COLUMN source_package.current_node_task_id IS '当前活动或 WAITING 节点尝试；READY 和终态时为空。';
COMMENT ON COLUMN output_package.execution_status IS '统一执行状态；输出包 SUCCEEDED 对应 API 产品状态 DELIVERED。';
COMMENT ON COLUMN output_package.artifacts IS '物理产物 JSONB 数组；契约见模型文档 9.4，locations 以 Pipeline nodeId 为命名空间。';
COMMENT ON COLUMN output_package.delivery_metadata IS '交付扩展事实 JSONB；receipt 等键按节点命名空间隔离，不保存 Secret。';
COMMENT ON COLUMN source_request.original_locator IS '原始请求定位 JSONB，含 sourceUri/memberPath/responsePaths，契约见模型文档 9.2。';
COMMENT ON COLUMN source_request.output_locator IS '修复后包内定位 JSONB，不重复保存交付 URI，契约见模型文档 9.3。';
COMMENT ON COLUMN delivery_node_task.task_id IS '所属 delivery_task；每次节点尝试始终保留任务和源包双血缘。';
COMMENT ON COLUMN delivery_node_task.node_scope IS 'SOURCE/OUTPUT；OUTPUT 尝试必须具有 output_package_id，attempt 唯一索引按 scope 分离。';
COMMENT ON COLUMN delivery_node_task.status IS '节点尝试状态：RUNNING/SUCCEEDED/WAITING/FAILED/CANCELLED，不是包产品状态。';
COMMENT ON COLUMN package_event.category IS '事件分类：LIFECYCLE/PROGRESS/DATA_QUALITY/OPERATION/SYSTEM/SECURITY。';
COMMENT ON COLUMN package_event.dedup_key IS '任务内事件去重键；事件只追加，不允许用更新覆盖历史事实。';
COMMENT ON COLUMN request_id_sequence.next_value IS '命名空间下一个可分配请求号；通过 PG 行锁事务保证号段不重叠。';

DO $$
DECLARE row_record record;
BEGIN
  FOR row_record IN
    SELECT t.table_name FROM information_schema.tables t
    WHERE t.table_schema='archive_delivery' AND t.table_type='BASE TABLE'
      AND t.table_name<>'flyway_schema_history'
  LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_description d JOIN pg_class c ON c.oid=d.objoid
        JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='archive_delivery' AND c.relname=row_record.table_name AND d.objsubid=0) THEN
      EXECUTE format('COMMENT ON TABLE archive_delivery.%I IS %L',row_record.table_name,
        'Archive Delivery 最小 MVP 业务表；字段、血缘和状态契约见 2026-11-03 模型关系文档。');
    END IF;
  END LOOP;
  FOR row_record IN
    SELECT c.table_name,c.column_name FROM information_schema.columns c
    WHERE c.table_schema='archive_delivery' AND c.table_name<>'flyway_schema_history'
  LOOP
    IF NOT EXISTS (SELECT 1 FROM pg_description d JOIN pg_class c ON c.oid=d.objoid
        JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_attribute a ON a.attrelid=c.oid
        WHERE n.nspname='archive_delivery' AND c.relname=row_record.table_name
          AND a.attname=row_record.column_name AND d.objoid=c.oid AND d.objsubid=a.attnum) THEN
      EXECUTE format('COMMENT ON COLUMN archive_delivery.%I.%I IS %L',row_record.table_name,row_record.column_name,
        '字段 '||row_record.column_name||'；类型、可空性和业务契约见 2026-11-03 模型关系文档。');
    END IF;
  END LOOP;
END $$;
