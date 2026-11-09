SET search_path TO archive_delivery;

DROP TRIGGER trg_task_artifacts ON delivery_task;

UPDATE delivery_task task
SET metadata_ids=grouped.metadata_ids
FROM (
  SELECT task_id,jsonb_agg(source_metadata ORDER BY source_ord) AS metadata_ids
  FROM (
    SELECT source.task_id,pair.source_ord,
           jsonb_agg(mapping.value ORDER BY mapping.mapping_ord) AS source_metadata
    FROM delivery_task source
    CROSS JOIN LATERAL jsonb_array_elements(source.artifacts) WITH ORDINALITY pair(value,source_ord)
    CROSS JOIN LATERAL jsonb_each(pair.value->'deliveryArtifact'->'file_mapping')
      WITH ORDINALITY mapping(key,value,mapping_ord)
    WHERE jsonb_array_length(source.metadata_ids)>0
    GROUP BY source.task_id,pair.source_ord
  ) source_groups
  GROUP BY task_id
) grouped
WHERE grouped.task_id=task.task_id;

COMMENT ON COLUMN delivery_task.metadata_ids IS '逐来源 requestId 二维数组；metadata_ids[i] 对应 artifacts[i]，保存该来源 file_mapping 的完整 requestId 集合，外层长度等于来源数。';

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
  metadata_group jsonb;
  metadata_id jsonb;
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
      END LOOP;
    ELSIF jsonb_typeof(shared_delivery)<>'null' THEN
      RAISE EXCEPTION 'deliveryArtifact must be null for every source before repair';
    END IF;
  END LOOP;
  IF EXISTS(SELECT 1 FROM jsonb_array_elements(NEW.artifacts) p GROUP BY p.value->>'dataId' HAVING count(*)>1) THEN
    RAISE EXCEPTION 'dataId must be unique within task artifacts';
  END IF;
  IF jsonb_typeof(shared_delivery)='null' THEN
    IF NEW.size_bytes<>expected_size OR NEW.token_count<>expected_token OR NEW.metadata_ids<>'[]'::jsonb THEN
      RAISE EXCEPTION 'unrepaired task size/token must equal the mapped source sums';
    END IF;
  ELSE
    IF NEW.size_bytes<>(shared_delivery->>'sizeBytes')::bigint OR NEW.token_count<>(shared_delivery->>'token')::bigint
       OR jsonb_array_length(NEW.metadata_ids)<>jsonb_array_length(NEW.artifacts) THEN
      RAISE EXCEPTION 'task size/token and source-aligned metadata_ids must equal the merged artifact facts';
    END IF;
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
    IF EXISTS(
      SELECT 1 FROM jsonb_array_elements(NEW.metadata_ids) groups
      CROSS JOIN LATERAL jsonb_array_elements(groups.value) ids
      GROUP BY ids.value::text HAVING count(*)>1
    ) THEN
      RAISE EXCEPTION 'metadata_ids requestIds must be unique within a task';
    END IF;
    IF EXISTS(
      SELECT 1
      FROM jsonb_array_elements(NEW.artifacts) WITH ORDINALITY pairs(value,ord)
      JOIN jsonb_array_elements(NEW.metadata_ids) WITH ORDINALITY groups(value,ord) USING(ord)
      WHERE jsonb_array_length(groups.value)<>(SELECT count(*) FROM jsonb_object_keys(pairs.value->'deliveryArtifact'->'file_mapping'))
         OR EXISTS(
           SELECT 1 FROM jsonb_array_elements(groups.value) expected
           WHERE NOT EXISTS(
             SELECT 1 FROM jsonb_each(pairs.value->'deliveryArtifact'->'file_mapping') actual
             WHERE actual.value=expected.value
           )
         )
         OR EXISTS(
           SELECT 1 FROM jsonb_each(pairs.value->'deliveryArtifact'->'file_mapping') actual
           WHERE NOT EXISTS(
             SELECT 1 FROM jsonb_array_elements(groups.value) expected
             WHERE expected.value=actual.value
           )
         )
    ) THEN
      RAISE EXCEPTION 'metadata_ids[i] must equal artifacts[i].deliveryArtifact.file_mapping requestIds';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_task_artifacts BEFORE INSERT OR UPDATE ON delivery_task
  FOR EACH ROW EXECUTE FUNCTION archive_delivery.validate_task_artifacts();
COMMENT ON FUNCTION archive_delivery.validate_task_artifacts() IS '校验原始/交付产物和合并包事实，并强制 metadata_ids 与 artifacts 按来源下标一一对应且保留完整 requestId 血缘。';
