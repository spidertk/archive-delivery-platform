package com.powerjob.archive.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.domain.DeliveryEntity;
import com.powerjob.archive.core.pipeline.PipelineRepository;
import com.powerjob.archive.core.repository.DeliveryEventRepository;
import com.powerjob.archive.core.repository.DeliveryRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 仅迁移未领取包的实体/执行组：包边界、源顺序及已领取任务全部保持不变。 */
@Service
public class BatchRedistributionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DeliveryRepository deliveries;
    private final PipelineRepository pipelines;
    private final DeliveryProjectionService projections;
    private final DeliveryEventRepository events;
    private final ObjectMapper mapper;

    public BatchRedistributionService(JdbcTemplate jdbc, TransactionTemplate transactions, DeliveryRepository deliveries,
            PipelineRepository pipelines, DeliveryProjectionService projections, DeliveryEventRepository events, ObjectMapper mapper) {
        this.jdbc=jdbc; this.transactions=transactions; this.deliveries=deliveries; this.pipelines=pipelines;
        this.projections=projections; this.events=events; this.mapper=mapper;
    }

    public Map<String,Object> redistribute(long batchId, Command command) {
        if (command == null || command.deliveryDate() == null || command.reason() == null || command.reason().isBlank()
                || command.targets() == null || command.targets().isEmpty() || command.expectedRemainingPackages() < 1) {
            throw new IllegalArgumentException("deliveryDate, expectedRemainingPackages, targets and reason are required");
        }
        return transactions.execute(status -> apply(batchId, command));
    }

    private Map<String,Object> apply(long batchId, Command command) {
        // 父单先锁，阻止消费、跨日和运维并发；外部 IO 不进入此事务。
        var batch = jdbc.queryForList("SELECT delivery_id FROM delivery_submit WHERE delivery_id=? AND submission_type='BATCH_CREATE' " +
                "AND discovery_completed_at IS NOT NULL FOR UPDATE", Long.class, batchId);
        if (batch.isEmpty()) throw new IllegalArgumentException("A fully planned BATCH_CREATE is required");
        Integer active = jdbc.queryForObject("SELECT count(*) FROM delivery_submit s WHERE s.batch_delivery_id=? " +
                "AND s.status<>'DELIVERED'", Integer.class, batchId);
        if (active != 0) throw new IdempotencyConflictException("Drain and verify all accepted submissions before redistribution");
        var policy = jdbc.queryForMap("SELECT pv.pipeline_version_id,pv.definition::text AS definition FROM pipeline_version pv " +
                "JOIN pipeline p ON p.pipeline_id=pv.pipeline_id WHERE p.pipeline_code='archive-batch' AND pv.status='PUBLISHED' " +
                "ORDER BY pv.version_no DESC LIMIT 1");
        List<BatchPlanningService.EntityWorker> workers;
        try { workers = BatchPlanningService.entityWorkers(pipelines.read(mapper.readTree(policy.get("definition").toString())), deliveries); }
        catch (Exception error) { throw new IllegalArgumentException("Invalid published archive-batch policy",error); }
        List<Destination> destinations = resolve(command, workers);
        var packages = jdbc.query("SELECT b.new_file_name,sum(sd.size_bytes) FROM delivery_batch b JOIN source_data sd ON sd.data_id=b.data_id " +
                "WHERE b.batch_delivery_id=? AND b.delivery_id IS NULL AND b.status='PLANNED' GROUP BY b.new_file_name ORDER BY b.new_file_name",
                (rs,n) -> new Package(rs.getString(1),rs.getLong(2)), batchId);
        if (packages.size()!=command.expectedRemainingPackages() || destinations.stream().mapToInt(d->d.remaining).sum()!=packages.size()) {
            throw new IdempotencyConflictException("Remaining package snapshot or requested coverage changed");
        }
        List<Object[]> updates = new ArrayList<>();
        // 大包优先仅用于选择执行机，canonical 包名/原文件/包内 source_seq 完全不变；消费仍按 canonical 顺序。
        var assignmentOrder=packages.stream().sorted(java.util.Comparator.comparingLong(Package::bytes).reversed()
                .thenComparing(Package::name)).toList();
        for (var item : assignmentOrder) {
            // 只改变完整包的归属；以文件容量配额加权来源字节，防止较多包压满较小磁盘，硬容量计数优先。
            Destination selected = destinations.stream().filter(d->d.remaining>0)
                    .min(java.util.Comparator.comparingLong(d->d.bytes*d.count)).orElseThrow();
            updates.add(new Object[]{selected.entity.deliveryEntityId(), selected.entity.entityCode(), selected.worker, batchId, item.name});
            selected.remaining--; selected.bytes+=item.bytes;
        }
        jdbc.batchUpdate("UPDATE delivery_batch SET delivery_entity_id=?,entity_code=?,worker_group=? WHERE batch_delivery_id=? " +
                "AND new_file_name=? AND delivery_id IS NULL AND status='PLANNED'", updates);
        var payload=mapper.createObjectNode().put("schemaVersion",1).put("policyPipelineVersionId",((Number)policy.get("pipeline_version_id")).longValue())
                .put("packageCount",packages.size()).put("deliveryDate",command.deliveryDate().toString()).put("reason",command.reason());
        var allocations=payload.putArray("allocations");
        destinations.forEach(d->allocations.addObject().put("entityCode",d.entity.entityCode()).put("workerGroup",d.worker)
                .put("packageCount",d.count).put("sourceBytes",d.bytes));
        events.append(batchId,null,null,null,null,"BATCH_REDISTRIBUTED","INFO",null,
                "Unconsumed whole packages reassigned without repacking",payload,"api","redistribute-"+UUID.randomUUID());
        // 规划分布是当前事实；原 PipelineVersion 和历史提交快照保留，新提交读取修正后的总分配。
        var totals=mapper.createArrayNode();
        jdbc.query("SELECT entity_code,worker_group,count(DISTINCT new_file_name) AS packages FROM delivery_batch " +
                "WHERE batch_delivery_id=? GROUP BY 1,2 ORDER BY 1,2",rs->{
            totals.addObject().put("entityWorker",rs.getString("entity_code")+"/"+rs.getString("worker_group"))
                    .put("packageCount",rs.getInt("packages"));
        },batchId);
        jdbc.update("UPDATE delivery_submit SET batch_extension=jsonb_set(batch_extension,'{allocations}',?::jsonb,true) WHERE delivery_id=?",
                totals.toString(),batchId);
        projections.refresh(batchId);
        return Map.of("batchDeliveryId",batchId,"packageCount",packages.size(),"allocations",allocations);
    }

    private List<Destination> resolve(Command command, List<BatchPlanningService.EntityWorker> workers) {
        // 运维输入仅使用业务键和目标数量，实际 worker_group 必须来自已发布 Pipeline。
        List<Destination> result=new ArrayList<>();
        var seen=new HashSet<Long>();
        for (Target target:command.targets()) {
            DeliveryEntity entity=deliveries.entityByBusinessCode(target.vendorCode(),target.entityCode());
            var candidates=workers.stream().filter(w->w.entity().deliveryEntityId()==entity.deliveryEntityId()).toList();
            if (!entity.enabled() || !seen.add(entity.deliveryEntityId()) || candidates.size()!=1 || target.targetCount()<1) {
                throw new IllegalArgumentException("Enabled distinct entities with one published worker group are required");
            }
            Integer used=jdbc.queryForObject("SELECT count(*) FROM delivery_task WHERE delivery_entity_id=? AND delivery_date=?",
                    Integer.class,entity.deliveryEntityId(),command.deliveryDate());
            if (used+target.targetCount()>FtpFileNameAllocator.MAX_FILES_PER_ENTITY) {
                throw new IllegalArgumentException("Per-FTP daily planned files exceed 6000");
            }
            result.add(new Destination(entity,candidates.get(0).workerGroup(),target.targetCount()));
        }
        return result;
    }

    public record Command(LocalDate deliveryDate,int expectedRemainingPackages,List<Target> targets,String reason) {}
    public record Target(String vendorCode,String entityCode,int targetCount) {}
    private record Package(String name,long bytes) {}
    private static class Destination {
        final DeliveryEntity entity; final String worker; final int count; int remaining; long bytes;
        Destination(DeliveryEntity entity,String worker,int count) { this.entity=entity;this.worker=worker;this.count=count;this.remaining=count; }
    }
}
