SET search_path TO archive_delivery;

ALTER TABLE delivery_submit DROP COLUMN idempotency_key;
ALTER TABLE delivery_submit ADD CONSTRAINT uk_submit_delivery_batch UNIQUE(delivery_batch);
COMMENT ON COLUMN delivery_submit.delivery_batch IS '全局唯一交付批次，同时作为提交幂等键和请求编号命名空间。';
COMMENT ON COLUMN delivery_submit.target_strategy IS 'schemaVersion=1 的 targets 快照；每项仅含 vendorCode/targetCount。';

ALTER TABLE delivery_entity ADD CONSTRAINT uk_entity_vendor_code UNIQUE(vendor_code);
COMMENT ON COLUMN delivery_entity.vendor_code IS '全局唯一厂商稳定编码；提交目标使用该编码而非数据库 ID。';

ALTER TABLE delivery_task ADD CONSTRAINT uk_task_id_delivery UNIQUE(task_id,delivery_id);
ALTER TABLE source_data ADD CONSTRAINT uk_source_id_delivery UNIQUE(data_id,delivery_id);

CREATE TABLE delivery_mapping (
  task_id bigint NOT NULL,
  delivery_id bigint NOT NULL,
  data_id bigint NOT NULL REFERENCES source_data(data_id),
  source_seq int NOT NULL CHECK(source_seq>0),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(task_id,source_seq),
  UNIQUE(task_id,data_id),
  FOREIGN KEY(task_id,delivery_id) REFERENCES delivery_task(task_id,delivery_id),
  FOREIGN KEY(data_id,delivery_id) REFERENCES source_data(data_id,delivery_id)
);
CREATE INDEX idx_mapping_data ON delivery_mapping(data_id,task_id);
CREATE INDEX idx_mapping_delivery ON delivery_mapping(delivery_id,task_id,source_seq);
COMMENT ON TABLE delivery_mapping IS '任务与来源包的有序映射；一个合并任务关联多个 source_data，跨日新任务复制同一组映射。';
COMMENT ON COLUMN delivery_mapping.task_id IS 'delivery_task 外键，一个任务可关联多个来源包。';
COMMENT ON COLUMN delivery_mapping.delivery_id IS '冗余提交外键，复合外键保证任务和来源属于同一 delivery_id。';
COMMENT ON COLUMN delivery_mapping.data_id IS 'source_data 外键，保留每个原始来源包事实。';
COMMENT ON COLUMN delivery_mapping.source_seq IS '任务内来源顺序，从 1 开始；下载、合并和重试均按此顺序执行。';
COMMENT ON COLUMN delivery_mapping.created_at IS '来源映射创建时间。';

DROP INDEX uk_task_active_artifact;
DROP INDEX uk_task_active_target_seq;
ALTER TABLE delivery_task DROP COLUMN data_id;
ALTER TABLE delivery_task DROP COLUMN target_seq;
ALTER TABLE delivery_task DROP COLUMN artifact_seq;
ALTER TABLE delivery_task DROP COLUMN original_file_name;
ALTER TABLE delivery_task DROP COLUMN sha256;
ALTER TABLE delivery_task DROP COLUMN superseded_by_task_id;
ALTER TABLE delivery_task DROP COLUMN invalidated_at;
ALTER TABLE delivery_task DROP COLUMN invalidation_reason;
ALTER TABLE delivery_task ADD COLUMN artifacts jsonb NOT NULL DEFAULT '[]';
ALTER TABLE delivery_task ADD COLUMN metadata_ids jsonb NOT NULL DEFAULT '[]';
ALTER TABLE delivery_task ADD CONSTRAINT ck_task_artifacts_array CHECK(jsonb_typeof(artifacts)='array');
ALTER TABLE delivery_task ADD CONSTRAINT ck_task_metadata_ids_array CHECK(jsonb_typeof(metadata_ids)='array');
COMMENT ON TABLE delivery_task IS '一个合并交付包面向一个实体的执行原子；来源只经 delivery_mapping 关联，跨日以事件记录新旧 task_id。';
COMMENT ON COLUMN delivery_task.new_file_name IS '当前合并交付包的安全文件名，也是外部路径的唯一文件名事实。';
COMMENT ON COLUMN delivery_task.size_bytes IS '修复后合并交付包字节数；repair 前为来源压缩包字节数合计。';
COMMENT ON COLUMN delivery_task.token_count IS '修复后合并交付包 token 数量/大小；repair 前为来源值合计。';
COMMENT ON COLUMN delivery_task.artifacts IS '来源/交付产物对数组；每项仅含 dataId/sourceArtifact/deliveryArtifact，repair 前 deliveryArtifact 为 JSON null。';
COMMENT ON COLUMN delivery_task.metadata_ids IS '修复后请求 metadata.json 的 requestId 字符串数组，按 repair 映射顺序保存且数组内唯一。';

ALTER TABLE delivery_task DROP CONSTRAINT delivery_task_execution_status_check;
ALTER TABLE delivery_task ADD CONSTRAINT delivery_task_execution_status_check
  CHECK(execution_status IN('READY','RUNNING','WAITING','RETRY_WAIT','BLOCKED','PAUSED','SUCCEEDED','EXPIRED','CANCELLED'));
COMMENT ON COLUMN delivery_task.execution_status IS 'READY/RUNNING/WAITING/RETRY_WAIT/BLOCKED/PAUSED/SUCCEEDED/EXPIRED/CANCELLED；PAUSED 只在节点边界设置。';
ALTER TABLE delivery_submit DROP CONSTRAINT delivery_submit_status_check;
ALTER TABLE delivery_submit ADD CONSTRAINT delivery_submit_status_check
  CHECK(status IN('SUBMITTED','DISCOVERING','PLANNING','PROCESSING','WAITING','RETRYING','BLOCKED','PAUSED',
                  'PARTIALLY_DELIVERED','DELIVERED','COMPLETED_WITH_CANCELLATIONS','CANCELLED'));
COMMENT ON COLUMN delivery_submit.status IS '聚合状态；节点边界暂停为 PAUSED，同批次原样再次提交可继续；全部有效任务成功才 DELIVERED。';

ALTER TABLE request_id_sequence ADD CONSTRAINT fk_request_sequence_batch
  FOREIGN KEY(request_namespace) REFERENCES delivery_submit(delivery_batch);
ALTER TABLE request_id_allocation ADD CONSTRAINT fk_request_allocation_batch
  FOREIGN KEY(request_namespace) REFERENCES delivery_submit(delivery_batch);
COMMENT ON COLUMN request_id_sequence.request_namespace IS 'delivery_submit.delivery_batch 外键；每个全局唯一批次使用独立请求编号序列。';
COMMENT ON COLUMN request_id_allocation.request_namespace IS 'delivery_submit.delivery_batch 外键；repair 重试在同批次命名空间复用原号段。';
COMMENT ON COLUMN source_data.request_namespace IS '请求编号命名空间，执行 repair 时固化为所属 delivery_submit.delivery_batch。';

CREATE OR REPLACE FUNCTION archive_delivery.validate_task_artifacts() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE pair jsonb; artifact jsonb; metadata_id jsonb;
BEGIN
  IF jsonb_typeof(NEW.artifacts)<>'array' OR jsonb_typeof(NEW.metadata_ids)<>'array' THEN
    RAISE EXCEPTION 'task artifacts and metadata_ids must be arrays';
  END IF;
  FOR pair IN SELECT value FROM jsonb_array_elements(NEW.artifacts)
  LOOP
    IF jsonb_typeof(pair)<>'object' OR NOT(pair ?& ARRAY['dataId','sourceArtifact','deliveryArtifact'])
       OR EXISTS(SELECT 1 FROM jsonb_object_keys(pair) k WHERE k NOT IN('dataId','sourceArtifact','deliveryArtifact'))
       OR (pair->>'dataId')::bigint<=0 OR jsonb_typeof(pair->'sourceArtifact')<>'object'
       OR jsonb_typeof(pair->'deliveryArtifact') NOT IN('object','null') THEN
      RAISE EXCEPTION 'task artifact pair violates the mapping contract';
    END IF;
    FOREACH artifact IN ARRAY ARRAY[pair->'sourceArtifact',pair->'deliveryArtifact']
    LOOP
      IF jsonb_typeof(artifact)='null' THEN CONTINUE; END IF;
      IF NOT(artifact ?& ARRAY['artifactSeq','originalFileName','newFileName','sizeBytes','sha256','token','file_mapping'])
         OR EXISTS(SELECT 1 FROM jsonb_object_keys(artifact) k WHERE k NOT IN
           ('artifactSeq','originalFileName','newFileName','sizeBytes','sha256','token','file_mapping')) THEN
        RAISE EXCEPTION 'task artifact must contain exactly the seven contracted fields';
      END IF;
      IF (artifact->>'artifactSeq')::int<=0 OR (artifact->>'sizeBytes')::bigint<0 OR (artifact->>'token')::bigint<0
         OR artifact->>'sha256'!~'^[0-9a-f]{64}$' OR jsonb_typeof(artifact->'file_mapping')<>'object'
         OR artifact->>'originalFileName' LIKE '%/%' OR artifact->>'originalFileName' LIKE '%..%'
         OR artifact->>'newFileName' LIKE '%/%' OR artifact->>'newFileName' LIKE '%..%' THEN
        RAISE EXCEPTION 'task artifact values violate the delivery contract';
      END IF;
    END LOOP;
  END LOOP;
  IF EXISTS(SELECT 1 FROM jsonb_array_elements(NEW.artifacts) p GROUP BY p.value->>'dataId' HAVING count(*)>1) THEN
    RAISE EXCEPTION 'dataId must be unique within task artifacts';
  END IF;
  FOR metadata_id IN SELECT value FROM jsonb_array_elements(NEW.metadata_ids)
  LOOP
    IF jsonb_typeof(metadata_id)<>'string' OR length(trim(both '"' from metadata_id::text))=0 THEN
      RAISE EXCEPTION 'metadata_ids must contain non-empty requestId strings';
    END IF;
  END LOOP;
  IF EXISTS(SELECT 1 FROM jsonb_array_elements(NEW.metadata_ids) m GROUP BY m.value::text HAVING count(*)>1) THEN
    RAISE EXCEPTION 'metadata_ids must be unique within a task';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_task_artifacts BEFORE INSERT OR UPDATE ON delivery_task
  FOR EACH ROW EXECUTE FUNCTION archive_delivery.validate_task_artifacts();
COMMENT ON FUNCTION archive_delivery.validate_task_artifacts() IS '校验任务来源/交付产物对、严格七字段产物、安全值以及 metadata requestId 数组。';
