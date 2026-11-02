package com.powerjob.archive.core.pipeline;

import com.powerjob.archive.core.domain.OutputPackage;
import com.powerjob.archive.core.domain.SourcePackage;

public record NodeContext(
        SourcePackage source,
        OutputPackage output,
        NodeDefinition node,
        PipelineDefinition pipeline,
        long runId,
        long fence,
        String workerId) {
    public long packageId() {
        return source != null ? source.id() : output.id();
    }
}
