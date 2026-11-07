package com.powerjob.archive.worker;

import com.powerjob.archive.core.service.DeliveryPreparationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tech.powerjob.worker.core.processor.ProcessResult;
import tech.powerjob.worker.core.processor.TaskContext;
import tech.powerjob.worker.core.processor.sdk.BasicProcessor;
import java.util.List;

@Component
public class DeliveryPreparationProcessor implements BasicProcessor {
    private final DeliveryPreparationService preparation;
    private final JdbcTemplate jdbc;

    public DeliveryPreparationProcessor(DeliveryPreparationService preparation, JdbcTemplate jdbc) {
        this.preparation = preparation;
        this.jdbc = jdbc;
    }

    /** PowerJob 只触发通用准备入口；重复触发由 discovery_completed_at 和任务唯一约束幂等。 */
    @Override public ProcessResult process(TaskContext context) {
        List<Long> deliveryIds = jdbc.queryForList("SELECT delivery_id FROM delivery_submit " +
                "WHERE status IN('SUBMITTED','DISCOVERING') ORDER BY delivery_id LIMIT 20", Long.class);
        int discovered = 0;
        for (long deliveryId : deliveryIds) discovered += preparation.prepare(deliveryId);
        return new ProcessResult(true, "discovered=" + discovered + ",deliveries=" + deliveryIds.size());
    }
}
