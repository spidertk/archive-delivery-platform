-- 快照与增量合为一张事实表；保留原采样 ID，避免已入队通知和来源窗口重新累计。
SET search_path TO archive_delivery;
ALTER TABLE ingest_monitor_sample RENAME TO ingest_monitor_record;
ALTER TABLE ingest_monitor_record RENAME COLUMN sample_id TO record_id;
ALTER TABLE ingest_monitor_record ADD COLUMN record_type varchar(8) NOT NULL DEFAULT 'SAMPLE' CHECK(record_type IN ('OBJECT','SAMPLE'));
ALTER TABLE ingest_monitor_record ADD COLUMN object_key text;
ALTER TABLE ingest_monitor_record ADD COLUMN etag varchar(256);
ALTER TABLE ingest_monitor_record ADD COLUMN modified_at timestamptz;
ALTER TABLE ingest_monitor_record ALTER COLUMN observed_at DROP NOT NULL;
ALTER TABLE ingest_monitor_record ALTER COLUMN directory DROP NOT NULL;
ALTER TABLE ingest_monitor_record ALTER COLUMN object_count DROP NOT NULL;
ALTER TABLE ingest_monitor_record ALTER COLUMN added_objects DROP NOT NULL;
ALTER TABLE ingest_monitor_record ALTER COLUMN added_bytes DROP NOT NULL;
ALTER TABLE ingest_monitor_record ALTER COLUMN changed_objects DROP NOT NULL;
ALTER TABLE ingest_monitor_record ALTER COLUMN changed_bytes DROP NOT NULL;
ALTER TABLE ingest_monitor_record ALTER COLUMN removed_objects DROP NOT NULL;
ALTER TABLE ingest_monitor_record ALTER COLUMN removed_bytes DROP NOT NULL;
ALTER TABLE ingest_monitor_record ADD CONSTRAINT ingest_monitor_record_shape CHECK (
  (record_type='OBJECT' AND object_key IS NOT NULL AND modified_at IS NOT NULL AND observed_at IS NULL AND directory IS NULL
   AND object_count IS NULL AND added_objects IS NULL AND added_bytes IS NULL AND changed_objects IS NULL AND changed_bytes IS NULL
   AND removed_objects IS NULL AND removed_bytes IS NULL AND NOT is_baseline)
  OR (record_type='SAMPLE' AND object_key IS NULL AND etag IS NULL AND modified_at IS NULL AND observed_at IS NOT NULL AND directory IS NOT NULL
   AND object_count IS NOT NULL AND added_objects IS NOT NULL AND added_bytes IS NOT NULL AND changed_objects IS NOT NULL
   AND changed_bytes IS NOT NULL AND removed_objects IS NOT NULL AND removed_bytes IS NOT NULL)
);
CREATE UNIQUE INDEX ingest_monitor_record_object_idx ON ingest_monitor_record(source_id,object_key) WHERE record_type='OBJECT';
CREATE INDEX ingest_monitor_record_sample_idx ON ingest_monitor_record(source_id,record_id) WHERE record_type='SAMPLE';
ALTER INDEX IF EXISTS ingest_monitor_sample_trend_idx RENAME TO ingest_monitor_record_trend_idx;
ALTER INDEX IF EXISTS ingest_monitor_sample_window_idx RENAME TO ingest_monitor_record_window_idx;
INSERT INTO ingest_monitor_record(record_type,source_id,object_key,size_bytes,etag,modified_at)
SELECT 'OBJECT',source_id,object_key,size_bytes,etag,modified_at FROM ingest_monitor_object;
DROP TABLE ingest_monitor_object;
COMMENT ON TABLE ingest_monitor_record IS '统一监控事实：OBJECT 为最后成功对象快照，SAMPLE 为完整扫描总量与变化目录；类型约束及部分索引隔离，不新增关系表。';
COMMENT ON COLUMN ingest_monitor_record.record_id IS '统一事实 ID；既有采样 ID 原样保留，新对象与采样共用序列，报告只读取 SAMPLE。';
COMMENT ON COLUMN ingest_monitor_record.record_type IS 'OBJECT 当前对象快照；SAMPLE 历史扫描/变化目录。';
COMMENT ON COLUMN ingest_monitor_record.object_key IS '仅 OBJECT：相对桶根的完整对象键，大小写敏感。';
COMMENT ON COLUMN ingest_monitor_record.etag IS '仅 OBJECT：变化指纹，不作为 SHA 证明。';
COMMENT ON COLUMN ingest_monitor_record.modified_at IS '仅 OBJECT：OSS 最后修改时间。';
COMMENT ON COLUMN ingest_monitor_record.size_bytes IS 'OBJECT 为对象大小；SAMPLE 为范围或实际变化目录的当前总字节。';

ALTER TABLE ingest_source RENAME COLUMN monitor_report_sample_id TO monitor_report_record_id;
COMMENT ON COLUMN ingest_source.monitor_report_record_id IS '该逻辑渠道已纳入进数报告的 SAMPLE 记录游标，不读取 OBJECT 行。';
ALTER TABLE ingest_monitor_notification RENAME COLUMN sample_watermark TO record_watermark;
COMMENT ON COLUMN ingest_monitor_notification.record_watermark IS '日报覆盖的最大 SAMPLE 记录 ID；对象快照 ID 不参与日报游标。';

-- 旧发送体原样保留为历史；新卡片只允许跳转受控的只读监控页面，消息与确认时间不伪造。
DO $$ DECLARE item record; BEGIN
 FOR item IN SELECT conname FROM pg_constraint WHERE conrelid='ingest_monitor_notification'::regclass AND contype='c' AND pg_get_constraintdef(oid) LIKE '%payload%' LOOP
 EXECUTE format('ALTER TABLE ingest_monitor_notification DROP CONSTRAINT %I',item.conname);
 END LOOP; END $$;
UPDATE ingest_monitor_notification SET report_key='history:'||notification_id||':'||report_key,event_type='HISTORY',cancelled_at=CASE WHEN sent_at IS NULL THEN coalesce(cancelled_at,now()) ELSE cancelled_at END WHERE event_type<>'HISTORY';
ALTER TABLE ingest_monitor_notification ADD CHECK(coalesce(jsonb_typeof(payload)='object' AND CASE WHEN event_type='HISTORY' THEN
 (jsonb_typeof(payload->'text')='string' AND payload-'text'='{}'::jsonb)
 OR (payload->>'msgtype'='markdown' AND payload-ARRAY['msgtype','markdown']='{}'::jsonb AND (payload->'markdown')-'content'='{}'::jsonb)
 OR (payload->>'msgtype'='template_card' AND payload-ARRAY['msgtype','template_card']='{}'::jsonb AND (payload->'template_card')-ARRAY['card_type','source','main_title','emphasis_content','sub_title_text','quote_area','card_action']='{}'::jsonb)
 ELSE payload->>'msgtype'='template_card' AND payload-ARRAY['msgtype','template_card']='{}'::jsonb
 AND (payload->'template_card')-ARRAY['card_type','source','main_title','emphasis_content','sub_title_text','quote_area','card_action']='{}'::jsonb
 AND payload->'template_card'->>'card_type'='text_notice'
 AND payload->'template_card'->'card_action'->>'type'='1'
 AND payload->'template_card'->'card_action'->>'url' ~ '^https?://[^/]+/monitor$'
 END,false));
COMMENT ON COLUMN ingest_monitor_notification.payload IS '群原生卡片发送体；Schema=docs/schemas/ingest-monitor-card.schema.json；仅白名单展示字段及只读监控页面 URL，无 Secret。历史正文/Markdown/卡片只读保留。';
