-- V25 批次交付模型保持不变；只新增来源观察事实，不推进交付状态。
SET search_path TO archive_delivery;

CREATE TABLE ingest_monitor_state (
  source_id bigint PRIMARY KEY REFERENCES ingest_source(source_id),
  phase varchar(16) NOT NULL DEFAULT 'IDLE' CHECK(phase IN ('IDLE','RECEIVING')),
  session_id uuid,
  started_at timestamptz,
  last_change_at timestamptz,
  observed_at timestamptz,
  object_count bigint NOT NULL DEFAULT 0 CHECK(object_count>=0),
  size_bytes bigint NOT NULL DEFAULT 0 CHECK(size_bytes>=0),
  last_error_code varchar(32),
  failed_at timestamptz
);
COMMENT ON TABLE ingest_monitor_state IS '渠道监控状态；完整 LIST 成功后更新，故障不得推导数据停止。';
COMMENT ON COLUMN ingest_monitor_state.source_id IS '来源主键，渠道 URI 在 ingest_source 登记。';
COMMENT ON COLUMN ingest_monitor_state.phase IS 'IDLE 为未接收，RECEIVING 为本轮有数据变化并等待静默。';
COMMENT ON COLUMN ingest_monitor_state.session_id IS '当前或最近一次接收轮次，开始后固定 UUID。';
COMMENT ON COLUMN ingest_monitor_state.started_at IS '本轮首次观察到新增或覆盖对象的时间。';
COMMENT ON COLUMN ingest_monitor_state.last_change_at IS '最近变化或故障恢复后的静默窗口起点。';
COMMENT ON COLUMN ingest_monitor_state.observed_at IS '最近一次完整成功 LIST 的完成时间。';
COMMENT ON COLUMN ingest_monitor_state.object_count IS '最近完整快照对象数，不含目录占位符。';
COMMENT ON COLUMN ingest_monitor_state.size_bytes IS '最近完整快照字节数。';
COMMENT ON COLUMN ingest_monitor_state.last_error_code IS '固定错误码 SCAN_FAILED，不保存 SDK 异常和凭据。';
COMMENT ON COLUMN ingest_monitor_state.failed_at IS '最近一次扫描失败时间，恢复成功后清空。';

CREATE TABLE ingest_monitor_object (
  source_id bigint NOT NULL REFERENCES ingest_source(source_id),
  object_key text NOT NULL,
  size_bytes bigint NOT NULL CHECK(size_bytes>=0),
  etag varchar(256),
  modified_at timestamptz NOT NULL,
  PRIMARY KEY(source_id,object_key)
);
COMMENT ON TABLE ingest_monitor_object IS '监控对象清单；只保存 key/size/ETag/修改时间，不下载数据，不触碰交付 source_data。';
COMMENT ON COLUMN ingest_monitor_object.source_id IS '所属来源 ID。';
COMMENT ON COLUMN ingest_monitor_object.object_key IS '相对于桶根的完整对象 key，保留大小写。';
COMMENT ON COLUMN ingest_monitor_object.size_bytes IS '对象字节数。';
COMMENT ON COLUMN ingest_monitor_object.etag IS 'OSS ETag，只用作变化指纹，不作为 SHA 证明。';
COMMENT ON COLUMN ingest_monitor_object.modified_at IS 'OSS 最后修改时间。';

CREATE TABLE ingest_monitor_sample (
  sample_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  source_id bigint NOT NULL REFERENCES ingest_source(source_id),
  session_id uuid,
  observed_at timestamptz NOT NULL,
  directory text NOT NULL,
  object_count bigint NOT NULL CHECK(object_count>=0),
  size_bytes bigint NOT NULL CHECK(size_bytes>=0),
  added_objects bigint NOT NULL CHECK(added_objects>=0),
  added_bytes bigint NOT NULL CHECK(added_bytes>=0),
  changed_objects bigint NOT NULL CHECK(changed_objects>=0),
  changed_bytes bigint NOT NULL CHECK(changed_bytes>=0),
  removed_objects bigint NOT NULL CHECK(removed_objects>=0),
  removed_bytes bigint NOT NULL CHECK(removed_bytes>=0)
);
COMMENT ON TABLE ingest_monitor_sample IS '渠道及直属子目录趋势；directory 空串为全渠道，每次成功扫描有一条渠道总计，变化目录另记。';
COMMENT ON COLUMN ingest_monitor_sample.sample_id IS '趋势采样主键。';
COMMENT ON COLUMN ingest_monitor_sample.source_id IS '所属渠道。';
COMMENT ON COLUMN ingest_monitor_sample.session_id IS '接收轮次；未开始时可空。';
COMMENT ON COLUMN ingest_monitor_sample.observed_at IS '完整 LIST 结束时间，UTC 存储。';
COMMENT ON COLUMN ingest_monitor_sample.directory IS '空串为全渠道；(根目录) 为直属文件，其余为渠道下第一级子目录。';
COMMENT ON COLUMN ingest_monitor_sample.object_count IS '当前目录的对象总数。';
COMMENT ON COLUMN ingest_monitor_sample.size_bytes IS '当前目录总字节数。';
COMMENT ON COLUMN ingest_monitor_sample.added_objects IS '相对上次成功扫描新增的对象数；首次快照为首次发现存量。';
COMMENT ON COLUMN ingest_monitor_sample.added_bytes IS '新增对象的完整字节数。';
COMMENT ON COLUMN ingest_monitor_sample.changed_objects IS '已有 key 的 ETag、大小或修改时间改变数。';
COMMENT ON COLUMN ingest_monitor_sample.changed_bytes IS '被覆盖对象当前完整字节数，不混入新增量。';
COMMENT ON COLUMN ingest_monitor_sample.removed_objects IS '相对上次成功扫描移除的对象数。';
COMMENT ON COLUMN ingest_monitor_sample.removed_bytes IS '移除对象在上次快照的字节数。';

CREATE TABLE ingest_monitor_notification (
  notification_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  source_id bigint NOT NULL REFERENCES ingest_source(source_id),
  session_id uuid NOT NULL,
  event_type varchar(8) NOT NULL CHECK(event_type IN ('START','STOP')),
  part_no int NOT NULL CHECK(part_no>0),
  content text NOT NULL,
  created_at timestamptz NOT NULL,
  sent_at timestamptz,
  attempts int NOT NULL DEFAULT 0 CHECK(attempts>=0),
  next_attempt_at timestamptz NOT NULL,
  UNIQUE(source_id,session_id,event_type,part_no)
);
COMMENT ON TABLE ingest_monitor_notification IS '微信群播报 outbox；与采样状态同事务写入，成功 ack 后记 sent_at；网络超时可能重复，轮次 ID 可核对。';
COMMENT ON COLUMN ingest_monitor_notification.notification_id IS '待发播报主键。';
COMMENT ON COLUMN ingest_monitor_notification.source_id IS '所属渠道。';
COMMENT ON COLUMN ingest_monitor_notification.session_id IS '接收轮次 UUID。';
COMMENT ON COLUMN ingest_monitor_notification.event_type IS 'START 为首次发现数据；STOP 为连续静默后的完整汇总。';
COMMENT ON COLUMN ingest_monitor_notification.part_no IS '长消息分段号，从 1 开始。';
COMMENT ON COLUMN ingest_monitor_notification.content IS '仅含渠道 URI、时间、计数、字节和轮次，不含 webhook 或 OSS Secret。';
COMMENT ON COLUMN ingest_monitor_notification.created_at IS '播报入队时间。';
COMMENT ON COLUMN ingest_monitor_notification.sent_at IS '企业微信 errcode=0 的确认时间。';
COMMENT ON COLUMN ingest_monitor_notification.attempts IS '已失败的发送次数。';
COMMENT ON COLUMN ingest_monitor_notification.next_attempt_at IS '指数退避后的下次允许发送时间。';

CREATE INDEX ingest_monitor_sample_trend_idx ON ingest_monitor_sample(source_id,observed_at DESC);
CREATE INDEX ingest_monitor_sample_session_idx ON ingest_monitor_sample(source_id,session_id,directory);
CREATE INDEX ingest_monitor_notification_pending_idx ON ingest_monitor_notification(notification_id) WHERE sent_at IS NULL;

-- 三个来源是同一桶中的独立渠道，按域名后的完整目录命名，禁止归一化大小写。
INSERT INTO ingest_source(oss_uri,channel_code,oss_type) VALUES
  ('oss://sg-oss-external-channel-source/Jayden-incoming-2.0/','Jayden-incoming-2.0','ALIBABA_CLOUD'),
  ('oss://sg-oss-external-channel-source/jayden-incoming/','jayden-incoming','ALIBABA_CLOUD'),
  ('oss://sg-oss-external-channel-source/incoming/temp-20260825-1007a693ea2acd68/','incoming/temp-20260825-1007a693ea2acd68','ALIBABA_CLOUD')
ON CONFLICT(oss_uri) DO UPDATE SET channel_code=EXCLUDED.channel_code,oss_type=EXCLUDED.oss_type;
