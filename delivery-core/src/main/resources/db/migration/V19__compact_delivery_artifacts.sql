SET search_path TO archive_delivery;

DROP TRIGGER trg_task_artifacts ON delivery_task;

UPDATE delivery_task task
SET artifacts=cleaned.artifacts
FROM (
  SELECT source.task_id,jsonb_agg(
    CASE WHEN jsonb_typeof(pair.value->'deliveryArtifact')='object'
      THEN jsonb_set(pair.value,'{deliveryArtifact}',(pair.value->'deliveryArtifact')-'newFileName'-'file_mapping')
      ELSE pair.value
    END ORDER BY pair.ord) AS artifacts
  FROM delivery_task source
  CROSS JOIN LATERAL jsonb_array_elements(source.artifacts) WITH ORDINALITY pair(value,ord)
  GROUP BY source.task_id
) cleaned
WHERE cleaned.task_id=task.task_id;

COMMENT ON COLUMN delivery_task.artifacts IS '逐来源产物对数组；sourceArtifact 逐字段等于 source_data.artifact，deliveryArtifact 仅含 artifactSeq/originalFileName/sizeBytes/sha256/token 五字段，不含 newFileName/file_mapping。';
COMMENT ON COLUMN delivery_task.metadata_ids IS '逐来源 requestId 二维数组；metadata_ids[i] 对应 artifacts[i] 的 dataId，保存 repair manifest 中该来源的完整 requestId 集合，外层长度等于来源数。';

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
  metadata_group jsonb;
  metadata_id jsonb;
BEGIN
  -- 任务至少有一个来源对，repair 前 metadata_ids 可以为空数组。
  IF jsonb_typeof(NEW.artifacts)<>'array' OR jsonb_array_length(NEW.artifacts)=0
     OR jsonb_typeof(NEW.metadata_ids)<>'array' THEN
    RAISE EXCEPTION 'task artifacts must be a non-empty array and metadata_ids must be an array';
  END IF;
  -- 每个来源对依次核对原始事实并累计 repair 前的任务汇总值。
  FOR pair IN SELECT value FROM jsonb_array_elements(NEW.artifacts)
  LOOP
    -- 产物对严格使用 dataId/sourceArtifact/deliveryArtifact 三字段。
    IF jsonb_typeof(pair)<>'object' OR NOT(pair ?& ARRAY['dataId','sourceArtifact','deliveryArtifact'])
       OR EXISTS(SELECT 1 FROM jsonb_object_keys(pair) k WHERE k NOT IN('dataId','sourceArtifact','deliveryArtifact')) THEN
      RAISE EXCEPTION 'task artifact pair violates the mapping contract';
    END IF;
    -- 原始 artifact 必须属于同一 delivery 且逐字段相等。
    SELECT artifact,size_bytes,token_count INTO source_artifact,source_size,source_token
      FROM archive_delivery.source_data WHERE data_id=(pair->>'dataId')::bigint AND delivery_id=NEW.delivery_id;
    IF NOT FOUND OR pair->'sourceArtifact'<>source_artifact THEN
      RAISE EXCEPTION 'sourceArtifact must equal source_data.artifact in the same delivery';
    END IF;
    expected_size=expected_size+source_size;
    expected_token=expected_token+source_token;
    delivery=pair->'deliveryArtifact';
    -- repair 前使用 JSON null，repair 后使用五字段对象。
    IF jsonb_typeof(delivery) NOT IN('object','null') THEN
      RAISE EXCEPTION 'deliveryArtifact must be an object or JSON null';
    END IF;
    IF shared_delivery IS NULL THEN shared_delivery=delivery; END IF;
    IF jsonb_typeof(delivery)='object' THEN
      -- 五字段白名单禁止 newFileName/file_mapping 回到任务 JSON。
      IF jsonb_typeof(shared_delivery)<>'object'
         OR NOT(delivery ?& ARRAY['artifactSeq','originalFileName','sizeBytes','sha256','token'])
         OR EXISTS(SELECT 1 FROM jsonb_object_keys(delivery) k WHERE k NOT IN
           ('artifactSeq','originalFileName','sizeBytes','sha256','token')) THEN
        RAISE EXCEPTION 'all delivery artifacts must share one five-field merged package';
      END IF;
      -- 所有来源对共享完全相同的 size/token/SHA 等交付事实。
      IF (delivery->>'artifactSeq')::int<>1 OR (delivery->>'sizeBytes')::bigint<0 OR (delivery->>'token')::bigint<0
         OR delivery->>'sha256'!~'^[0-9a-f]{64}$' OR delivery<>shared_delivery THEN
        RAISE EXCEPTION 'delivery artifact values or shared package facts are invalid';
      END IF;
    ELSIF jsonb_typeof(shared_delivery)<>'null' THEN
      RAISE EXCEPTION 'deliveryArtifact must be null for every source before repair';
    END IF;
  END LOOP;
  -- 同一来源在一个任务中只能出现一次。
  IF EXISTS(SELECT 1 FROM jsonb_array_elements(NEW.artifacts) p GROUP BY p.value->>'dataId' HAVING count(*)>1) THEN
    RAISE EXCEPTION 'dataId must be unique within task artifacts';
  END IF;
  IF jsonb_typeof(shared_delivery)='null' THEN
    -- repair 前任务汇总值等于全部原始来源之和，metadata_ids 必须为空。
    IF NEW.size_bytes<>expected_size OR NEW.token_count<>expected_token OR NEW.metadata_ids<>'[]'::jsonb THEN
      RAISE EXCEPTION 'unrepaired task size/token must equal the mapped source sums';
    END IF;
  ELSE
    -- repair 后任务汇总值等于合并包，metadata 外层长度等于来源数。
    IF NEW.size_bytes<>(shared_delivery->>'sizeBytes')::bigint OR NEW.token_count<>(shared_delivery->>'token')::bigint
       OR jsonb_array_length(NEW.metadata_ids)<>jsonb_array_length(NEW.artifacts) THEN
      RAISE EXCEPTION 'task size/token and source-aligned metadata_ids must equal the merged artifact facts';
    END IF;
    -- 每个来源组均为非空 requestId 字符串数组。
    FOR metadata_group IN SELECT value FROM jsonb_array_elements(NEW.metadata_ids)
    LOOP
      IF jsonb_typeof(metadata_group)<>'array' OR jsonb_array_length(metadata_group)=0 THEN
        RAISE EXCEPTION 'each metadata_ids group must be a non-empty requestId array';
      END IF;
      FOR metadata_id IN SELECT value FROM jsonb_array_elements(metadata_group)
      LOOP
        IF jsonb_typeof(metadata_id)<>'string' OR length(trim(both '"' from metadata_id::text))=0 THEN
          RAISE EXCEPTION 'metadata_ids groups must contain non-empty requestId strings';
        END IF;
      END LOOP;
    END LOOP;
    -- requestId 在任务的全部来源组之间保持唯一。
    IF EXISTS(
      SELECT 1 FROM jsonb_array_elements(NEW.metadata_ids) groups
      CROSS JOIN LATERAL jsonb_array_elements(groups.value) ids
      GROUP BY ids.value::text HAVING count(*)>1
    ) THEN
      RAISE EXCEPTION 'metadata_ids requestIds must be unique within a task';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_task_artifacts BEFORE INSERT OR UPDATE ON delivery_task
  FOR EACH ROW EXECUTE FUNCTION archive_delivery.validate_task_artifacts();
COMMENT ON FUNCTION archive_delivery.validate_task_artifacts() IS '校验原始 artifact、五字段交付 artifact、合并包 size/token/SHA 以及逐来源 metadata_ids 的结构和唯一性。';
