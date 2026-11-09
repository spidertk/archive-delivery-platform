SET search_path TO archive_delivery;

DROP TRIGGER trg_task_artifacts ON delivery_task;

UPDATE delivery_task task
SET artifacts=cleaned.artifacts,
    metadata_ids=CASE WHEN task.metadata_ids='[]'::jsonb THEN '[]'::jsonb ELSE cleaned.metadata_ids END
FROM (
  SELECT source.task_id,
         jsonb_agg(jsonb_build_object('dataId',pair.value->'dataId','sourceArtifact',pair.value->'sourceArtifact')
                   ORDER BY pair.ord) AS artifacts,
         jsonb_agg(pair.value->'dataId' ORDER BY pair.ord) AS metadata_ids
  FROM delivery_task source
  CROSS JOIN LATERAL jsonb_array_elements(source.artifacts) WITH ORDINALITY pair(value,ord)
  GROUP BY source.task_id
) cleaned
WHERE cleaned.task_id=task.task_id;

COMMENT ON COLUMN delivery_task.artifacts IS '逐来源原始产物数组；每项严格为 dataId/sourceArtifact 两字段，sourceArtifact 逐字段等于 source_data.artifact，不保存 deliveryArtifact。';
COMMENT ON COLUMN delivery_task.metadata_ids IS '有序 source_data.data_id 数字数组；metadata_ids[i] 等于 artifacts[i].dataId 并与 delivery_mapping.source_seq 顺序一致，不保存 requestId。';

CREATE OR REPLACE FUNCTION archive_delivery.validate_task_artifacts() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  pair jsonb;
  source_artifact jsonb;
  source_size bigint;
  source_token bigint;
  expected_size bigint:=0;
  expected_token bigint:=0;
  metadata_id jsonb;
BEGIN
  -- 任务至少有一个原始来源项，repair 前 metadata_ids 可以为空数组。
  IF jsonb_typeof(NEW.artifacts)<>'array' OR jsonb_array_length(NEW.artifacts)=0
     OR jsonb_typeof(NEW.metadata_ids)<>'array' THEN
    RAISE EXCEPTION 'task artifacts must be a non-empty array and metadata_ids must be an array';
  END IF;
  -- 每个来源项依次核对原始事实并累计 repair 前的任务汇总值。
  FOR pair IN SELECT value FROM jsonb_array_elements(NEW.artifacts)
  LOOP
    -- 来源项严格使用 dataId/sourceArtifact 两字段，禁止 deliveryArtifact。
    IF jsonb_typeof(pair)<>'object' OR NOT(pair ?& ARRAY['dataId','sourceArtifact'])
       OR EXISTS(SELECT 1 FROM jsonb_object_keys(pair) k WHERE k NOT IN('dataId','sourceArtifact')) THEN
      RAISE EXCEPTION 'task artifact must contain only dataId and sourceArtifact';
    END IF;
    -- 原始 artifact 必须属于同一 delivery 且逐字段相等。
    SELECT artifact,size_bytes,token_count INTO source_artifact,source_size,source_token
      FROM archive_delivery.source_data WHERE data_id=(pair->>'dataId')::bigint AND delivery_id=NEW.delivery_id;
    IF NOT FOUND OR pair->'sourceArtifact'<>source_artifact THEN
      RAISE EXCEPTION 'sourceArtifact must equal source_data.artifact in the same delivery';
    END IF;
    expected_size=expected_size+source_size;
    expected_token=expected_token+source_token;
  END LOOP;
  -- 同一来源在一个任务中只能出现一次。
  IF EXISTS(SELECT 1 FROM jsonb_array_elements(NEW.artifacts) p GROUP BY p.value->>'dataId' HAVING count(*)>1) THEN
    RAISE EXCEPTION 'dataId must be unique within task artifacts';
  END IF;
  -- metadata_ids 只接受正整数 data_id，不接受 requestId 字符串或嵌套数组。
  FOR metadata_id IN SELECT value FROM jsonb_array_elements(NEW.metadata_ids)
  LOOP
    IF jsonb_typeof(metadata_id)<>'number' OR metadata_id::text!~'^[1-9][0-9]*$' THEN
      RAISE EXCEPTION 'metadata_ids must contain positive source_data dataId numbers';
    END IF;
  END LOOP;
  IF EXISTS(SELECT 1 FROM jsonb_array_elements(NEW.metadata_ids) m GROUP BY m.value::text HAVING count(*)>1) THEN
    RAISE EXCEPTION 'metadata_ids dataIds must be unique within a task';
  END IF;
  IF NEW.metadata_ids='[]'::jsonb THEN
    -- repair 前任务汇总值等于全部原始来源之和。
    IF NEW.size_bytes<>expected_size OR NEW.token_count<>expected_token THEN
      RAISE EXCEPTION 'unrepaired task size/token must equal the mapped source sums';
    END IF;
  ELSE
    -- repair 后 metadata_ids 与 artifacts 按来源下标保存相同 data_id。
    IF NEW.size_bytes<0 OR NEW.token_count<0
       OR jsonb_array_length(NEW.metadata_ids)<>jsonb_array_length(NEW.artifacts) THEN
      RAISE EXCEPTION 'metadata_ids must align with every source artifact';
    END IF;
    IF EXISTS(
      SELECT 1
      FROM jsonb_array_elements(NEW.artifacts) WITH ORDINALITY pairs(value,ord)
      JOIN jsonb_array_elements(NEW.metadata_ids) WITH ORDINALITY metadata(value,ord) USING(ord)
      WHERE metadata.value<>pairs.value->'dataId'
    ) THEN
      RAISE EXCEPTION 'metadata_ids[i] must equal artifacts[i].dataId';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_task_artifacts BEFORE INSERT OR UPDATE ON delivery_task
  FOR EACH ROW EXECUTE FUNCTION archive_delivery.validate_task_artifacts();
COMMENT ON FUNCTION archive_delivery.validate_task_artifacts() IS '校验 dataId/sourceArtifact 原始来源项、metadata_ids 有序 data_id、来源唯一性以及 repair 前后任务汇总值。';
