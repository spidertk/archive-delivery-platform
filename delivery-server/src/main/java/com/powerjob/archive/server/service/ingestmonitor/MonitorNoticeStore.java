package com.powerjob.archive.server.service.ingestmonitor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** 两表模型：窗口复用来源行，日报游标和渠道快照直接保存在通知记录。 */
@Repository
public class MonitorNoticeStore {
    private final JdbcTemplate jdbc;
    private final MonitorStore store;
    public record Channel(long id,String key,String name,String bucket,List<Long> sourceIds,long files,long bytes,String phase,
                          Instant observed,boolean growing,Instant lastReport,long recordId) {}
    public record Change(String directory,long files,long bytes,long totalDirectories) {}
    public record Report(Channel channel,long addedFiles,long addedBytes,long changedFiles,long changedBytes,List<Change> changes) {
        public long incomingBytes() {return addedBytes+changedBytes;}
        public boolean incoming() {return addedFiles+changedFiles>0;}
    }
    public record Daily(LocalDate date,long recordId) {}
    public record Pending(long id,String payload,int attempts) {}
    public MonitorNoticeStore(JdbcTemplate jdbc,MonitorStore store) {this.jdbc=jdbc;this.store=store;}

    public List<Channel> channels() {
        // 按桶/第一级目录合并范围和当前总量；同名不同桶仍是不同渠道。
        List<Channel> result=new ArrayList<>();
        for (var scope : store.scopes()) {
            var state=store.state(scope.id());
            var totals=jdbc.queryForMap("SELECT sum(monitor_object_count) AS files,sum(monitor_size_bytes) AS bytes,"+
                    "CASE WHEN count(monitor_observed_at)=count(*) THEN min(monitor_observed_at) END AS observed,"+
                    "coalesce(bool_or(monitor_last_growth_at=monitor_observed_at),false) AS growing FROM ingest_source WHERE source_id=ANY(?::bigint[])",MonitorStore.ids(scope.sourceIds()));
            Instant observed=instant((Timestamp)totals.get("observed"));
            if (observed!=null) result.add(new Channel(scope.id(),scope.key(),scope.name(),scope.bucket(),scope.sourceIds(),
                    ((Number)totals.get("files")).longValue(),((Number)totals.get("bytes")).longValue(),state.phase(),observed,
                    (boolean)totals.get("growing"),state.lastReport(),state.reportRecordId()));
        }
        return result;
    }

    public void advance(Set<Long> successful,Instant now,Duration continuousPeriod) {
        // 连续进数按逻辑渠道计算，允许同一级目录下不同范围轮流进数；有范围失败则本轮不延续窗口。
        for (var channel : channels()) {
            if (!successful.containsAll(channel.sourceIds()) || channel.phase().equals("ACTIVE")) continue;
            var state=store.state(channel.id());
            if (!channel.growing()) {
                jdbc.update("UPDATE ingest_source SET monitor_phase='IDLE',monitor_continuous_since=NULL WHERE source_id=?",channel.id());
            } else if (!channel.phase().equals("OBSERVING")) {
                jdbc.update("UPDATE ingest_source SET monitor_phase='OBSERVING',monitor_continuous_since=?,monitor_report_record_id=coalesce(("+
                        "SELECT min(s.record_id)-1 FROM ingest_monitor_record s JOIN ingest_source i USING(source_id) WHERE s.record_type='SAMPLE' AND s.source_id=ANY(?::bigint[]) AND s.observed_at=i.monitor_observed_at),0) WHERE source_id=?",
                        Timestamp.from(now),MonitorStore.ids(channel.sourceIds()),channel.id());
            } else if (state.continuousSince()!=null && !now.isBefore(state.continuousSince().plus(continuousPeriod))) {
                jdbc.update("UPDATE ingest_source SET monitor_phase='ACTIVE' WHERE source_id=?",channel.id());
            }
        }
    }

    public Report report(Channel channel,long after) {
        // 仅按采样游标汇总新增数据；已有目录继续进数与新目录同样展示，不读取历史目录树。
        String ids=MonitorStore.ids(channel.sourceIds());
        long[] sums=jdbc.queryForObject("SELECT coalesce(sum(added_objects),0),coalesce(sum(added_bytes),0),coalesce(sum(changed_objects),0),coalesce(sum(changed_bytes),0) "+
                "FROM ingest_monitor_record WHERE record_type='SAMPLE' AND source_id=ANY(?::bigint[]) AND record_id>? AND directory='' AND NOT is_baseline",
                (rs,row)->new long[]{rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4)},ids,after);
        var changes=jdbc.query("SELECT directory,sum(added_objects+changed_objects) AS files,sum(added_bytes+changed_bytes) AS bytes,count(*) OVER() AS directories "+
                "FROM ingest_monitor_record WHERE record_type='SAMPLE' AND source_id=ANY(?::bigint[]) AND record_id>? AND directory<>'' AND NOT is_baseline "+
                "GROUP BY directory HAVING sum(added_objects+changed_objects)>0 ORDER BY sum(added_bytes+changed_bytes) DESC,directory LIMIT 7",
                (rs,row)->new Change(rs.getString(1),rs.getLong(2),rs.getLong(3),rs.getLong(4)),ids,after);
        return new Report(channel,sums[0],sums[1],sums[2],sums[3],changes);
    }

    public Daily daily() {
        // 日报日期和覆盖的采样 ID 由通知事实给出；格式升级前的历史游标仅用于防止旧增量重复播报。
        var daily=jdbc.query("SELECT report_day,record_watermark FROM ingest_monitor_notification WHERE event_type='DAILY' ORDER BY report_day DESC,notification_id DESC LIMIT 1",
                (rs,row)->new Daily(rs.getObject(1,LocalDate.class),rs.getLong(2)));
        return daily.isEmpty() ? new Daily(null,jdbc.queryForObject("SELECT coalesce(max(record_watermark),0) FROM ingest_monitor_notification",Long.class)) : daily.get(0);
    }

    public void markReported(Channel channel,Instant now) {
        // 先将未报告尾量与窗口游标同事务入队，再根据本轮是否进数决定保留或关闭小时窗口。
        jdbc.update("UPDATE ingest_source SET monitor_phase=?,monitor_last_report_at=?,monitor_continuous_since=CASE WHEN ? THEN monitor_continuous_since ELSE NULL END,"+
                "monitor_report_record_id=(SELECT coalesce(max(record_id),0) FROM ingest_monitor_record WHERE record_type='SAMPLE' AND source_id=ANY(?::bigint[])) WHERE source_id=?",
                channel.growing()?"ACTIVE":"IDLE",channel.growing()?Timestamp.from(now):null,channel.growing(),MonitorStore.ids(channel.sourceIds()),channel.id());
    }

    public void enqueue(String kind,String key,String payload,List<Channel> channels,Instant now) {
        // 一个逻辑通知保存来源 ID 数组及日报游标；不再有独立游标表或消息渠道关系表。
        List<Long> ids=channels.stream().flatMap(channel->channel.sourceIds().stream()).distinct().sorted().toList();
        jdbc.update("INSERT INTO ingest_monitor_notification(event_type,report_key,payload,source_ids,report_day,record_watermark,created_at,next_attempt_at) "+
                "VALUES(?,?,?::jsonb,?::bigint[],?,(SELECT coalesce(max(record_id),0) FROM ingest_monitor_record WHERE record_type='SAMPLE'),?,?)",
                kind,key,payload,MonitorStore.ids(ids),kind.equals("DAILY")?now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate():null,Timestamp.from(now),Timestamp.from(now));
    }
    public List<Pending> next(Instant now) {
        return jdbc.query("SELECT notification_id,payload::text,attempts FROM ingest_monitor_notification WHERE sent_at IS NULL AND cancelled_at IS NULL AND event_type<>'HISTORY' AND next_attempt_at<=? ORDER BY notification_id LIMIT 1",
                (rs,row)->new Pending(rs.getLong(1),rs.getString(2),rs.getInt(3)),Timestamp.from(now));
    }
    public void sent(long id,Instant now) {jdbc.update("UPDATE ingest_monitor_notification SET sent_at=? WHERE notification_id=?",Timestamp.from(now),id);}
    public void retry(Pending item,Instant now) {
        long seconds=Math.min(3600,60L<<Math.min(item.attempts(),6));
        jdbc.update("UPDATE ingest_monitor_notification SET attempts=attempts+1,next_attempt_at=? WHERE notification_id=?",Timestamp.from(now.plusSeconds(seconds)),item.id());
    }
    private static Instant instant(Timestamp value) {return value==null?null:value.toInstant();}
}
