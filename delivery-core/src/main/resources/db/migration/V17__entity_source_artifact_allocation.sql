SET search_path TO archive_delivery;

ALTER TABLE delivery_entity DROP CONSTRAINT uk_entity_vendor_code;
COMMENT ON COLUMN delivery_entity.vendor_code IS '厂商稳定编码，与 entity_code 联合唯一；同厂商多个实体目标在分配时作为一个整体连续组装。';
COMMENT ON COLUMN delivery_entity.entity_code IS '厂商内交付实体编码，与 vendor_code 联合唯一；提交同时传 vendorCode/entityCode，repair 使用 entityCode。';

ALTER TABLE source_data ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE source_data ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE source_data ADD COLUMN original_file_name varchar(512);
UPDATE source_data SET original_file_name=artifacts->0->>'originalFileName';
ALTER TABLE source_data ALTER COLUMN original_file_name SET NOT NULL;
ALTER TABLE source_data ADD COLUMN artifact jsonb;
UPDATE source_data SET artifact=artifacts->0;
ALTER TABLE source_data ALTER COLUMN artifact SET NOT NULL;
ALTER TABLE source_data DROP COLUMN artifacts;
ALTER TABLE source_data DROP COLUMN original_request_id;
ALTER TABLE source_data DROP COLUMN new_request_id;
ALTER TABLE source_data ADD CONSTRAINT ck_source_artifact_object CHECK(jsonb_typeof(artifact)='object');
COMMENT ON TABLE source_data IS '提交内原始来源包事实；保存定位、过滤、原始文件名和单个原始 artifact，不被合并交付产物覆盖。';
COMMENT ON COLUMN source_data.original_file_name IS '原始来源包安全文件名，与 artifact.originalFileName 一致。';
COMMENT ON COLUMN source_data.original_locator IS 'sourceUri/sourceVersion/cachePath/memberPath/responsePaths JSONB；cachePath 是同机共享源包缓存，其他执行组缺失时回退 OSS。';
COMMENT ON COLUMN source_data.request_namespace IS '请求编号命名空间，执行 repair 时固化为所属 delivery_submit.delivery_batch。';
COMMENT ON COLUMN source_data.size_bytes IS '原始来源包字节数，与 artifact.sizeBytes 一致。';
COMMENT ON COLUMN source_data.token_count IS '原始来源包 token 数量/大小，与 artifact.token 一致。';
COMMENT ON COLUMN source_data.sha256 IS '原始来源包 64 位小写 SHA-256，与 artifact.sha256 一致。';
COMMENT ON COLUMN source_data.artifact IS '单个原始产物 JSONB 对象；严格七字段 artifactSeq/originalFileName/newFileName/sizeBytes/sha256/token/file_mapping。';
COMMENT ON COLUMN source_data.created_at IS '来源事实创建时间，默认当前时间。';
COMMENT ON COLUMN source_data.updated_at IS '来源事实最近更新时间，默认当前时间并由更新触发器维护。';

ALTER TABLE delivery_mapping ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
COMMENT ON COLUMN delivery_mapping.updated_at IS '来源映射最近更新时间，默认当前时间。';

ALTER TABLE delivery_task ADD COLUMN entity_code varchar(64);
UPDATE delivery_task t SET entity_code=e.entity_code FROM delivery_entity e WHERE e.delivery_entity_id=t.delivery_entity_id;
ALTER TABLE delivery_task ALTER COLUMN entity_code SET NOT NULL;
COMMENT ON COLUMN delivery_task.entity_code IS '任务创建时固化的交付实体编码；repair 和看板使用该快照。';
COMMENT ON COLUMN delivery_task.artifacts IS '逐来源产物对数组；sourceArtifact 必须逐字节等于对应 source_data.artifact，deliveryArtifact 为同一合并包的来源级映射。';
COMMENT ON COLUMN delivery_task.size_bytes IS 'repair 前等于来源原始包字节数合计，repair 后等于合并 deliveryArtifact.sizeBytes。';
CREATE UNIQUE INDEX uk_task_active_delivery_file_name ON delivery_task(delivery_id,new_file_name)
  WHERE execution_status NOT IN('EXPIRED','CANCELLED');

DROP TRIGGER trg_source_artifacts ON source_data;
DROP FUNCTION archive_delivery.validate_source_artifacts();
CREATE FUNCTION archive_delivery.validate_source_artifact() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF jsonb_typeof(NEW.artifact)<>'object'
     OR NOT(NEW.artifact ?& ARRAY['artifactSeq','originalFileName','newFileName','sizeBytes','sha256','token','file_mapping'])
     OR EXISTS(SELECT 1 FROM jsonb_object_keys(NEW.artifact) k WHERE k NOT IN
       ('artifactSeq','originalFileName','newFileName','sizeBytes','sha256','token','file_mapping')) THEN
    RAISE EXCEPTION 'source artifact must contain exactly the seven contracted fields';
  END IF;
  IF (NEW.artifact->>'artifactSeq')::int<>1 OR NEW.artifact->>'originalFileName'<>NEW.original_file_name
     OR (NEW.artifact->>'sizeBytes')::bigint<>NEW.size_bytes OR (NEW.artifact->>'token')::bigint<>NEW.token_count
     OR NEW.artifact->>'sha256' IS DISTINCT FROM NEW.sha256 OR NEW.artifact->>'sha256'!~'^[0-9a-f]{64}$'
     OR jsonb_typeof(NEW.artifact->'file_mapping')<>'object'
     OR NEW.original_file_name LIKE '%/%' OR NEW.original_file_name LIKE '%..%'
     OR NEW.artifact->>'newFileName' LIKE '%/%' OR NEW.artifact->>'newFileName' LIKE '%..%' THEN
    RAISE EXCEPTION 'source artifact values must match the original source_data facts';
  END IF;
  IF TG_OP='UPDATE' THEN NEW.updated_at=now(); END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_source_artifact BEFORE INSERT OR UPDATE ON source_data
  FOR EACH ROW EXECUTE FUNCTION archive_delivery.validate_source_artifact();
COMMENT ON FUNCTION archive_delivery.validate_source_artifact() IS '强制单个原始 artifact 七字段白名单，并保证文件名、size/token/SHA 与 source_data 完全一致。';

CREATE OR REPLACE FUNCTION archive_delivery.validate_task_artifacts() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  pair jsonb;
  delivery jsonb;
  shared_delivery jsonb;
  source_artifact jsonb;
  source_size bigint;
  source_token bigint;
  expected_size bigint:=0;
  expected_token bigint:=0;
  mapping record;
  metadata_id jsonb;
  metadata_values jsonb:='[]'::jsonb;
BEGIN
  IF jsonb_typeof(NEW.artifacts)<>'array' OR jsonb_array_length(NEW.artifacts)=0
     OR jsonb_typeof(NEW.metadata_ids)<>'array' THEN
    RAISE EXCEPTION 'task artifacts must be a non-empty array and metadata_ids must be an array';
  END IF;
  FOR pair IN SELECT value FROM jsonb_array_elements(NEW.artifacts)
  LOOP
    IF jsonb_typeof(pair)<>'object' OR NOT(pair ?& ARRAY['dataId','sourceArtifact','deliveryArtifact'])
       OR EXISTS(SELECT 1 FROM jsonb_object_keys(pair) k WHERE k NOT IN('dataId','sourceArtifact','deliveryArtifact')) THEN
      RAISE EXCEPTION 'task artifact pair violates the mapping contract';
    END IF;
    SELECT artifact,size_bytes,token_count INTO source_artifact,source_size,source_token
      FROM archive_delivery.source_data WHERE data_id=(pair->>'dataId')::bigint AND delivery_id=NEW.delivery_id;
    IF NOT FOUND OR pair->'sourceArtifact'<>source_artifact THEN
      RAISE EXCEPTION 'sourceArtifact must equal source_data.artifact in the same delivery';
    END IF;
    expected_size=expected_size+source_size;
    expected_token=expected_token+source_token;
    delivery=pair->'deliveryArtifact';
    IF jsonb_typeof(delivery) NOT IN('object','null') THEN
      RAISE EXCEPTION 'deliveryArtifact must be an object or JSON null';
    END IF;
    IF shared_delivery IS NULL THEN shared_delivery=delivery; END IF;
    IF jsonb_typeof(delivery)='object' THEN
      IF jsonb_typeof(shared_delivery)<>'object'
         OR NOT(delivery ?& ARRAY['artifactSeq','originalFileName','newFileName','sizeBytes','sha256','token','file_mapping'])
         OR EXISTS(SELECT 1 FROM jsonb_object_keys(delivery) k WHERE k NOT IN
           ('artifactSeq','originalFileName','newFileName','sizeBytes','sha256','token','file_mapping')) THEN
        RAISE EXCEPTION 'all delivery artifacts must share one valid merged package';
      END IF;
      IF (delivery->>'artifactSeq')::int<>1 OR (delivery->>'sizeBytes')::bigint<0 OR (delivery->>'token')::bigint<0
         OR delivery->>'sha256'!~'^[0-9a-f]{64}$' OR jsonb_typeof(delivery->'file_mapping')<>'object'
         OR jsonb_typeof(delivery->'file_mapping')='object' AND delivery->'file_mapping'='{}'::jsonb
         OR delivery->>'newFileName'<>NEW.new_file_name OR delivery->>'newFileName' LIKE '%/%'
         OR delivery->>'newFileName' LIKE '%..%' OR delivery-'file_mapping'<>shared_delivery-'file_mapping' THEN
        RAISE EXCEPTION 'delivery artifact values or shared package facts are invalid';
      END IF;
      FOR mapping IN SELECT key,value FROM jsonb_each(delivery->'file_mapping')
      LOOP
        IF jsonb_typeof(mapping.value)<>'string' OR length(trim(both '"' from mapping.value::text))=0 THEN
          RAISE EXCEPTION 'file_mapping values must be non-empty requestId strings';
        END IF;
        metadata_values=metadata_values||jsonb_build_array(mapping.value);
      END LOOP;
    ELSIF jsonb_typeof(shared_delivery)<>'null' THEN
      RAISE EXCEPTION 'deliveryArtifact must be null for every source before repair';
    END IF;
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
  IF jsonb_typeof(shared_delivery)='null' THEN
    IF NEW.size_bytes<>expected_size OR NEW.token_count<>expected_token OR NEW.metadata_ids<>'[]'::jsonb THEN
      RAISE EXCEPTION 'unrepaired task size/token must equal the mapped source sums';
    END IF;
  ELSE
    IF NEW.size_bytes<>(shared_delivery->>'sizeBytes')::bigint OR NEW.token_count<>(shared_delivery->>'token')::bigint
       OR jsonb_array_length(NEW.metadata_ids)<>jsonb_array_length(metadata_values)
       OR EXISTS(SELECT 1 FROM jsonb_array_elements(NEW.metadata_ids) expected
                 WHERE NOT EXISTS(SELECT 1 FROM jsonb_array_elements(metadata_values) actual WHERE actual.value=expected.value)) THEN
      RAISE EXCEPTION 'task size/token and metadata_ids must equal the merged artifact facts';
    END IF;
  END IF;
  RETURN NEW;
END $$;
COMMENT ON FUNCTION archive_delivery.validate_task_artifacts() IS '校验任务来源 artifact 与 source_data 完全一致，并保证合并包 size/token/SHA、逐来源映射和 metadata_ids 对齐。';
