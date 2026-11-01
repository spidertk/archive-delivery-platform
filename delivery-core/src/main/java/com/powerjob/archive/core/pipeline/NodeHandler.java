package com.powerjob.archive.core.pipeline;

public interface NodeHandler {
    String type();
    NodeResult execute(NodeContext context) throws Exception;
}
