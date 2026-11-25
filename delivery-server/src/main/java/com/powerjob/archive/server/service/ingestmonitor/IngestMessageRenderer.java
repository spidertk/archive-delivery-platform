package com.powerjob.archive.server.service.ingestmonitor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** 群内保留紧凑原生卡片，点击进入自己的只读 IP 看板，详细渠道/目标桶/费用在页面展开。 */
@Component
public class IngestMessageRenderer {
    private final ObjectMapper mapper;
    private final MonitorSettings settings;
    private final MonitorDashboardService dashboard;
    public IngestMessageRenderer(ObjectMapper mapper,MonitorSettings settings,MonitorDashboardService dashboard) {
        this.mapper=mapper;this.settings=settings;this.dashboard=dashboard;
    }

    public String render(String kind,List<MonitorNoticeStore.Report> reports,Instant now) {
        // 使用与页面一致的今日口径；卡片保留最多四个渠道和三个真实变化目录，详情通过受控 URL 查看。
        settings.validateDashboard();
        boolean daily=kind.equals("DAILY");
        var metrics=dashboard.channels(now).stream().collect(Collectors.toMap(row->((Number)row.get("sourceId")).longValue(),row->row));
        var names=reports.stream().collect(Collectors.groupingBy(report->report.channel().name(),Collectors.counting()));
        long stored=reports.stream().mapToLong(report->report.channel().bytes()).sum();
        long incoming=reports.stream().mapToLong(MonitorNoticeStore.Report::incomingBytes).sum();
        long today=reports.stream().mapToLong(report->number(metrics.get(report.channel().id()),"incomingTodayBytes")).sum();
        StringBuilder body=new StringBuilder();
        for (var report : reports.stream().limit(4).toList()) {
            var channel=report.channel();
            String label=names.get(channel.name())>1?channel.bucket()+"/"+channel.name():channel.name();
            if (!body.isEmpty()) body.append("\n\n");
            body.append(shortText(label,40)).append("\n");
            body.append(daily?"存量 "+size(channel.bytes()):"本轮 +"+size(report.incomingBytes()));
            body.append(" · 今日 +").append(size(number(metrics.get(channel.id()),"incomingTodayBytes")));
        }
        if (reports.size()>4) body.append("\n另 ").append(reports.size()-4).append(" 个渠道，点击查看");
        // 即使本轮只播报部分进数渠道，总存储月费也覆盖全部监控渠道，与页面顶部口径一致。
        long totalStored=metrics.values().stream().mapToLong(row->number(row,"sizeBytes")).sum();
        body.append("\n\n总存储1个月预估 ¥").append(OssCostService.charge(totalStored).setScale(2,java.math.RoundingMode.HALF_UP));
        body.append("\n参考汇率 1 USD=").append(OssCostService.USD_CNY_RATE).append(" CNY（").append(OssCostService.EXCHANGE_DATE).append("）");
        Map<String,Object> card=new LinkedHashMap<>();
        card.put("card_type","text_notice");
        card.put("source",Map.of("desc","OSS 渠道监控","desc_color",1));
        card.put("main_title",Map.of("title",daily?"📦 渠道日报":kind.equals("ACTIVE")?"🟢 持续进数":"📈 小时新增",
                "desc",DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai")).format(now)+" · 今日 +"+size(today)));
        card.put("emphasis_content",Map.of("title",size(daily?stored:incoming),"desc",daily?"当前存量":"本轮进数"));
        card.put("sub_title_text",body.toString());
        var changed=new ArrayList<String>();
        for (int rank=0;rank<3 && changed.size()<3;rank++) {
            for (var report : reports) {
                if (changed.size()>=3 || rank>=report.changes().size()) continue;
                var item=report.changes().get(rank);
                changed.add(shortText(report.channel().name(),24)+" / "+shortText(item.directory(),42)+" +"+size(item.bytes()));
            }
        }
        if (!changed.isEmpty()) card.put("quote_area",Map.of("title","有新增数据的目录","quote_text",String.join("\n",changed)));
        card.put("card_action",Map.of("type",1,"url",settings.getDashboardUrl()));
        try {return mapper.writeValueAsString(Map.of("msgtype","template_card","template_card",card));}
        catch (JsonProcessingException failure) {throw new IllegalStateException("Cannot serialize monitor card");}
    }

    private static long number(Map<String,Object> row,String key) {return row==null?0:((Number)row.getOrDefault(key,0)).longValue();}
    static String size(long bytes) {
        if (bytes>0 && bytes<10_000_000L) return "<0.01 GB";
        return String.format(Locale.ROOT,"%.2f %s",bytes>=1_000_000_000_000L?bytes/1_000_000_000_000.0:bytes/1_000_000_000.0,bytes>=1_000_000_000_000L?"TB":"GB");
    }
    private static String shortText(String text,int max) {
        String safe=text.replaceAll("[\\p{Cntrl}<>]","_");
        int[] points=safe.codePoints().toArray();
        return points.length<=max?safe:new String(points,0,max/2)+"…"+new String(points,points.length-max/2,max/2);
    }
}
