package com.powerjob.archive.worker;

import com.powerjob.archive.core.service.DeliveryBatchService;
import com.powerjob.archive.core.service.DeliveryPreparationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tech.powerjob.worker.core.processor.ProcessResult;
import tech.powerjob.worker.core.processor.TaskContext;
import tech.powerjob.worker.core.processor.sdk.BasicProcessor;

import java.util.List;
import java.util.Map;

@Component
public class DeliveryPreparationProcessor implements BasicProcessor {
    private final DeliveryPreparationService preparation;
    private final DeliveryBatchService batches;
    private final JdbcTemplate jdbc;

    public DeliveryPreparationProcessor(DeliveryPreparationService preparation, DeliveryBatchService batches, JdbcTemplate jdbc) {
        this.preparation = preparation;
        this.batches = batches;
        this.jdbc = jdbc;
    }

    @Override public ProcessResult process(TaskContext context) {
        List<Map<String, Object>> deliveryIds = jdbc.queryForList("SELECT delivery_id,submission_type FROM delivery_submit " +
                "WHERE status IN('SUBMITTED','DISCOVERING') ORDER BY delivery_id LIMIT 20");
        int discovered = 0;
        for (Map<String, Object> delivery : deliveryIds) {
            long deliveryId = ((Number) delivery.get("delivery_id")).longValue();
            try {
                discovered += "BATCH_CREATE".equals(delivery.get("submission_type"))
                        ? batches.prepare(deliveryId) : preparation.prepare(deliveryId);
            } catch (RuntimeException error) {
                jdbc.update("UPDATE delivery_submit SET status='BLOCKED',updated_at=now() " +
                        "WHERE delivery_id=? AND discovery_completed_at IS NULL", deliveryId);
                throw error;
            }
        }
        return new ProcessResult(true, "discovered=" + discovered + ",deliveries=" + deliveryIds.size());
    }
}
