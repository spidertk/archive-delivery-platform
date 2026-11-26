-- FTP 交付名称独立于批次合并包名；历史名称按真实旧任务回填，不伪造历史序号分配事实。
ALTER TABLE delivery_task ADD COLUMN ftp_file_name VARCHAR(255);
ALTER TABLE delivery_task ADD COLUMN ftp_sequence BIGINT;
UPDATE delivery_task SET ftp_file_name=new_file_name;
ALTER TABLE delivery_task ALTER COLUMN ftp_file_name SET NOT NULL;
ALTER TABLE delivery_task ADD CONSTRAINT delivery_task_ftp_sequence_ck CHECK (
    ftp_sequence IS NULL OR (ftp_sequence BETWEEN 1 AND 9999999
      AND ftp_file_name='batch_' || lpad(ftp_sequence::text,7,'0') || '.tar.gz'));
CREATE UNIQUE INDEX uq_delivery_task_ftp_day_sequence ON delivery_task(delivery_entity_id,delivery_date,ftp_sequence)
    WHERE ftp_sequence IS NOT NULL;
COMMENT ON COLUMN delivery_task.ftp_file_name IS 'FTP 实际压缩包名；新任务按 FTP 实体和交付日期跨批次独立累计；批次血缘仍使用 new_file_name。';
COMMENT ON COLUMN delivery_task.ftp_sequence IS '单个 FTP 当日累计序号；历史任务无分配事实时为空，名称真实回填且不改历史成品。';

CREATE TABLE delivery_ftp_day_sequence (
    delivery_entity_id BIGINT NOT NULL REFERENCES delivery_entity(delivery_entity_id),
    delivery_date DATE NOT NULL,
    PRIMARY KEY(delivery_entity_id,delivery_date),
    next_value BIGINT NOT NULL CHECK(next_value BETWEEN 1 AND 10000000),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE delivery_ftp_day_sequence IS '各 FTP 每日独立文件序号分配器；只分配名称，不替代 V25 批次/任务血缘，不保存 Secret。';
COMMENT ON COLUMN delivery_ftp_day_sequence.delivery_entity_id IS '实际 FTP 交付实体；不同实体分别计数，不共享序列。';
COMMENT ON COLUMN delivery_ftp_day_sequence.delivery_date IS '业务交付日期，每个 FTP 实体在此日期单独从 1 累计。';
COMMENT ON COLUMN delivery_ftp_day_sequence.next_value IS '下一可分配序号；在父批次锁之后按日期行锁原子递增，不回收已使用编号。';
COMMENT ON COLUMN delivery_ftp_day_sequence.updated_at IS '最近一次成功预留序号的数据库时间。';
INSERT INTO delivery_ftp_day_sequence(delivery_entity_id,delivery_date,next_value)
SELECT delivery_entity_id,delivery_date,COALESCE(max(substring(ftp_file_name FROM '^batch_([0-9]{7})\.tar\.gz$')::bigint),0)+1
FROM delivery_task GROUP BY delivery_entity_id,delivery_date;
