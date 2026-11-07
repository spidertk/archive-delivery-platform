package com.powerjob.archive.worker;

import com.powerjob.archive.core.config.ArchiveProperties;
import com.powerjob.archive.core.service.TaskExecutionService;
import org.springframework.stereotype.Component;
import tech.powerjob.worker.core.processor.ProcessResult;
import tech.powerjob.worker.core.processor.TaskContext;
import tech.powerjob.worker.core.processor.sdk.BroadcastProcessor;

@Component
public class TaskTickProcessor implements BroadcastProcessor {
    private final TaskExecutionService execution;
    private final ArchiveProperties properties;

    public TaskTickProcessor(TaskExecutionService execution, ArchiveProperties properties) {
        this.execution = execution;
        this.properties = properties;
    }

    /** 每个广播 Worker 每次最多 claim 一个节点；PG 有界并发与 fence 防止重复推进。 */
    @Override public ProcessResult process(TaskContext context) {
        return new ProcessResult(true, "completedNodes=" + execution.executeNext(properties.getWorkerId()));
    }
}
