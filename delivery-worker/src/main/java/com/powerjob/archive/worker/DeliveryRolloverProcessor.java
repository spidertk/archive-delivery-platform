package com.powerjob.archive.worker;

import com.powerjob.archive.core.service.DeliveryRolloverService;
import org.springframework.stereotype.Component;
import tech.powerjob.worker.core.processor.ProcessResult;
import tech.powerjob.worker.core.processor.TaskContext;
import tech.powerjob.worker.core.processor.sdk.BasicProcessor;

@Component
public class DeliveryRolloverProcessor implements BasicProcessor {
    private final DeliveryRolloverService rollovers;
    public DeliveryRolloverProcessor(DeliveryRolloverService rollovers) { this.rollovers = rollovers; }

    /** 截止扫描只调用跨日领域事务，不直接改任务日期或伪造新 taskId。 */
    @Override public ProcessResult process(TaskContext context) {
        return new ProcessResult(true, "regeneratedTasks=" + rollovers.rolloverDue());
    }
}
