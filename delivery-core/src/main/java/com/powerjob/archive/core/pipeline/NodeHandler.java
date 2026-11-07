package com.powerjob.archive.core.pipeline;

/** 每种数据面能力由独立 NodeHandler 提供，DAG 后继由当前 PipelineVersion 决定。 */
public interface NodeHandler {
    String type();
    NodeResult execute(NodeContext context) throws Exception;
}
