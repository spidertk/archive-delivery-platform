package com.powerjob.archive.server.service.ingestmonitor;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 日报和进数节奏由同一策略推进，卡片入队与每个渠道的增量游标由调用方原子提交。 */
@Service
public class IngestReportService {
    private final MonitorNoticeStore notices;
    private final IngestMessageRenderer renderer;
    private final MonitorSettings settings;
    public IngestReportService(MonitorNoticeStore notices, IngestMessageRenderer renderer, MonitorSettings settings) {
        this.notices=notices;this.renderer=renderer;this.settings=settings;
    }

    public void report(Instant now, Set<Long> successful, boolean complete) {
        // 北京时间首个完整成功周期只推一张日报；未完成扫描的渠道不以旧快照冒充本轮事实。
        notices.advance(successful,now,settings.getContinuousPeriod());
        var channels=notices.channels().stream().filter(channel -> successful.containsAll(channel.sourceIds())).toList();
        if (channels.isEmpty()) return;
        var day=now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        var daily=notices.daily();
        if (complete && !day.equals(daily.date())) {
            var reports=channels.stream().map(channel -> notices.report(channel,daily.recordId())).toList();
            notices.enqueue("DAILY","daily:"+day,renderer.render("DAILY",reports,now),channels,now);
            return;
        }
        List<MonitorNoticeStore.Report> reports=new ArrayList<>();
        boolean starts=false;
        for (var channel : channels) {
            if (!channel.phase().equals("ACTIVE")) continue;
            boolean start=channel.lastReport()==null;
            if (!start && now.isBefore(channel.lastReport().plus(settings.getReportInterval()))) continue;
            var report=notices.report(channel,channel.recordId());
            if (report.incoming()) { reports.add(report);starts|=start; }
            else notices.markReported(channel,now);
        }
        if (reports.isEmpty()) return;
        String kind=starts ? "ACTIVE" : "HOURLY";
        notices.enqueue(kind,kind.toLowerCase()+":"+UUID.randomUUID(),renderer.render(kind,reports,now),
                reports.stream().map(MonitorNoticeStore.Report::channel).toList(),now);
        reports.forEach(report -> notices.markReported(report.channel(),now));
    }
}
