package com.powerjob.archive.server.service.ingestmonitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class IngestMonitorService {
    private static final Logger log=LoggerFactory.getLogger(IngestMonitorService.class);
    private final MonitorStore store;
    private final OssSourceScanner scanner;
    private final WeComNotifier notifier;
    private final MonitorNoticeStore notices;
    private final IngestReportService reports;
    private final MonitorSettings settings;
    private final Clock clock;
    private final MonitorDashboardService dashboard;
    private boolean firstCycle=true;

    public IngestMonitorService(MonitorStore store, OssSourceScanner scanner, WeComNotifier notifier,
                                MonitorNoticeStore notices, IngestReportService reports, MonitorSettings settings, Clock clock, MonitorDashboardService dashboard) {
        // 清单观察与播报策略在同一模块协作，通用交付服务及 Worker 不承担监控职责。
        this.store=store;this.scanner=scanner;this.notifier=notifier;
        this.notices=notices;this.reports=reports;this.settings=settings;this.clock=clock;this.dashboard=dashboard;
    }

    @PostConstruct
    public void validate() { if (settings.isEnabled()) settings.validate(); }

    @Scheduled(fixedDelayString="${archive.ingest-monitor.poll-interval-ms:60000}",initialDelayString="${archive.ingest-monitor.initial-delay-ms:30000}")
    public void poll() {
        // 全局 PG 锁避免多副本重复日报；一个周期最多发送一张聚合卡片，失败清单不参与本轮报告。
        if (!settings.isEnabled()) return;
        try {
            store.exclusively(() -> {
                var sources=store.sources();
                Set<Long> successful=new HashSet<>();
                for (var source : sources) if (scan(source)) successful.add(source.id());
                store.transaction(() -> reports.report(clock.instant(),successful,successful.size()==sources.size()));
                flushNotification();
                firstCycle=false;
            });
        } catch (Exception failure) { log.warn("Ingest monitor cycle failed code=MONITOR_FAILED"); }
    }

    private boolean scan(SourceSnapshot.Source source) {
        // 完整 LIST 与观察事实在同一事务；失败只保存错误码，不能发布部分清单或延续连续进数判定。
        try {
            store.state(source.id());
            store.transaction(() -> {
                store.beginSnapshot();
                try { scanner.scan(source,store::stage); }
                catch (Exception failed) { throw new IllegalStateException("OSS inventory scan failed"); }
                apply(source,clock.instant());
            });
            return true;
        } catch (Exception failure) {
            store.failed(source.id(),clock.instant());
            log.warn("Ingest monitor scan failed sourceId={} code=SCAN_FAILED",source.id());
            return false;
        }
    }

    void apply(SourceSnapshot.Source source, Instant now) {
        // 首次存量仅建立基线；之后每次完整扫描有新增或覆盖才延续 30 分钟进数窗口。
        var old=store.state(source.id());
        String prefix=source.channelPrefix();
        var total=store.compare(source.id(),prefix);
        boolean baseline=old.observed()==null;
        boolean incoming=!baseline && total.addedObjects()+total.changedObjects()>0;
        boolean gap=old.failed()!=null || firstCycle && old.observed()!=null
                && Duration.between(old.observed(),now).toMillis()>settings.getPollIntervalMs()*3;
        if (gap) store.breakContinuity(source.id());
        var next=new MonitorStore.State(old.phase(),old.continuousSince(),incoming ? now : old.lastGrowth(),now,null,old.lastReport(),old.reportRecordId());
        store.save(source.id(),next,total,baseline);
    }

    private void flushNotification() {
        // 只有群端 HTTP 200 且 errcode=0 才确认；失败保留整张卡片并有界退避，不拆分重发刷屏。
        var pending=notices.next(clock.instant());
        if (pending.isEmpty()) return;
        var item=pending.get(0);
        try {
            notifier.send(item.payload());
            notices.sent(item.id(),clock.instant());
            log.info("Ingest card acknowledged notificationId={} httpStatus=200 businessCode=0",item.id());
        }
        catch (Exception failure) {
            notices.retry(item,clock.instant());
            int status=failure instanceof WeComNotifier.DeliveryFailure rejected ? rejected.httpStatus : 0;
            int code=failure instanceof WeComNotifier.DeliveryFailure rejected ? rejected.businessCode : 0;
            log.warn("Ingest card deferred notificationId={} code=WEBHOOK_FAILED type={} httpStatus={} businessCode={}",
                    item.id(),failure.getClass().getSimpleName(),status,code);
        }
    }

    public Map<String,Object> status() {
        // 公共只读页面仅拿到脱敏流量及预估单价；交付写接口和 Pipeline 凭据不进入此投影。
        var result=new java.util.LinkedHashMap<>(dashboard.overview(clock.instant()));
        result.put("enabled",settings.isEnabled());result.put("pollIntervalSeconds",settings.getPollIntervalMs()/1000);
        result.put("continuousPeriodSeconds",settings.getContinuousPeriod().toSeconds());result.put("reportIntervalSeconds",settings.getReportInterval().toSeconds());
        result.put("dailyTimezone","Asia/Shanghai");return result;
    }

    public List<Map<String,Object>> trend(long sourceId, int hours) {
        if (sourceId<1 || hours<1 || hours>720) throw new IllegalArgumentException("sourceId must be positive and hours must be 1..720");
        Instant now=clock.instant();
        return store.trend(sourceId,now.minus(Duration.ofHours(hours)),now);
    }

    public List<Map<String,Object>> changes(long sourceId, int hours, int limit) {
        if (sourceId<1 || hours<1 || hours>720 || limit<1 || limit>200) throw new IllegalArgumentException("Invalid directory query");
        return store.changes(sourceId,clock.instant().minus(Duration.ofHours(hours)),limit);
    }
}
