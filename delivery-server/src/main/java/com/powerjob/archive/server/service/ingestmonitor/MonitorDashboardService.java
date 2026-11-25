package com.powerjob.archive.server.service.ingestmonitor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** 只读看板投影：存量、观察到的进数、真实目标上传分别计算，不创建交付或费用关系表。 */
@Service
public class MonitorDashboardService {
    private final JdbcTemplate jdbc;
    private final MonitorStore store;
    private final OssCostService costs;
    public MonitorDashboardService(JdbcTemplate jdbc,MonitorStore store,OssCostService costs) {
        this.jdbc=jdbc;this.store=store;this.costs=costs;
    }

    public Map<String,Object> overview(Instant now) {
        // 费用只估算各桶实际监控的存量；同桶渠道合并计算，不以累计上传量冒充目标桶当前存储。
        var channels=channels(now);
        return Map.of("channels",channels,"bucketCosts",costs.buckets(channels),"storageSummary",costs.total(channels),
                "pricing",costs.pricing(),"statisticsDate",now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString());
    }

    public List<Map<String,Object>> channels(Instant now) {
        // 进数总量只读范围总计且排除基线，防止叶目录重复累计；今天按北京时间完整日界计算。
        Instant start=now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        var result=store.status();
        var registered=jdbc.query("SELECT source_id,oss_uri FROM ingest_source",(rs,row)->new SourceSnapshot.Source(rs.getLong(1),rs.getString(2)));
        for (var channel : result) {
            @SuppressWarnings("unchecked") var scopeIds=(List<Long>)channel.get("sourceIds");
            var traffic=jdbc.queryForMap("SELECT min(observed_at) AS \"monitoringSince\",coalesce(sum(added_bytes+changed_bytes) FILTER(WHERE NOT is_baseline),0) AS \"incomingTotalBytes\","+
                    "coalesce(sum(added_bytes+changed_bytes) FILTER(WHERE NOT is_baseline AND observed_at>=? AND observed_at<=?),0) AS \"incomingTodayBytes\" "+
                    "FROM ingest_monitor_record WHERE record_type='SAMPLE' AND directory='' AND source_id=ANY(?::bigint[])",Timestamp.from(start),Timestamp.from(now),MonitorStore.ids(scopeIds));
            traffic.replaceAll((key,value)->value instanceof Timestamp time?time.toInstant().toString():value);
            channel.putAll(traffic);
            // 历史上传沿原 source_id 血缘查询，包含覆盖范围中未被监控重复扫描的业务来源。
            var ids=registered.stream().filter(source->source.key().equals(channel.get("channelKey"))).map(SourceSnapshot.Source::id).toList();
            var targets=uploads(ids,start,now);
            channel.put("destinations",targets);
            channel.put("deliveryTotalBytes",targets.stream().mapToLong(row->((Number)row.get("totalBytes")).longValue()).sum());
            channel.put("deliveryTodayBytes",targets.stream().mapToLong(row->((Number)row.get("todayBytes")).longValue()).sum());
            channel.put("costEstimate",costs.estimate(((Number)channel.get("sizeBytes")).longValue()));
        }
        return result;
    }

    private List<Map<String,Object>> uploads(List<Long> sourceIds,Instant start,Instant until) {
        // 同一目标 URI 的重试/回验只记一次，以首次成功时间归日；只有真实成功 oss.upload 的脱敏 locator 能计入。
        String sql="WITH uploaded AS (SELECT DISTINCT ON(n.output_locator->>'uri') n.output_locator->>'uri' AS uri,"+
                "(n.output_locator->>'sizeBytes')::bigint AS bytes,n.finished_at FROM delivery_node n JOIN delivery_submit d USING(delivery_id) "+
                "WHERE d.source_id=ANY(?::bigint[]) AND n.node_type='oss.upload' AND n.status='SUCCEEDED' AND n.finished_at IS NOT NULL AND n.finished_at<=? "+
                "AND n.output_locator->>'uri' ~ '^oss://[^/]+/.+' AND n.output_locator->>'sizeBytes' ~ '^[0-9]{1,18}$' "+
                "ORDER BY n.output_locator->>'uri',n.finished_at,n.process_id) "+
                "SELECT substring(uri FROM '^oss://([^/]+)') AS bucket,count(*) AS files,sum(bytes) AS \"totalBytes\","+
                "count(*) FILTER(WHERE finished_at>=?) AS \"todayFiles\",coalesce(sum(bytes) FILTER(WHERE finished_at>=?),0) AS \"todayBytes\","+
                "max(finished_at) AS \"lastTransferredAt\" FROM uploaded GROUP BY substring(uri FROM '^oss://([^/]+)') ORDER BY bucket";
        var rows=jdbc.queryForList(sql,MonitorStore.ids(sourceIds),Timestamp.from(until),Timestamp.from(start),Timestamp.from(start));
        rows.forEach(row->row.replaceAll((key,value)->value instanceof Timestamp time?time.toInstant().toString():value));
        return rows;
    }

}
