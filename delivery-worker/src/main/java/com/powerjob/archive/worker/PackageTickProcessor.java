package com.powerjob.archive.worker;

import com.powerjob.archive.core.config.MvpProperties;
import com.powerjob.archive.core.pipeline.PipelineEngine;
import org.springframework.stereotype.Component;
import tech.powerjob.worker.core.processor.ProcessResult;
import tech.powerjob.worker.core.processor.TaskContext;
import tech.powerjob.worker.core.processor.sdk.BroadcastProcessor;

@Component
public class PackageTickProcessor implements BroadcastProcessor {
    private final PipelineEngine engine;
    private final MvpProperties properties;

    public PackageTickProcessor(PipelineEngine engine, MvpProperties properties) {
        this.engine = engine;
        this.properties = properties;
    }

    @Override
    public ProcessResult process(TaskContext context) {
        int completed = 0;
        completed += engine.executeNext(1L, properties.getWorkerId());
        completed += engine.executeNext(2L, properties.getWorkerId());
        return new ProcessResult(true, "completedNodes=" + completed);
    }
}
