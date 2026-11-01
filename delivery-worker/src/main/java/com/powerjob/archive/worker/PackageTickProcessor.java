package com.powerjob.archive.worker;

import com.powerjob.archive.core.config.MvpProperties;
import com.powerjob.archive.core.pipeline.PipelineEngine;
import org.springframework.stereotype.Component;
import tech.powerjob.worker.core.processor.ProcessResult;
import tech.powerjob.worker.core.processor.TaskContext;
import tech.powerjob.worker.core.processor.sdk.BroadcastProcessor;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class PackageTickProcessor implements BroadcastProcessor {
    private final PipelineEngine engine;
    private final MvpProperties properties;
    private final ThreadPoolExecutor executor;

    public PackageTickProcessor(PipelineEngine engine, MvpProperties properties) {
        this.engine = engine;
        this.properties = properties;
        this.executor = new ThreadPoolExecutor(0, 10, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(10));
    }

    @Override
    public ProcessResult process(TaskContext context) {
        if (!properties.isAsyncExecution()) {
            int completed = 0;
            if (properties.getLineId() == 0) {
                    completed += engine.executeNext(1L, properties.getWorkerId());
                    completed += engine.executeNext(2L, properties.getWorkerId());
                    completed += engine.executeNext(3L, properties.getWorkerId());
            } else {
                completed += engine.executeNext(properties.getLineId(), properties.getWorkerId());
            }
            return new ProcessResult(true, "completedNodes=" + completed);
        }
        int submitted = 0;
        long lineId = properties.getLineId();
        while (submitted < executor.getQueue().remainingCapacity()) {
            executor.execute(() -> engine.executeNext(lineId, properties.getWorkerId()));
            submitted++;
        }
        return new ProcessResult(true, "submitted=" + submitted + ",lineId=" + lineId);
    }

    @javax.annotation.PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
