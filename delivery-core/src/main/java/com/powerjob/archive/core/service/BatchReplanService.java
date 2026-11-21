package com.powerjob.archive.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.powerjob.archive.core.domain.DeliverySubmit;
import com.powerjob.archive.core.domain.DeliveryTask;
import com.powerjob.archive.core.domain.SourceData;
import com.powerjob.archive.core.repository.DeliveryRepository;
import com.powerjob.archive.core.repository.DeliveryTaskRepository;
import com.powerjob.archive.core.repository.SourceDataRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BatchReplanService {
    private final JdbcTemplate jdbc;
    private final DeliveryTaskRepository tasks;
    private final SourceDataRepository sources;
    private final DeliveryRepository deliveries;
    private final ObjectMapper mapper;

    public BatchReplanService(JdbcTemplate jdbc, DeliveryTaskRepository tasks, SourceDataRepository sources,
                              DeliveryRepository deliveries, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.sources = sources;
        this.deliveries = deliveries;
        this.mapper = mapper;
    }

    public ReplanResult moveLastSourceToNewPackage(long taskId, long fence, String workerId) {
        DeliveryTask task = tasks.findById(taskId).orElseThrow();
        List<SourceData> taskSources = sources.byTaskId(taskId);
        if (taskSources.size() < 2) {
            throw new IllegalStateException("A single source package exceeds the final limit and cannot be split");
        }
        DeliverySubmit submit = deliveries.find(task.deliveryId()).orElseThrow();
        long maxPackageBytes = submit.batchExtension().required("maxPackageBytes").asLong();
        long targetBytes = maxPackageBytes * 95 / 100;
        long totalBytes = taskSources.stream().mapToLong(SourceData::sizeBytes).sum();
        // 对半拆分并保留字节边界，避免逐包移出导致同一任务反复下载、合并和 repair。
        int moveCount = Math.max(1, taskSources.size() / 2);
        while (moveCount < taskSources.size()
                && totalBytes - taskSources.stream().skip(taskSources.size() - moveCount)
                .mapToLong(SourceData::sizeBytes).sum() > targetBytes) {
            moveCount++;
        }
        if (moveCount >= taskSources.size()) {
            throw new IllegalStateException("Cannot split the batch into a package within the configured bounds");
        }
        jdbc.queryForObject("SELECT delivery_id FROM delivery_submit WHERE delivery_id=? AND submission_type='BATCH_CREATE' FOR UPDATE",
                Long.class, submit.batchDeliveryId());
        String previous = jdbc.queryForObject("SELECT max(new_file_name) FROM delivery_batch WHERE batch_delivery_id=?",
                String.class, submit.batchDeliveryId());
        int sequence = Integer.parseInt(previous.replaceFirst("^batch_", "").replaceFirst("\\.tar\\.gz$", "")) + 1;
        String newFileName = String.format("batch_%05d.tar.gz", sequence);
        List<SourceData> moved = taskSources.subList(taskSources.size() - moveCount, taskSources.size());
        for (int index = 0; index < moved.size(); index++) {
            SourceData source = moved.get(index);
            int changed = jdbc.update("UPDATE delivery_batch SET new_file_name=?,source_seq=?,delivery_id=NULL,status='PLANNED'," +
                            "started_at=NULL,completed_at=NULL WHERE batch_delivery_id=? AND delivery_id=? AND new_file_name=? " +
                            "AND data_id=? AND source_seq=? AND status<>'DELIVERED'",
                    newFileName, index + 1, submit.batchDeliveryId(), task.deliveryId(), task.newFileName(),
                    source.dataId(), taskSources.size() - moveCount + index + 1);
            if (changed != 1) throw new IllegalStateException("The overflow batch source changed concurrently");
        }
        ArrayNode artifacts = task.artifacts().deepCopy();
        for (int index = 0; index < moveCount; index++) artifacts.remove(artifacts.size() - 1);
        int updated = jdbc.update("UPDATE delivery_task t SET artifacts=?::jsonb," +
                        "size_bytes=(SELECT sum(sd.size_bytes) FROM delivery_batch b JOIN source_data sd ON sd.data_id=b.data_id " +
                        "WHERE b.delivery_id=t.delivery_id AND b.new_file_name=t.new_file_name)," +
                        "token_count=(SELECT sum(sd.token_count) FROM delivery_batch b JOIN source_data sd ON sd.data_id=b.data_id " +
                        "WHERE b.delivery_id=t.delivery_id AND b.new_file_name=t.new_file_name)," +
                        "current_node_id='sourceDownload',execution_status='READY',lease_owner=NULL,lease_until=NULL," +
                        "retry_count=0,next_retry_at=NULL,last_error_code=NULL,completed_at=NULL,updated_at=now() " +
                        "WHERE task_id=? AND fence=? AND lease_owner=? AND execution_status='RUNNING'",
                artifacts.toString(), taskId, fence, workerId);
        if (updated != 1) throw new IllegalStateException("Stale task fence during overflow replan");
        return new ReplanResult(moved.stream().map(SourceData::dataId).toList(), newFileName);
    }

    public record ReplanResult(List<Long> dataIds, String newFileName) {}
}
