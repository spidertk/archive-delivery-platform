package com.powerjob.archive.server.service.ingestmonitor;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Repository
public class MonitorStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private static final long LOCK=817_263_026L;
    public record Scope(long id,String key,String name,String bucket,List<Long> sourceIds) {}
    public record State(String phase, Instant continuousSince, Instant lastGrowth, Instant observed, Instant failed,
                        Instant lastReport, long reportRecordId) {}
    public MonitorStore(JdbcTemplate jdbc,TransactionTemplate transactions) { this.jdbc=jdbc;this.transactions=transactions; }

    public void exclusively(Runnable work) {
        // 同一 PG 连接持有并释放会话锁；外层锁与短观察事务使用不同连接，多实例不重复扫描或日报。
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (var statement=connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                statement.setLong(1,LOCK);
                try (var result=statement.executeQuery()) {result.next();if (!result.getBoolean(1)) return null;}
            }
            try {work.run();}
            finally {try (var statement=connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {statement.setLong(1,LOCK);statement.execute();}}
            return null;
        });
    }

    public List<SourceSnapshot.Source> sources() {
        // 渠道按桶/第一级目录确定；已被父范围覆盖的来源不重复扫描或累计，实际登记 URI 不改变。
        var registered=jdbc.query("SELECT source_id,oss_uri FROM ingest_source WHERE availability_status='AVAILABLE' AND oss_type='ALIBABA_CLOUD'",
                (rs,row) -> new SourceSnapshot.Source(rs.getLong(1),rs.getString(2)));
        registered.sort(Comparator.comparingInt(source -> source.prefix().length()));
        List<SourceSnapshot.Source> selected=new ArrayList<>();
        for (var source : registered) {
            boolean covered=selected.stream().anyMatch(parent -> parent.bucket().equals(source.bucket()) && source.prefix().startsWith(parent.prefix()));
            if (!covered) selected.add(source);
        }
        selected.sort(Comparator.comparingLong(SourceSnapshot.Source::id));
        return selected;
    }

    public List<Scope> scopes() {
        // 一个桶的同一级目录共用最早登记行的窗口状态；停用某条扫描范围不改变渠道身份。
        var all=jdbc.query("SELECT source_id,oss_uri FROM ingest_source ORDER BY source_id",(rs,row) -> new SourceSnapshot.Source(rs.getLong(1),rs.getString(2)));
        var groups=sources().stream().collect(Collectors.groupingBy(SourceSnapshot.Source::key,LinkedHashMap::new,Collectors.toList()));
        List<Scope> result=new ArrayList<>();
        for (var entry : groups.entrySet()) {
            var first=entry.getValue().get(0);
            long owner=all.stream().filter(source -> source.key().equals(entry.getKey())).mapToLong(SourceSnapshot.Source::id).min().orElseThrow();
            result.add(new Scope(owner,entry.getKey(),first.name(),first.bucket(),entry.getValue().stream().map(SourceSnapshot.Source::id).toList()));
        }
        return result;
    }

    public List<Long> sourceIds(long sourceId) {
        String uri=jdbc.queryForObject("SELECT oss_uri FROM ingest_source WHERE source_id=?",String.class,sourceId);
        String key=new SourceSnapshot.Source(sourceId,uri).key();
        return scopes().stream().filter(scope -> scope.key().equals(key)).findFirst().map(Scope::sourceIds).orElse(List.of());
    }

    public void breakContinuity(long sourceId) {
        // 任一登记范围观察中断，整个逻辑渠道的未达标连续窗口重计；已通知窗口保留待报尾量。
        for (var scope : scopes()) {
            if (!scope.sourceIds().contains(sourceId)) continue;
            jdbc.update("UPDATE ingest_source SET monitor_phase='IDLE',monitor_continuous_since=NULL WHERE source_id=? AND monitor_phase='OBSERVING'",scope.id());
        }
    }

    public State state(long sourceId) {
        return jdbc.queryForObject("SELECT * FROM ingest_source WHERE source_id=?",(rs,row) -> new State(rs.getString("monitor_phase"),
                instant(rs.getTimestamp("monitor_continuous_since")),instant(rs.getTimestamp("monitor_last_growth_at")),
                instant(rs.getTimestamp("monitor_observed_at")),instant(rs.getTimestamp("monitor_failed_at")),
                instant(rs.getTimestamp("monitor_last_report_at")),rs.getLong("monitor_report_record_id")),sourceId);
    }

    public void beginSnapshot() {
        // 当前事务只缓存一份完整 LIST；失败回滚临时表，不会把缺页误判成删除。
        jdbc.execute("CREATE TEMP TABLE ingest_monitor_scan (object_key text PRIMARY KEY,size_bytes bigint NOT NULL,etag varchar(256),modified_at timestamptz NOT NULL) ON COMMIT DROP");
    }

    public void stage(List<SourceSnapshot.Item> page) {
        // 每页最多 1000 个对象，Java 不保留完整对象清单或目录树。
        jdbc.batchUpdate("INSERT INTO ingest_monitor_scan VALUES(?,?,?,?) ON CONFLICT(object_key) DO UPDATE SET size_bytes=EXCLUDED.size_bytes,etag=EXCLUDED.etag,modified_at=EXCLUDED.modified_at",
                page,500,(ps,item) -> {ps.setString(1,item.key());ps.setLong(2,item.size());ps.setString(3,item.etag());ps.setTimestamp(4,Timestamp.from(item.modifiedAt()));});
    }

    public SourceSnapshot.Counts compare(long sourceId,String channelPrefix) {
        // 文件所属目录相对桶的一级渠道根；只保留实际变化的叶目录，不创建祖先目录树。
        jdbc.execute("ANALYZE ingest_monitor_scan");
        String sql="WITH old AS (SELECT * FROM ingest_monitor_record WHERE record_type='OBJECT' AND source_id=?),diff AS ("+
                "SELECT coalesce(n.object_key,o.object_key) AS key,n.object_key IS NOT NULL AS present,coalesce(n.size_bytes,0) AS bytes,"+
                "o.object_key IS NULL AS added,n.object_key IS NULL AS removed,coalesce(o.size_bytes,0) AS old_bytes,"+
                "(n.object_key IS NOT NULL AND o.object_key IS NOT NULL AND ROW(n.size_bytes,n.etag,n.modified_at) IS DISTINCT FROM ROW(o.size_bytes,o.etag,o.modified_at)) AS changed "+
                "FROM ingest_monitor_scan n FULL JOIN old o USING(object_key)),grouped AS ("+
                "SELECT *,coalesce(nullif(regexp_replace(substr(key,?),'[^/]+$',''),''),'(根目录)') AS directory FROM diff) "+
                "SELECT CASE WHEN grouping(directory)=1 THEN '' ELSE directory END AS directory,count(*) FILTER(WHERE present) AS objects,coalesce(sum(bytes),0) AS bytes,"+
                "count(*) FILTER(WHERE added) AS added,coalesce(sum(bytes) FILTER(WHERE added),0) AS added_bytes,"+
                "count(*) FILTER(WHERE changed) AS changed,coalesce(sum(bytes) FILTER(WHERE changed),0) AS changed_bytes,"+
                "count(*) FILTER(WHERE removed) AS removed,coalesce(sum(old_bytes) FILTER(WHERE removed),0) AS removed_bytes "+
                "FROM grouped GROUP BY GROUPING SETS ((directory),())";
        jdbc.update("CREATE TEMP TABLE ingest_monitor_diff_groups ON COMMIT DROP AS "+sql,sourceId,channelPrefix.codePointCount(0,channelPrefix.length())+1);
        return jdbc.queryForObject("SELECT * FROM ingest_monitor_diff_groups WHERE directory=''",(rs,row) -> new SourceSnapshot.Counts(
                rs.getLong("objects"),rs.getLong("bytes"),rs.getLong("added"),rs.getLong("added_bytes"),
                rs.getLong("changed"),rs.getLong("changed_bytes"),rs.getLong("removed"),rs.getLong("removed_bytes")));
    }

    public void transaction(Runnable work) {transactions.executeWithoutResult(status -> work.run());}

    public void save(long sourceId,State state,SourceSnapshot.Counts total,boolean baseline) {
        // 对象、增量和来源内监控状态同事务更新；已有目录继续进数同样产生变化记录。
        jdbc.update("DELETE FROM ingest_monitor_record o WHERE record_type='OBJECT' AND source_id=? AND NOT EXISTS(SELECT 1 FROM ingest_monitor_scan n WHERE n.object_key=o.object_key)",sourceId);
        jdbc.update("INSERT INTO ingest_monitor_record(record_type,source_id,object_key,size_bytes,etag,modified_at) SELECT 'OBJECT',?,n.object_key,n.size_bytes,n.etag,n.modified_at "+
                "FROM ingest_monitor_scan n LEFT JOIN ingest_monitor_record o ON o.record_type='OBJECT' AND o.source_id=? AND o.object_key=n.object_key "+
                "WHERE o.object_key IS NULL OR ROW(n.size_bytes,n.etag,n.modified_at) IS DISTINCT FROM ROW(o.size_bytes,o.etag,o.modified_at) "+
                "ON CONFLICT(source_id,object_key) WHERE record_type='OBJECT' DO UPDATE SET size_bytes=EXCLUDED.size_bytes,etag=EXCLUDED.etag,modified_at=EXCLUDED.modified_at",sourceId,sourceId);
        jdbc.update("INSERT INTO ingest_monitor_record(source_id,observed_at,directory,object_count,size_bytes,added_objects,added_bytes,changed_objects,changed_bytes,removed_objects,removed_bytes,is_baseline) "+
                "SELECT ?,?,directory,objects,bytes,added,added_bytes,changed,changed_bytes,removed,removed_bytes,? FROM ingest_monitor_diff_groups "+
                "WHERE directory='' OR (NOT ? AND added+changed+removed>0)",sourceId,Timestamp.from(state.observed()),baseline,baseline);
        jdbc.update("UPDATE ingest_source SET monitor_last_growth_at=?,monitor_observed_at=?,monitor_object_count=?,monitor_size_bytes=?,"+
                "monitor_last_error_code=NULL,monitor_failed_at=NULL,previous_refresh_bytes=latest_refresh_bytes,latest_refresh_bytes=? WHERE source_id=?",
                timestamp(state.lastGrowth()),timestamp(state.observed()),total.objects(),total.bytes(),total.bytes(),sourceId);
    }

    public void failed(long sourceId,Instant now) {
        jdbc.update("UPDATE ingest_source SET monitor_last_error_code='SCAN_FAILED',monitor_failed_at=? WHERE source_id=?",Timestamp.from(now),sourceId);
        breakContinuity(sourceId);
    }
    public void resetPendingWindow(long sourceId) {
        jdbc.update("UPDATE ingest_source SET monitor_report_record_id=(SELECT coalesce(max(record_id),0) FROM ingest_monitor_record WHERE record_type='SAMPLE' AND source_id=?) WHERE source_id=?",sourceId,sourceId);
    }

    public List<Map<String,Object>> status() {
        // 一个逻辑渠道合并多条扫描范围；待发数按通知去重，不把一条聚合消息重复累计。
        List<Map<String,Object>> result=new ArrayList<>();
        for (var scope : scopes()) {
            var ids=scope.sourceIds();
            Map<String,Object> row=jdbc.queryForMap("SELECT sum(monitor_object_count) AS \"objectCount\",sum(monitor_size_bytes) AS \"sizeBytes\",CASE WHEN count(monitor_observed_at)=count(*) THEN min(monitor_observed_at) END AS \"observedAt\","+
                    "max(monitor_last_growth_at) AS \"lastGrowthAt\",max(monitor_last_error_code) AS \"lastErrorCode\",max(monitor_failed_at) AS \"failedAt\" FROM ingest_source WHERE source_id=ANY(?::bigint[])",ids(ids));
            var state=state(scope.id());
            row.put("sourceId",scope.id());row.put("sourceIds",ids);row.put("channelKey",scope.key());row.put("channelCode",scope.name());
            row.put("bucket",scope.bucket());row.put("ossUri",scope.key());row.put("availability","AVAILABLE");row.put("phase",state.phase());row.put("continuousSince",timestamp(state.continuousSince()));
            row.put("pendingNotifications",jdbc.queryForObject("SELECT count(*) FROM ingest_monitor_notification WHERE source_ids && ?::bigint[] AND sent_at IS NULL AND cancelled_at IS NULL",Long.class,ids(ids)));
            result.add(row);
        }
        return publicRows(result);
    }

    public List<Map<String,Object>> trend(long sourceId,Instant from,Instant until) {
        // 每小时总量携带各范围此前最后快照，避免同一渠道的扫描跨整点时少算一个范围。
        String ids=ids(sourceIds(sourceId));
        String sql="WITH hours AS (SELECT DISTINCT date_trunc('hour',observed_at) AS hour FROM ingest_monitor_record WHERE record_type='SAMPLE' AND source_id=ANY(?::bigint[]) AND directory='' AND observed_at>=? AND observed_at<=?) "+
                "SELECT h.hour,sum(coalesce(last.object_count,0)) AS \"objectCount\",sum(coalesce(last.size_bytes,0)) AS \"sizeBytes\","+
                "sum(d.added_objects) AS \"addedObjects\",sum(d.added_bytes) AS \"addedBytes\",sum(d.changed_objects) AS \"changedObjects\",sum(d.changed_bytes) AS \"changedBytes\",sum(d.removed_objects) AS \"removedObjects\",sum(d.removed_bytes) AS \"removedBytes\" "+
                "FROM hours h CROSS JOIN unnest(?::bigint[]) s(source_id) LEFT JOIN LATERAL (SELECT object_count,size_bytes FROM ingest_monitor_record x WHERE x.record_type='SAMPLE' AND x.source_id=s.source_id AND x.directory='' AND x.observed_at<=least(?,h.hour+interval '1 hour'-interval '1 microsecond') ORDER BY observed_at DESC,record_id DESC LIMIT 1) last ON true "+
                "CROSS JOIN LATERAL (SELECT coalesce(sum(added_objects),0) AS added_objects,coalesce(sum(added_bytes),0) AS added_bytes,coalesce(sum(changed_objects),0) AS changed_objects,coalesce(sum(changed_bytes),0) AS changed_bytes,coalesce(sum(removed_objects),0) AS removed_objects,coalesce(sum(removed_bytes),0) AS removed_bytes "+
                "FROM ingest_monitor_record x WHERE x.record_type='SAMPLE' AND x.source_id=s.source_id AND x.directory='' AND NOT is_baseline AND x.observed_at>=greatest(?,h.hour) AND x.observed_at<=? AND x.observed_at<h.hour+interval '1 hour') d GROUP BY h.hour ORDER BY h.hour";
        return publicRows(jdbc.queryForList(sql,ids,Timestamp.from(from),Timestamp.from(until),ids,Timestamp.from(until),Timestamp.from(from),Timestamp.from(until)));
    }

    public List<Map<String,Object>> changes(long sourceId,Instant from,int limit) {
        // 仅返回窗口中有新增数据的文件所属目录；删除、基线和未变目录不进入列表。
        return publicRows(jdbc.queryForList("SELECT directory,sum(added_objects) AS \"addedObjects\",sum(added_bytes) AS \"addedBytes\",sum(changed_objects) AS \"changedObjects\",sum(changed_bytes) AS \"changedBytes\",max(observed_at) AS \"observedAt\" "+
                "FROM ingest_monitor_record WHERE record_type='SAMPLE' AND source_id=ANY(?::bigint[]) AND directory<>'' AND NOT is_baseline AND observed_at>=? GROUP BY directory HAVING sum(added_objects+changed_objects)>0 ORDER BY max(observed_at) DESC,directory LIMIT ?",ids(sourceIds(sourceId)),Timestamp.from(from),limit));
    }

    static String ids(Collection<Long> values) {return values.stream().map(String::valueOf).collect(Collectors.joining(",","{","}"));}
    private static List<Map<String,Object>> publicRows(List<Map<String,Object>> rows) {
        rows.forEach(row -> row.replaceAll((key,value) -> value instanceof Timestamp time ? time.toInstant().toString() : value));return rows;
    }
    private static Instant instant(Timestamp value) {return value==null ? null : value.toInstant();}
    private static Timestamp timestamp(Instant value) {return value==null ? null : Timestamp.from(value);}
}
