package com.powerjob.archive.core.pipeline;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PipelineEngine {
    private final Map<String, NodeHandler> handlers;

    public PipelineEngine(List<NodeHandler> nodeHandlers) {
        this.handlers = nodeHandlers.stream().collect(Collectors.toUnmodifiableMap(NodeHandler::type, Function.identity()));
    }

    /** 通用执行器仅按 node_type 分派，不知道下载、repair、上传或验证之间的固定顺序。 */
    public NodeResult execute(NodeContext context) throws Exception {
        NodeHandler handler = handlers.get(context.node().type());
        if (handler == null) throw new IllegalStateException("No NodeHandler for " + context.node().type());
        return handler.execute(context);
    }
}
