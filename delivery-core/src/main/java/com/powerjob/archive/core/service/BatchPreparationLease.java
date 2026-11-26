package com.powerjob.archive.core.service;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.function.IntSupplier;

/** 同一准备单仅允许一个物理执行者；会话锁随断连释放，不持有父批次行锁或长事务。 */
@Service
public class BatchPreparationLease {
    private final JdbcTemplate jdbc;

    public BatchPreparationLease(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public int run(long deliveryId, IntSupplier preparation) {
        // PowerJob 可重复触发仍在 DISCOVERING 的批次；未取得锁的触发立即退出，不进行任何下载。
        return jdbc.execute((ConnectionCallback<Integer>) connection -> {
            String key = "archive-batch-" + deliveryId;
            try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(730012,hashtext(?))")) {
                statement.setString(1, key);
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (!result.getBoolean(1)) return 0;
                }
            }
            // 只有轻量会话锁覆盖外部 IO，普通事实写入和投影仍使用各自短事务。
            try {
                return preparation.getAsInt();
            } finally {
                try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(730012,hashtext(?))")) {
                    statement.setString(1, key);
                    statement.execute();
                }
            }
        });
    }
}
