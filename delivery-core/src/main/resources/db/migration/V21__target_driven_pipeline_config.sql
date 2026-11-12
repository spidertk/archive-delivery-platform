SET search_path TO archive_delivery;

-- 旧约束要求独立 ruleSets；先按实际约束名删除，再统一清理不可变快照中的冗余字段。
DO $$
DECLARE
  constraint_record RECORD;
BEGIN
  FOR constraint_record IN
    SELECT conname FROM pg_constraint
    WHERE conrelid='archive_delivery.pipeline_version'::regclass AND contype='c'
      AND pg_get_constraintdef(oid) LIKE '%ruleSets%'
  LOOP
    EXECUTE format('ALTER TABLE archive_delivery.pipeline_version DROP CONSTRAINT %I', constraint_record.conname);
  END LOOP;
END $$;

-- targetCount 已决定任务槽位数，来源扫描持续到全部槽位满足包大小边界，不再使用 selection.limit。
WITH removed_top_level AS (
  SELECT pipeline_version_id,(definition-'ruleSets') #- '{config,selection}' AS definition
  FROM pipeline_version
), removed_node_rules AS (
  SELECT pipeline_version_id,jsonb_set(definition,'{nodes}',COALESCE((
    SELECT jsonb_agg(node.value #- '{config,ruleSet}' ORDER BY node.ord)
    FROM jsonb_array_elements(definition->'nodes') WITH ORDINALITY AS node(value,ord)
  ),'[]'::jsonb),false) AS definition
  FROM removed_top_level
), removed_rule_schema AS (
  SELECT pipeline_version_id,
    CASE WHEN definition #> '{nodeDefinitions,python.repair,configSchema}' IS NULL THEN definition
      ELSE jsonb_set(definition,'{nodeDefinitions,python.repair,configSchema}',
        (definition #> '{nodeDefinitions,python.repair,configSchema}')-'required',false)
    END AS definition
  FROM removed_node_rules
)
UPDATE pipeline_version target
SET definition=cleaned.definition,
    definition_sha256=encode(sha256(convert_to(cleaned.definition::text,'UTF8')),'hex')
FROM removed_rule_schema cleaned
WHERE cleaned.pipeline_version_id=target.pipeline_version_id;

-- 清洗执行版本统一通过 delivery_submit.pipeline_version_id 追溯，不在每个来源上重复保存。
ALTER TABLE source_data DROP COLUMN rule_version;

ALTER TABLE pipeline_version ADD CONSTRAINT pipeline_version_definition_current CHECK(
  NOT (definition ? 'ruleSets')
  AND NOT COALESCE(((definition->'config') ? 'selection'),false)
  AND NOT jsonb_path_exists(definition,'$.nodes[*].config.ruleSet')
);

COMMENT ON TABLE pipeline IS '可定义交付流程；所有节点和执行配置汇集到不可变 pipeline_version。';
COMMENT ON TABLE pipeline_version IS '不可变流程版本；统一保存 DAG、节点定义、分配、重试、跨日和节点凭据。';
COMMENT ON COLUMN pipeline_version.definition IS '统一配置 JSONB；白名单为 pipelineCode/version/nodeDefinitions/nodes/config，普通 API 必须脱敏；不保存独立 ruleSets 或 selection。';
