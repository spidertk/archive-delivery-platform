package com.powerjob.archive.core.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDate;

/** 只分配 FTP 渠道名称：每个 FTP 实体在同一天跨批次独立累计编号，批次合并文件名和来源关系保持独立。 */
@Service
public class FtpFileNameAllocator {
    public static final int MAX_FILES_PER_ENTITY = 6000;
    private final JdbcTemplate jdbc;

    public FtpFileNameAllocator(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Allocation allocate(LocalDate date, long entityId) {
        // 调用方必须处于已锁 BATCH_CREATE 父单的事务中；实体和日期主键将同 FTP 的不同批次分配串行化。
        // 一个提交可同时涉及多个 FTP；先按日期取得短事务命名锁，避免不同实体顺序产生交叉死锁。
        jdbc.queryForList("SELECT pg_advisory_xact_lock(730013,?)",Math.toIntExact(date.toEpochDay()));
        Long sequence = jdbc.queryForObject("INSERT INTO delivery_ftp_day_sequence(delivery_entity_id,delivery_date,next_value) VALUES(?,?,2) " +
                "ON CONFLICT(delivery_entity_id,delivery_date) DO UPDATE SET next_value=delivery_ftp_day_sequence.next_value+1,updated_at=now() " +
                "RETURNING next_value-1", Long.class, entityId, date);
        // 同实体日期行锁已取得；已预留任务也计入容量，防止并发批次突破单 FTP 六千文件。
        Integer count = jdbc.queryForObject("SELECT count(*) FROM delivery_task WHERE delivery_entity_id=? AND delivery_date=?",
                Integer.class, entityId, date);
        if (count >= MAX_FILES_PER_ENTITY) throw new IllegalStateException("FTP daily file capacity exhausted");
        if (sequence == null || sequence < 1 || sequence > 9999999) {
            throw new IllegalStateException("FTP daily sequence exhausted");
        }
        return new Allocation(sequence, String.format("batch_%07d.tar.gz", sequence));
    }

    public record Allocation(long sequence, String fileName) {}
}
