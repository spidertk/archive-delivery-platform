-- 监控精简为对象快照、增量、通知三表；来源身份及状态复用 ingest_source。
SET search_path TO archive_delivery;
ALTER TABLE ingest_source ALTER COLUMN channel_code TYPE text;
ALTER TABLE ingest_source ADD COLUMN monitor_phase varchar(16) NOT NULL DEFAULT 'IDLE' CHECK(monitor_phase IN ('IDLE','OBSERVING','ACTIVE'));
COMMENT ON COLUMN ingest_source.monitor_phase IS '监控窗口：IDLE 安静、OBSERVING 连续进数观察、ACTIVE 小时汇总中。';
ALTER TABLE ingest_source ADD COLUMN monitor_continuous_since timestamptz;
COMMENT ON COLUMN ingest_source.monitor_continuous_since IS '连续成功进数的起点，失败或无进数重新计算。';
ALTER TABLE ingest_source ADD COLUMN monitor_last_growth_at timestamptz;
COMMENT ON COLUMN ingest_source.monitor_last_growth_at IS '最近完整扫描发现新增或覆盖的时间。';
ALTER TABLE ingest_source ADD COLUMN monitor_observed_at timestamptz;
COMMENT ON COLUMN ingest_source.monitor_observed_at IS '最近完整成功扫描完成时间；为空表示尚无基线。';
ALTER TABLE ingest_source ADD COLUMN monitor_object_count bigint NOT NULL DEFAULT 0 CHECK(monitor_object_count>=0);
COMMENT ON COLUMN ingest_source.monitor_object_count IS '登记范围最近成功快照的文件数。';
ALTER TABLE ingest_source ADD COLUMN monitor_size_bytes bigint NOT NULL DEFAULT 0 CHECK(monitor_size_bytes>=0);
COMMENT ON COLUMN ingest_source.monitor_size_bytes IS '登记范围最近成功快照的字节数。';
ALTER TABLE ingest_source ADD COLUMN monitor_last_error_code varchar(32);
COMMENT ON COLUMN ingest_source.monitor_last_error_code IS '固定扫描错误码，不保存 SDK 异常或 Secret。';
ALTER TABLE ingest_source ADD COLUMN monitor_failed_at timestamptz;
COMMENT ON COLUMN ingest_source.monitor_failed_at IS '最近失败时间，成功后清空。';
ALTER TABLE ingest_source ADD COLUMN monitor_last_report_at timestamptz;
COMMENT ON COLUMN ingest_source.monitor_last_report_at IS '最近进数/小时报告入队时间。';
ALTER TABLE ingest_source ADD COLUMN monitor_report_sample_id bigint NOT NULL DEFAULT 0 CHECK(monitor_report_sample_id>=0);
COMMENT ON COLUMN ingest_source.monitor_report_sample_id IS '本来源已纳入进数报告的最大观察 ID。';
UPDATE ingest_source i SET monitor_phase=s.phase,monitor_continuous_since=s.continuous_since,monitor_last_growth_at=s.last_growth_at,monitor_observed_at=s.observed_at,monitor_object_count=s.object_count,monitor_size_bytes=s.size_bytes,monitor_last_error_code=s.last_error_code,monitor_failed_at=s.failed_at,monitor_last_report_at=s.last_report_at,monitor_report_sample_id=s.report_sample_id FROM ingest_monitor_state s WHERE s.source_id=i.source_id;
UPDATE ingest_source SET channel_code=coalesce(nullif(split_part(regexp_replace(oss_uri,'^oss://[^/]+/?',''), '/',1),''),substring(oss_uri FROM '^oss://([^/]+)'));
COMMENT ON COLUMN ingest_source.channel_code IS '从 OSS 桶下第一级目录自动推导的展示标识；真正渠道身份由桶与该目录共同确定。';
COMMENT ON TABLE ingest_source IS '无密钥来源登记及其监控状态；扫描范围保持原 URI，展示渠道按桶与第一级目录归并。';

-- 历史目录采样从登记范围相对路径统一为渠道第一级目录下的相对路径，渠道总计行保持空串。
WITH location AS (SELECT source_id,regexp_replace(regexp_replace(oss_uri,'^oss://[^/]+/?',''),'^[^/]+/?','') AS suffix FROM ingest_source) 
UPDATE ingest_monitor_sample s SET directory=coalesce(nullif(l.suffix||CASE WHEN s.directory='(根目录)' THEN '' ELSE s.directory END,''),'(根目录)') FROM location l WHERE l.source_id=s.source_id AND s.directory<>'';
ALTER TABLE ingest_monitor_sample DROP COLUMN new_directory;
COMMENT ON COLUMN ingest_monitor_sample.directory IS '空串为范围总计；其他为桶第一级渠道目录下实际发生变化的文件所属相对目录，不存祖先目录树。';

ALTER TABLE ingest_monitor_notification ADD COLUMN source_ids bigint[] NOT NULL DEFAULT ARRAY[]::bigint[];
ALTER TABLE ingest_monitor_notification ADD COLUMN report_day date;
ALTER TABLE ingest_monitor_notification ADD COLUMN sample_watermark bigint NOT NULL DEFAULT 0 CHECK(sample_watermark>=0);
ALTER TABLE ingest_monitor_notification ALTER COLUMN report_key TYPE text;
UPDATE ingest_monitor_notification n SET source_ids=(SELECT array_agg(c.source_id ORDER BY c.source_id) FROM ingest_monitor_notification_channel c WHERE c.notification_id=n.notification_id);
UPDATE ingest_monitor_notification SET report_day=substring(report_key FROM '^daily:([0-9]{4}-[0-9]{2}-[0-9]{2})$')::date WHERE event_type='DAILY';
UPDATE ingest_monitor_notification n SET sample_watermark=b.last_daily_sample_id FROM ingest_monitor_broadcast b WHERE n.event_type='DAILY' AND n.report_day=b.last_daily_date;
ALTER TABLE ingest_monitor_notification ADD CHECK(array_ndims(source_ids)=1 AND 0<ALL(source_ids));
COMMENT ON COLUMN ingest_monitor_notification.source_ids IS '该消息包含的来源 ID 快照数组；由服务从已登记范围生成，取消消息渠道关系表。';
COMMENT ON COLUMN ingest_monitor_notification.report_day IS '日报对应的北京时间日期，替代独立日报游标表。历史日报日期保留。';
COMMENT ON COLUMN ingest_monitor_notification.sample_watermark IS '日报已包含的最大采样 ID；与消息同事务写入，替代独立日报游标。';

-- 旧消息仅归档，不修改真实确认时间；新样式首次完整成功扫描发送一份修正后的日报。
DO $$ DECLARE item record; BEGIN
 FOR item IN SELECT conname FROM pg_constraint WHERE conrelid='ingest_monitor_notification'::regclass AND contype='c' AND pg_get_constraintdef(oid) LIKE '%payload%' LOOP
 EXECUTE format('ALTER TABLE ingest_monitor_notification DROP CONSTRAINT %I',item.conname);
 END LOOP; END $$;
UPDATE ingest_monitor_notification SET report_key='history:'||notification_id||':'||report_key,event_type='HISTORY',cancelled_at=CASE WHEN sent_at IS NULL THEN coalesce(cancelled_at,now()) ELSE cancelled_at END WHERE event_type<>'HISTORY';
ALTER TABLE ingest_monitor_notification ADD CHECK(coalesce(jsonb_typeof(payload)='object' AND CASE WHEN event_type='HISTORY' THEN 
 (jsonb_typeof(payload->'text')='string' AND payload-'text'='{}'::jsonb) OR (payload->>'msgtype'='template_card' AND payload-ARRAY['msgtype','template_card']='{}'::jsonb AND (payload->'template_card')-ARRAY['card_type','source','main_title','emphasis_content','sub_title_text','quote_area','card_action']='{}'::jsonb)
 ELSE payload->>'msgtype'='markdown' AND payload-ARRAY['msgtype','markdown']='{}'::jsonb AND (payload->'markdown')-'content'='{}'::jsonb AND jsonb_typeof(payload->'markdown'->'content')='string' AND octet_length(payload->'markdown'->>'content')<=4096 END,false));
COMMENT ON COLUMN ingest_monitor_notification.payload IS '通知发送体；Schema=docs/schemas/ingest-monitor-card.schema.json。当前仅 msgtype/markdown.content 白名单，不含点击地址、Secret 或目录树；历史只读保留 text 或旧 template_card。';
COMMENT ON TABLE ingest_monitor_notification IS '聚合通知、日报去重与可靠投递事实；消息自身保存日期、采样游标和来源 ID 数组，不再需要关系或全局状态表。';
CREATE INDEX ingest_monitor_notification_sources_idx ON ingest_monitor_notification USING gin(source_ids);
CREATE INDEX ingest_monitor_notification_daily_idx ON ingest_monitor_notification(report_day DESC) WHERE event_type='DAILY';

DROP TABLE ingest_monitor_notification_channel;
DROP TABLE ingest_monitor_broadcast;
DROP TABLE ingest_monitor_directory;
DROP TABLE ingest_monitor_state;
