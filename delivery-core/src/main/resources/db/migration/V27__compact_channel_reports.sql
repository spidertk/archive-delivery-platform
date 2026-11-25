-- 渠道播报改为单张聚合卡片；保留观察、已确认通知及交付事实，不伪造发送结果。
SET search_path TO archive_delivery;

ALTER TABLE ingest_monitor_state DROP CONSTRAINT ingest_monitor_state_phase_check;
ALTER TABLE ingest_monitor_state DROP COLUMN session_id;
ALTER TABLE ingest_monitor_state RENAME COLUMN started_at TO continuous_since;
ALTER TABLE ingest_monitor_state RENAME COLUMN last_change_at TO last_growth_at;
ALTER TABLE ingest_monitor_state ADD COLUMN folder_count bigint NOT NULL DEFAULT 0 CHECK(folder_count>=0);
ALTER TABLE ingest_monitor_state ADD COLUMN last_report_at timestamptz;
ALTER TABLE ingest_monitor_state ADD COLUMN report_sample_id bigint NOT NULL DEFAULT 0 CHECK(report_sample_id>=0);
UPDATE ingest_monitor_state SET phase='IDLE',continuous_since=NULL,last_growth_at=NULL,
  report_sample_id=coalesce((SELECT max(sample_id) FROM ingest_monitor_sample s WHERE s.source_id=ingest_monitor_state.source_id),0);
ALTER TABLE ingest_monitor_state ADD CHECK(phase IN ('IDLE','OBSERVING','ACTIVE'));
COMMENT ON COLUMN ingest_monitor_state.phase IS 'IDLE 安静；OBSERVING 连续进数尚未满 30 分钟；ACTIVE 已通知并等待小时增量汇总。';
COMMENT ON COLUMN ingest_monitor_state.continuous_since IS '连续成功扫描均有新增或覆盖的窗口起点；空扫描、故障或重启中断重置。';
COMMENT ON COLUMN ingest_monitor_state.last_growth_at IS '最近完整扫描发现新增或覆盖对象的时间。';
COMMENT ON COLUMN ingest_monitor_state.folder_count IS '来源前缀下所有非空文件夹数，包含中间目录和嵌套目录，不含来源根。';
COMMENT ON COLUMN ingest_monitor_state.last_report_at IS '最近一次已入队的主动或小时汇总时间；发送成功另由 outbox 确认。';
COMMENT ON COLUMN ingest_monitor_state.report_sample_id IS '已纳入主动或小时汇总的最大采样 ID，事务游标防止丢增量或重复累计。';

ALTER TABLE ingest_monitor_sample DROP COLUMN session_id;
ALTER TABLE ingest_monitor_sample ADD COLUMN is_baseline boolean NOT NULL DEFAULT false;
ALTER TABLE ingest_monitor_sample ADD COLUMN new_directory boolean NOT NULL DEFAULT false;
UPDATE ingest_monitor_sample s SET is_baseline=true WHERE observed_at=(SELECT min(x.observed_at) FROM ingest_monitor_sample x WHERE x.source_id=s.source_id);
COMMENT ON COLUMN ingest_monitor_sample.is_baseline IS '首次完整观察的存量；累计量保留，不能计入后续进数通知或趋势新增量。';
COMMENT ON COLUMN ingest_monitor_sample.new_directory IS '本次首次出现的文件所属目录；只对非基线、实际进数目录为 true。';
COMMENT ON COLUMN ingest_monitor_sample.directory IS '空串代表渠道；(根目录) 代表直属文件；V27 起记录文件所属的完整相对目录。';
COMMENT ON TABLE ingest_monitor_sample IS '完整扫描与变化目录的趋势事实；新增、覆盖、删除分别统计，首次存量显式标记。';

CREATE TABLE ingest_monitor_directory (
  source_id bigint NOT NULL REFERENCES ingest_source(source_id),
  directory text NOT NULL,
  object_count bigint NOT NULL CHECK(object_count>0),
  size_bytes bigint NOT NULL CHECK(size_bytes>=0),
  first_seen_at timestamptz NOT NULL,
  PRIMARY KEY(source_id,directory)
);
COMMENT ON TABLE ingest_monitor_directory IS '当前非空文件夹树；同一对象计入全部祖先目录，渠道总量只从对象清单统计，禁止重复求和。';
COMMENT ON COLUMN ingest_monitor_directory.source_id IS '所属来源。';
COMMENT ON COLUMN ingest_monitor_directory.directory IS '来源前缀下完整相对文件夹路径，以斜线结尾，保留大小写。';
COMMENT ON COLUMN ingest_monitor_directory.object_count IS '当前目录及全部后代中的文件数。';
COMMENT ON COLUMN ingest_monitor_directory.size_bytes IS '当前目录及全部后代的对象总字节数。';
COMMENT ON COLUMN ingest_monitor_directory.first_seen_at IS '本次存在周期第一次成功观察到的时间；初始化现有清单不是新增到数事件。';

-- 从已确认对象清单建立目录基线，避免发布后把历史文件夹重新当作新增。
WITH relative AS (
  SELECT o.source_id,o.size_bytes,string_to_array(substr(o.object_key,length(regexp_replace(i.oss_uri,'^oss://[^/]+/',''))+1),'/') AS parts
  FROM ingest_monitor_object o JOIN ingest_source i USING(source_id)
), expanded AS (
  SELECT source_id,size_bytes,array_to_string(parts[1:depth],'/')||'/' AS directory
  FROM relative CROSS JOIN LATERAL generate_series(1,array_length(parts,1)-1) depth
)
INSERT INTO ingest_monitor_directory(source_id,directory,object_count,size_bytes,first_seen_at)
SELECT source_id,directory,count(*),sum(size_bytes),now() FROM expanded GROUP BY source_id,directory;
UPDATE ingest_monitor_state s SET folder_count=(SELECT count(*) FROM ingest_monitor_directory d WHERE d.source_id=s.source_id);

CREATE TABLE ingest_monitor_broadcast (
  singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
  last_daily_date date,
  last_daily_sample_id bigint NOT NULL DEFAULT 0 CHECK(last_daily_sample_id>=0)
);
COMMENT ON TABLE ingest_monitor_broadcast IS '聚合日报游标；北京时间每天首次所有渠道完整扫描成功后只入队一次。';
COMMENT ON COLUMN ingest_monitor_broadcast.singleton IS '固定 true 的单行键，聚合播报在全局 PG 锁下推进。';
COMMENT ON COLUMN ingest_monitor_broadcast.last_daily_date IS '最近已入队日报的北京时间日期；空表示新策略尚未播报。';
COMMENT ON COLUMN ingest_monitor_broadcast.last_daily_sample_id IS '日报已包含的最大观察 ID，新增目录按此游标去重。';
INSERT INTO ingest_monitor_broadcast(singleton,last_daily_sample_id) SELECT true,coalesce(max(sample_id),0) FROM ingest_monitor_sample;

CREATE TABLE ingest_monitor_notification_channel (
  notification_id bigint NOT NULL REFERENCES ingest_monitor_notification(notification_id),
  source_id bigint NOT NULL REFERENCES ingest_source(source_id),
  PRIMARY KEY(notification_id,source_id)
);
COMMENT ON TABLE ingest_monitor_notification_channel IS '单张聚合卡片包含的渠道；用于各渠道待发数，不能为每个渠道重复发相同卡片。';
COMMENT ON COLUMN ingest_monitor_notification_channel.notification_id IS '聚合 outbox 消息。';
COMMENT ON COLUMN ingest_monitor_notification_channel.source_id IS '卡片包含的渠道。';
INSERT INTO ingest_monitor_notification_channel SELECT notification_id,source_id FROM ingest_monitor_notification;
ALTER TABLE ingest_monitor_notification ADD COLUMN report_key varchar(160);
ALTER TABLE ingest_monitor_notification ADD COLUMN cancelled_at timestamptz;
UPDATE ingest_monitor_notification SET report_key='history:'||notification_id,
  cancelled_at=CASE WHEN sent_at IS NULL THEN now() END;
ALTER TABLE ingest_monitor_notification ALTER COLUMN report_key SET NOT NULL;
ALTER TABLE ingest_monitor_notification ADD UNIQUE(report_key);
ALTER TABLE ingest_monitor_notification DROP CONSTRAINT ingest_monitor_notification_event_type_check;
ALTER TABLE ingest_monitor_notification RENAME COLUMN content TO payload;
ALTER TABLE ingest_monitor_notification ALTER COLUMN payload TYPE jsonb USING jsonb_build_object('text',payload);
ALTER TABLE ingest_monitor_notification DROP COLUMN source_id CASCADE;
ALTER TABLE ingest_monitor_notification DROP COLUMN session_id CASCADE;
ALTER TABLE ingest_monitor_notification DROP COLUMN part_no CASCADE;
-- 先移除旧 source/session/type/part 唯一键，再统一历史类型，避免同轮 START/STOP 相撞。
UPDATE ingest_monitor_notification SET event_type='HISTORY';
ALTER TABLE ingest_monitor_notification ADD CHECK(event_type IN ('HISTORY','DAILY','ACTIVE','HOURLY'));
ALTER TABLE ingest_monitor_notification ADD CHECK(coalesce(
  CASE WHEN event_type='HISTORY' THEN jsonb_typeof(payload->'text')='string' AND payload-'text'='{}'::jsonb
  ELSE payload->>'msgtype'='template_card' AND payload-ARRAY['msgtype','template_card']='{}'::jsonb
    AND payload->'template_card'->>'card_type'='text_notice'
    AND (payload->'template_card')-ARRAY['card_type','source','main_title','emphasis_content','sub_title_text','quote_area','card_action']='{}'::jsonb
    AND jsonb_typeof(payload->'template_card'->'main_title'->'title')='string'
    AND (payload->'template_card'->'main_title')-ARRAY['title','desc']='{}'::jsonb
    AND (payload->'template_card'->'source')-ARRAY['desc','desc_color']='{}'::jsonb
    AND (payload->'template_card'->'emphasis_content')-ARRAY['title','desc']='{}'::jsonb
    AND (payload->'template_card'->'card_action')-ARRAY['type','url']='{}'::jsonb
    AND payload->'template_card'->'card_action'->>'type'='1'
    AND payload->'template_card'->'card_action'->>'url' LIKE 'https://%'
    AND (NOT(payload->'template_card' ? 'quote_area') OR (payload->'template_card'->'quote_area')-ARRAY['title','quote_text']='{}'::jsonb)
  END,false));
COMMENT ON TABLE ingest_monitor_notification IS '单卡聚合微信群 outbox；日报、持续进数及小时增量均只有一条消息，不拆分刷屏。';
COMMENT ON COLUMN ingest_monitor_notification.report_key IS '日报按日期唯一；主动和小时汇总按事务批次 UUID 唯一，与游标一同提交。';
COMMENT ON COLUMN ingest_monitor_notification.cancelled_at IS '已取消的过时未发送格式；保留历史但不允许旧策略继续推送，不伪造 sent_at。';
COMMENT ON COLUMN ingest_monitor_notification.event_type IS 'DAILY 日报、ACTIVE 持续进数、HOURLY 增量；HISTORY 仅保存旧通知历史，运行时不投递。';
COMMENT ON COLUMN ingest_monitor_notification.payload IS '用途为群卡片发送体；Schema=docs/schemas/ingest-monitor-card.schema.json。白名单 msgtype/template_card；卡片只含 card_type/source/main_title/emphasis_content/sub_title_text/quote_area/card_action。历史仅 text，不含 Secret。';
CREATE INDEX ingest_monitor_notification_channel_source_idx ON ingest_monitor_notification_channel(source_id,notification_id);
CREATE INDEX ingest_monitor_sample_window_idx ON ingest_monitor_sample(source_id,sample_id) WHERE NOT is_baseline;
