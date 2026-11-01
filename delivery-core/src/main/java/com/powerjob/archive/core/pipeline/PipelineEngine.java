package com.powerjob.archive.core.pipeline;

import com.powerjob.archive.core.domain.OutputPackage;
import com.powerjob.archive.core.domain.SourcePackage;
import com.powerjob.archive.core.repository.NodeRunRepository;
import com.powerjob.archive.core.repository.OutputPackageRepository;
import com.powerjob.archive.core.repository.SourcePackageRepository;
import com.powerjob.archive.core.service.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PipelineEngine {
    private static final Logger log = LoggerFactory.getLogger(PipelineEngine.class);
    private final SourcePackageRepository sources;
    private final OutputPackageRepository outputs;
    private final NodeRunRepository runs;
    private final PipelineRepository pipelines;
    private final RetryPolicy retries;
    private final Map<String, NodeHandler> handlers;

    public PipelineEngine(SourcePackageRepository sources, OutputPackageRepository outputs,
                          NodeRunRepository runs, PipelineRepository pipelines, RetryPolicy retries,
                          List<NodeHandler> nodeHandlers) {
        this.sources = sources;
        this.outputs = outputs;
        this.runs = runs;
        this.pipelines = pipelines;
        this.retries = retries;
        this.handlers = nodeHandlers.stream().collect(Collectors.toUnmodifiableMap(NodeHandler::type, Function.identity()));
    }

    public int executeNext(long lineId, String workerId) {
        var source = sources.claimNext(lineId, workerId);
        if (source.isPresent()) {
            executeSource(source.get(), workerId);
            return 1;
        }
        var output = outputs.claimNext(lineId, workerId);
        if (output.isPresent()) {
            executeOutput(output.get(), workerId);
            return 1;
        }
        return 0;
    }

    private void executeSource(SourcePackage source, String workerId) {
        NodeDefinition node = pipelines.read(source.pipelineSnapshot()).node(source.currentNode());
        long runId = runs.start(source.id(), null, node.id(), node.type(), source.fence(), workerId);
        try {
            NodeResult result = handler(node).execute(new NodeContext(source, null, node, runId, source.fence(), workerId));
            boolean terminal = result.nextNode() == null;
            sources.complete(source.id(), source.fence(), workerId, result.businessStatus(),
                    terminal ? node.id() : result.nextNode(),
                    result.manifest() == null ? null : result.manifest().toString(), terminal);
            runs.finish(runId, "SUCCEEDED", result.manifest() == null ? null : result.manifest().toString(), null);
            log.info("Source node succeeded sourcePackageId={} node={} status={}", source.id(), node.id(), result.businessStatus());
        } catch (Exception e) {
            runs.finish(runId, "FAILED", null, e.getMessage());
            sources.markRetry(source.id(), source.fence(), workerId, e.getClass().getSimpleName());
            log.warn("Source node failed sourcePackageId={} node={}", source.id(), node.id(), e);
        }
    }

    private void executeOutput(OutputPackage output, String workerId) {
        NodeDefinition node = pipelines.read(output.pipelineSnapshot()).node(output.currentNode());
        long runId = runs.start(null, output.id(), node.id(), node.type(), output.fence(), workerId);
        try {
            NodeResult result = handler(node).execute(new NodeContext(null, output, node, runId, output.fence(), workerId));
            if (result.kind() == NodeResult.ResultKind.WAITING) {
                outputs.markWaiting(output.id(), output.fence(), workerId,
                        result.manifest() == null ? null : result.manifest().toString(), result.nextPoll());
                runs.finish(runId, "WAITING", result.manifest() == null ? null : result.manifest().toString(), null);
                log.info("Output node waiting outputPackageId={} node={} nextPoll={}", output.id(), node.id(), result.nextPoll());
                return;
            }
            boolean terminal = result.nextNode() == null;
            outputs.complete(output.id(), output.fence(), workerId, result.businessStatus(),
                    terminal ? node.id() : result.nextNode(),
                    result.manifest() == null ? null : result.manifest().toString(), terminal);
            runs.finish(runId, "SUCCEEDED", result.manifest() == null ? null : result.manifest().toString(), null);
            log.info("Output node succeeded outputPackageId={} node={} status={}", output.id(), node.id(), result.businessStatus());
        } catch (Exception e) {
            runs.finish(runId, "FAILED", null, e.getMessage());
            outputs.markFailure(output.id(), output.fence(), workerId, retries.onFailure(e, output.retryCount(), 5));
            log.warn("Output node failed outputPackageId={} node={}", output.id(), node.id(), e);
        }
    }

    private NodeHandler handler(NodeDefinition node) {
        NodeHandler handler = handlers.get(node.type());
        if (handler == null) throw new IllegalStateException("No NodeHandler for " + node.type());
        return handler;
    }
}
