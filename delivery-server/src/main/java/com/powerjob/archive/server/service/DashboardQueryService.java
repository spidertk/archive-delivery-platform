package com.powerjob.archive.server.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardQueryService {
    private final JdbcTemplate jdbc;

    public DashboardQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.jdbc.setResultsMapCaseInsensitive(true);
    }

    public Map<String, Object> summary(Filter filter) {
        Map<String, Object> result = new LinkedHashMap<>();
        String outputWhere = outputWhere(filter);
        result.putAll(jdbc.queryForMap("SELECT count(*) AS \"outputTotal\"," +
                "count(*) FILTER (WHERE p.status='DELIVERED') AS delivered," +
                "count(*) FILTER (WHERE p.run_status='BLOCKED') AS blocked," +
                "count(*) FILTER (WHERE p.run_status='RETRY_WAIT') AS retrying," +
                "count(*) FILTER (WHERE p.run_status='WAITING') AS waiting," +
                "count(*) FILTER (WHERE p.rollover_count>0) AS rolledOver," +
                "count(*) FILTER (WHERE p.run_status='RUNNING' AND p.lease_until>=now()) AS \"activeOutputSlots\"," +
                "min(p.cutoff_at) FILTER (WHERE p.status<>'DELIVERED') AS \"nextCutoff\" " +
                "FROM output_package p JOIN source_package s ON s.id=p.source_package_id " + outputWhere));
        result.putAll(jdbc.queryForMap("SELECT count(*) AS discovered," +
                "count(*) FILTER (WHERE s.status='PLANNED') AS planned," +
                "count(*) FILTER (WHERE s.status='REPAIRED') AS repaired," +
                "count(*) FILTER (WHERE s.status='DISCOVERED') AS backlog," +
                "count(*) FILTER (WHERE s.status IN ('PLANNED','DOWNLOADED','REPAIRED') AND NOT EXISTS " +
                "(SELECT 1 FROM output_package o WHERE o.source_package_id=s.id)) AS ungrouped," +
                "count(*) FILTER (WHERE s.run_status='RUNNING' AND s.lease_until>=now()) AS \"activeSourceSlots\" " +
                "FROM source_package s " + sourceWhere(filter)));
        result.put("groups", groups(filter));
        return result;
    }

    public List<Map<String, Object>> groups(Filter filter) {
        return jdbc.queryForList("SELECT p.production_line_id AS lineId," +
                "count(*) AS outputTotal," +
                "count(*) FILTER (WHERE p.status='GROUPED') AS grouped," +
                "count(*) FILTER (WHERE p.status='HZ_UPLOADED') AS hzUploaded," +
                "count(*) FILTER (WHERE p.status='SG_VERIFIED') AS sgVerified," +
                "count(*) FILTER (WHERE p.status='SG_DOWNLOADED') AS sgDownloaded," +
                "count(*) FILTER (WHERE p.status='PUBLISHED') AS published," +
                "count(*) FILTER (WHERE p.status='DELIVERED') AS delivered," +
                "count(*) FILTER (WHERE p.run_status='RETRY_WAIT') AS retrying," +
                "count(*) FILTER (WHERE p.run_status='WAITING') AS waiting," +
                "count(*) FILTER (WHERE p.run_status='BLOCKED') AS blocked " +
                "FROM output_package p JOIN source_package s ON s.id=p.source_package_id " + outputWhere(filter) +
                " GROUP BY p.production_line_id ORDER BY p.production_line_id");
    }

    public Map<String, Object> packages(Filter filter, String status, String keyword, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        String where = outputWhere(filter);
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where += " AND (p.status=? OR p.run_status=?)";
            args.add(status);
            args.add(status);
        }
        if (keyword != null && !keyword.isBlank()) {
            where += " AND (p.new_package_name ILIKE ? OR p.hz_path ILIKE ? OR p.sg_source_path ILIKE ?)";
            String pattern = "%" + keyword + "%";
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }
        long total = jdbc.queryForObject("SELECT count(*) FROM output_package p JOIN source_package s ON s.id=p.source_package_id " + where,
                Long.class, args.toArray());
        args.add(safeSize);
        args.add(safePage * safeSize);
        List<Map<String, Object>> items = jdbc.queryForList("SELECT p.id,p.status,p.run_status AS runStatus," +
                "p.current_node AS currentNode,p.new_package_name AS packageName,p.delivery_date AS deliveryDate," +
                "p.archive_date AS archiveDate,p.production_line_id AS lineId,p.retry_count AS retryCount,p.fence," +
                "p.rollover_count AS rolloverCount,p.hz_path AS hzPath,p.sg_source_path AS sgPath," +
                "p.sg_download_path AS stagingPath,p.published_path AS publishedPath,p.last_error_code AS errorCode," +
                "s.run_id AS runId,a.sha256 AS artifactSha FROM output_package p JOIN source_package s ON s.id=p.source_package_id " +
                "JOIN output_artifact a ON a.output_package_id=p.id AND a.artifact_seq=1 " + where +
                " ORDER BY p.delivery_date,p.production_line_id,p.production_group_id,p.group_seq LIMIT ? OFFSET ?", args.toArray());
        return Map.of("items", items, "page", safePage, "size", safeSize, "total", total);
    }

    public Map<String, Object> packageDetail(long id) {
        return jdbc.queryForMap("SELECT p.*,s.run_id,a.sha256 AS artifact_sha FROM output_package p " +
                "JOIN source_package s ON s.id=p.source_package_id JOIN output_artifact a ON a.output_package_id=p.id " +
                "WHERE p.id=? AND a.artifact_seq=1", id);
    }

    public Map<String, Object> timeline(long id) {
        List<Map<String, Object>> nodes = jdbc.queryForList("SELECT id,node_id AS nodeId,node_type AS nodeType,attempt," +
                "status,worker_instance_id AS workerId,started_at AS startedAt,finished_at AS finishedAt," +
                "error_code AS errorCode,error_detail AS errorDetail,input_manifest AS inputManifest," +
                "output_manifest AS outputManifest FROM package_node_run WHERE output_package_id=? ORDER BY started_at,id", id);
        List<Map<String, Object>> events = jdbc.queryForList("SELECT e.run_id AS nodeRunId,e.event_type AS eventType," +
                "e.event_time AS eventTime,e.message,e.payload FROM package_event e JOIN package_node_run r ON r.id=e.run_id " +
                "WHERE r.output_package_id=? ORDER BY e.event_time,e.id", id);
        List<Map<String, Object>> audits = jdbc.queryForList("SELECT action,reason,before_content AS beforeContent," +
                "after_content AS afterContent,created_at AS createdAt FROM operation_audit WHERE output_package_id=? ORDER BY id", id);
        return Map.of("nodes", nodes, "events", events, "audits", audits);
    }

    public Map<String, Object> run(long runId) {
        return jdbc.queryForMap("SELECT r.id AS runId,r.run_code AS runCode,r.oss_uri AS ossUri,r.status," +
                "r.job_date AS jobDate,r.pipeline_version_id AS pipelineVersionId,pv.version_no AS pipelineVersion," +
                "r.created_at AS createdAt,r.updated_at AS updatedAt FROM delivery_run r " +
                "JOIN pipeline_version pv ON pv.id=r.pipeline_version_id WHERE r.id=?", runId);
    }

    private static String outputWhere(Filter filter) {
        return "WHERE s.run_id=" + filter.runId() +
                (filter.deliveryDate() == null ? "" : " AND p.delivery_date='" + filter.deliveryDate() + "'") +
                (filter.archiveDate() == null ? "" : " AND p.archive_date='" + filter.archiveDate() + "'") +
                (filter.lineId() == null ? "" : " AND p.production_line_id=" + filter.lineId()) +
                (filter.rolledOver() == null ? "" : " AND (p.rollover_count>0)=" + filter.rolledOver());
    }

    private static String sourceWhere(Filter filter) {
        return "WHERE s.run_id=" + filter.runId() +
                (filter.deliveryDate() == null ? "" : " AND s.process_date='" + filter.deliveryDate() + "'") +
                (filter.archiveDate() == null ? "" : " AND s.process_date='" + filter.archiveDate() + "'") +
                (filter.lineId() == null ? "" : " AND s.production_line_id=" + filter.lineId());
    }

    public record Filter(long runId, LocalDate deliveryDate, LocalDate archiveDate, Long lineId, Boolean rolledOver) {
        Object[] args() {
            return new Object[]{runId};
        }
    }
}
