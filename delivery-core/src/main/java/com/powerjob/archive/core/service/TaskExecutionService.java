package com.powerjob.archive.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.config.ArchiveProperties;
import com.powerjob.archive.core.domain.DeliveryEntity;
import com.powerjob.archive.core.domain.DeliveryTask;
import com.powerjob.archive.core.domain.SourceData;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeDefinition;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.pipeline.PipelineDefinition;
import com.powerjob.archive.core.pipeline.PipelineEngine;
import com.powerjob.archive.core.pipeline.PipelineRepository;
import com.powerjob.archive.core.repository.DeliveryEventRepository;
import com.powerjob.archive.core.repository.DeliveryNodeRepository;
import com.powerjob.archive.core.repository.DeliveryRepository;
import com.powerjob.archive.core.repository.DeliveryTaskRepository;
import com.powerjob.archive.core.repository.SourceDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

@Service
public class TaskExecutionService {
    private static final Logger log = LoggerFactory.getLogger(TaskExecutionService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DeliveryTaskRepository tasks;
    private final SourceDataRepository sources;
    private final DeliveryRepository deliveries;
    private final DeliveryNodeRepository nodes;
    private final DeliveryEventRepository events;
    private final DeliveryProjectionService projections;
    private final PipelineRepository pipelines;
    private final PipelineEngine engine;
    private final RetryPolicy retries;
    private final ArchiveProperties properties;
    private final ObjectMapper mapper;

    public TaskExecutionService(JdbcTemplate jdbc, TransactionTemplate transactions, DeliveryTaskRepository tasks,
                                SourceDataRepository sources, DeliveryRepository deliveries, DeliveryNodeRepository nodes,
                                DeliveryEventRepository events, DeliveryProjectionService projections, PipelineRepository pipelines,
                                PipelineEngine engine, RetryPolicy retries, ArchiveProperties properties, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.tasks = tasks;
        this.sources = sources;
        this.deliveries = deliveries;
        this.nodes = nodes;
        this.events = events;
        this.projections = projections;
        this.pipelines = pipelines;
        this.engine = engine;
        this.retries = retries;
        this.properties = properties;
        this.mapper = mapper;
    }

    /** Worker 每次只推进一个任务的一个节点；这是任务状态与提交聚合的唯一写入口。 */
    public int executeNext(String workerId) {
        var claimed = tasks.claimNext(workerId, properties.getWorkerGroup(), properties.getMaxRunningTasks());
        if (claimed.isEmpty()) return 0;
        DeliveryTask task = claimed.get();
        PipelineDefinition rawPipeline = pipeline(task.deliveryId());
        NodeDefinition rawNode = rawPipeline.node(task.currentNodeId());
        PipelineDefinition pipeline = onlyCurrentNodeConfig(rawPipeline, rawNode.id());
        NodeDefinition node = pipeline.node(rawNode.id());
        long processId = nodes.start(task, node, workerId);
        try {
            SourceData source = sources.findById(task.dataId()).orElseThrow();
            DeliveryEntity entity = deliveries.entity(task.deliveryEntityId());
            NodeResult result = engine.execute(new NodeContext(task, source, entity, node, pipeline,
                    processId, task.fence(), workerId, nodes.succeededOutputs(task.taskId())));
            if (result.kind() == NodeResult.ResultKind.WAITING) {
                transactions.executeWithoutResult(status -> {
                    nodes.finish(processId, "WAITING", result.inputLocator(), result.outputLocator(), null, null);
                    tasks.markWaiting(task.taskId(), task.fence(), workerId, result.nextPoll().toSeconds());
                    projections.refresh(task.deliveryId());
                });
                return 1;
            }
            rawPipeline.requireSuccessor(node.id(), result.nextNode());
            LocatorSafety.validate(result.inputLocator());
            LocatorSafety.validate(result.outputLocator());
            if (result.outputLocator() != null && result.outputLocator().has("nodeId")
                    && !node.id().equals(result.outputLocator().get("nodeId").asText())) {
                throw new IllegalArgumentException("output_locator.nodeId must equal the current delivery node");
            }
            boolean terminal = result.nextNode() == null;
            transactions.executeWithoutResult(status -> {
                sources.applyPatch(task.dataId(), result.sourcePatch());
                nodes.finish(processId, "SUCCEEDED", result.inputLocator(), result.outputLocator(), null, null);
                tasks.complete(task.taskId(), task.fence(), workerId, terminal ? node.id() : result.nextNode(), result.sourcePatch(), terminal);
                events.append(task.deliveryId(), task.taskId(), processId, task.deliveryEntityId(), source.dataId(), "NODE_SUCCEEDED", "INFO",
                        null, "Pipeline node succeeded", mapper.createObjectNode().put("taskId", task.taskId())
                                .put("processId", processId).put("nodeId", node.id()), workerId, "node-succeeded-" + processId);
                projections.refresh(task.deliveryId());
            });
            log.info("Delivery node succeeded deliveryId={} taskId={} processId={} node={}", task.deliveryId(), task.taskId(), processId, node.id());
        } catch (Exception error) {
            fail(task, node, processId, workerId, error);
        }
        return 1;
    }

    private void fail(DeliveryTask task, NodeDefinition node, long processId, String workerId, Exception error) {
        RetryPolicy.Decision decision = retries.onFailure(error, task.retryCount(), 5);
        String detail = safeError(error);
        transactions.executeWithoutResult(status -> {
            nodes.finish(processId, "FAILED", null, null, decision.errorCode(), detail);
            tasks.markFailure(task.taskId(), task.fence(), workerId, decision);
            events.append(task.deliveryId(), task.taskId(), processId, task.deliveryEntityId(), task.dataId(), "NODE_FAILED",
                    "ERROR", decision.errorCode(), detail, mapper.createObjectNode().put("taskId", task.taskId())
                            .put("processId", processId).put("nodeId", node.id()), workerId, "node-failed-" + processId);
            projections.refresh(task.deliveryId());
        });
        log.warn("Delivery node failed deliveryId={} taskId={} processId={} node={} errorCode={}",
                task.deliveryId(), task.taskId(), processId, node.id(), decision.errorCode());
    }

    private PipelineDefinition pipeline(long deliveryId) {
        try {
            String definition = jdbc.queryForObject("SELECT pv.definition::text FROM delivery_submit s " +
                    "JOIN pipeline_version pv ON pv.pipeline_version_id=s.pipeline_version_id WHERE s.delivery_id=?", String.class, deliveryId);
            return pipelines.read(mapper.readTree(definition));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load task PipelineVersion", e);
        }
    }

    /** 当前节点上下文移除其他节点配置，Worker 不会把完整 Secret 集合交给 NodeHandler。 */
    private PipelineDefinition onlyCurrentNodeConfig(PipelineDefinition source, String currentNodeId) {
        return new PipelineDefinition(source.pipelineCode(), source.version(), source.config(), source.nodeDefinitions(), source.ruleSets(),
                source.nodes().stream().map(node -> new NodeDefinition(node.id(), node.type(), node.stageCode(), node.displayName(), node.next(),
                        node.id().equals(currentNodeId) ? node.config() : Map.of())).toList());
    }

    private static String safeError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        message = message.replaceAll("(?i)(accessKeyId|accessKeySecret|password|privateKey)=[^,;\\s]+", "$1=***");
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
