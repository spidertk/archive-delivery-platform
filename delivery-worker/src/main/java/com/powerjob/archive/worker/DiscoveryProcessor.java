package com.powerjob.archive.worker;

import com.powerjob.archive.core.service.OssDiscoveryService;
import com.powerjob.archive.core.service.PlanningService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tech.powerjob.worker.core.processor.ProcessResult;
import tech.powerjob.worker.core.processor.TaskContext;
import tech.powerjob.worker.core.processor.sdk.BasicProcessor;

import java.util.List;

@Component
@ConditionalOnProperty(name = "archive.discovery-mode", havingValue = "oss")
public class DiscoveryProcessor implements BasicProcessor {
    private final OssDiscoveryService discovery;
    private final PlanningService planning;
    private final JdbcTemplate jdbc;

    public DiscoveryProcessor(OssDiscoveryService discovery, PlanningService planning, JdbcTemplate jdbc) {
        this.discovery = discovery;
        this.planning = planning;
        this.jdbc = jdbc;
    }

    @Override
    public ProcessResult process(TaskContext context) {
        List<Long> runs = jdbc.queryForList("SELECT id FROM delivery_run WHERE status='DISCOVERING' ORDER BY id", Long.class);
        int discovered = 0;
        for (long runId : runs) discovered += discovery.discover(runId);
        for (long runId : runs) planning.plan(runId, null);
        return new ProcessResult(true, "discovered=" + discovered + ",runs=" + runs.size());
    }
}
