SET search_path TO archive_delivery;

-- 脚本根目录由 Worker 镜像和整目录挂载统一提供，不可变 Pipeline 只保存目录内相对路径。
WITH relative_scripts AS (
  SELECT pipeline_version_id,jsonb_set(definition,'{nodes}',COALESCE((
    SELECT jsonb_agg(
      CASE WHEN node.value->>'type' IN('package.merge','python.repair','ftp.download-verify')
        THEN jsonb_set(node.value,'{config,scriptPath}',
          to_jsonb(regexp_replace(node.value->'config'->>'scriptPath','^/(app|workspace)/nodes/python/','')),false)
        ELSE node.value
      END ORDER BY node.ord)
    FROM jsonb_array_elements(definition->'nodes') WITH ORDINALITY AS node(value,ord)
  ),'[]'::jsonb),false) AS definition
  FROM pipeline_version
)
UPDATE pipeline_version target
SET definition=cleaned.definition,
    definition_sha256=encode(sha256(convert_to(cleaned.definition::text,'UTF8')),'hex')
FROM relative_scripts cleaned
WHERE cleaned.pipeline_version_id=target.pipeline_version_id;

COMMENT ON COLUMN pipeline_version.definition IS '统一配置 JSONB；白名单为 pipelineCode/version/nodeDefinitions/nodes/config，Python 节点 config.scriptPath 为固定挂载目录内规范化相对 .py 路径，普通 API 必须脱敏；不保存独立 ruleSets 或 selection。';
