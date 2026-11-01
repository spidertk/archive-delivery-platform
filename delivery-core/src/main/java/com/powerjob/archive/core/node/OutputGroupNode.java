package com.powerjob.archive.core.node;

import com.powerjob.archive.core.config.MvpProperties;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeHandler;
import com.powerjob.archive.core.pipeline.NodeResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

@Component
public class OutputGroupNode implements NodeHandler {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final MvpProperties properties;

    public OutputGroupNode(JdbcTemplate jdbc, TransactionTemplate transactions, MvpProperties properties) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.properties = properties;
    }

    @Override
    public String type() { return "output.group"; }

    @Override
    public NodeResult execute(NodeContext context) {
        transactions.executeWithoutResult(status -> group(context));
        return NodeResult.of(null, "REPAIRED", context.source().manifest());
    }

    private void group(NodeContext context) {
        List<Long> existing = jdbc.query("SELECT id FROM output_package WHERE source_package_id=?",
                (rs, row) -> rs.getLong(1), context.source().id());
        if (!existing.isEmpty()) return;
        var group = jdbc.queryForMap("SELECT * FROM production_group WHERE production_line_id=? " +
                "AND status='FILLING' ORDER BY group_no FOR UPDATE LIMIT 1", context.source().productionLineId());
        long groupId = ((Number) group.get("id")).longValue();
        int assigned = ((Number) group.get("assigned_count")).intValue();
        int target = ((Number) group.get("target_count")).intValue();
        if (assigned >= target) throw new IllegalStateException("FILLING group is already full");
        int sequence = assigned + 1;
        String archiveDate = properties.getArchiveDate();
        LocalDate deliveryDate = LocalDate.parse(archiveDate);
        var cutoff = deliveryDate.plusDays(1).atTime(LocalTime.parse(properties.getDeliveryCutoffTime()))
                .atZone(ZoneId.of(properties.getBusinessTimezone())).toOffsetDateTime();
        var artifact = context.source().manifest().required("artifacts").required(0);
        String packageName = artifact.required("name").asText();
        long outputId = jdbc.queryForObject("INSERT INTO output_package " +
                "(source_package_id,pipeline_version_id,production_group_id,production_line_id,archive_date," +
                "delivery_date,cutoff_at,group_seq,new_package_name,manifest) " +
                "VALUES (?,?,?,?,?::date,?::date,?, ?,?,?::jsonb) RETURNING id", Long.class,
                context.source().id(), context.source().pipelineVersionId(), groupId,
                context.source().productionLineId(), archiveDate, archiveDate, cutoff,
                sequence, packageName, context.source().manifest().toString());
        jdbc.update("INSERT INTO output_artifact(output_package_id,artifact_seq,original_file_name," +
                "new_file_name,size_bytes,sha256) VALUES (?,1,?,?,?,?)", outputId, packageName,
                packageName, artifact.required("bytes").asLong(), artifact.required("sha256").asText());
        jdbc.update("UPDATE request_mapping SET output_package_id=?,grouped_at=now() " +
                "WHERE source_member_id IN (SELECT id FROM source_member WHERE source_package_id=?)",
                outputId, context.source().id());
        jdbc.update("UPDATE production_group SET assigned_count=assigned_count+1," +
                "status=CASE WHEN assigned_count+1>=target_count THEN 'SEALED' ELSE status END," +
                "sealed_at=CASE WHEN assigned_count+1>=target_count THEN now() ELSE sealed_at END WHERE id=?", groupId);
    }
}
