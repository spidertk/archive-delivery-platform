package com.powerjob.archive.core.pipeline;

public class PipelineVersionConflictException extends RuntimeException {
    public PipelineVersionConflictException(String message) {
        super(message);
    }
}
