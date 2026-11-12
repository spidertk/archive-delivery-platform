SET search_path TO archive_delivery;

-- 三个 Python 数据面节点的实现路径随不可变 Pipeline 保存；已有快照使用镜像内受控默认路径。
WITH node_scripts AS (
  SELECT pipeline_version_id,jsonb_set(definition,'{nodes}',COALESCE((
    SELECT jsonb_agg(
      CASE WHEN node.value->>'type' IN('package.merge','python.repair','ftp.download-verify')
             AND NOT ((node.value->'config') ? 'scriptPath')
        THEN jsonb_set(node.value,'{config}',COALESCE(node.value->'config','{}'::jsonb) || jsonb_build_object(
          'scriptPath',CASE node.value->>'type'
            WHEN 'package.merge' THEN '/app/nodes/python/archive_merge.py'
            WHEN 'python.repair' THEN '/app/nodes/python/archive_repair.py'
            ELSE '/app/nodes/python/ftp_download_verify.py'
          END),false)
        ELSE node.value
      END ORDER BY node.ord)
    FROM jsonb_array_elements(definition->'nodes') WITH ORDINALITY AS node(value,ord)
  ),'[]'::jsonb),false) AS definition
  FROM pipeline_version
), merge_schema AS (
  SELECT pipeline_version_id,jsonb_set(definition,'{nodeDefinitions,package.merge,configSchema,required}',
    COALESCE(definition #> '{nodeDefinitions,package.merge,configSchema,required}','[]'::jsonb) || '["scriptPath"]'::jsonb,false) AS definition
  FROM node_scripts
), repair_schema AS (
  SELECT pipeline_version_id,jsonb_set(definition,'{nodeDefinitions,python.repair,configSchema,required}',
    COALESCE(definition #> '{nodeDefinitions,python.repair,configSchema,required}','[]'::jsonb) || '["scriptPath"]'::jsonb,false) AS definition
  FROM merge_schema
), ftp_schema AS (
  SELECT pipeline_version_id,jsonb_set(definition,'{nodeDefinitions,ftp.download-verify,configSchema,required}',
    COALESCE(definition #> '{nodeDefinitions,ftp.download-verify,configSchema,required}','[]'::jsonb) || '["scriptPath"]'::jsonb,false) AS definition
  FROM repair_schema
)
UPDATE pipeline_version target
SET definition=cleaned.definition,
    definition_sha256=encode(sha256(convert_to(cleaned.definition::text,'UTF8')),'hex')
FROM ftp_schema cleaned
WHERE cleaned.pipeline_version_id=target.pipeline_version_id;

COMMENT ON COLUMN pipeline_version.definition IS '统一配置 JSONB；白名单为 pipelineCode/version/nodeDefinitions/nodes/config，Python 节点 config.scriptPath 为规范化绝对 .py 路径，普通 API 必须脱敏；不保存独立 ruleSets 或 selection。';
