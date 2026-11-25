package com.powerjob.archive.server.service.ingestmonitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IngestMessageRendererTest {
    final ObjectMapper mapper=new ObjectMapper();
    final MonitorSettings settings=new MonitorSettings();
    final MonitorDashboardService dashboard=mock(MonitorDashboardService.class);
    final IngestMessageRenderer renderer=new IngestMessageRenderer(mapper,settings,dashboard);
    final Instant now=Instant.parse("2026-11-22T00:00:00Z");
    IngestMessageRendererTest() {settings.setDashboardUrl("http://127.0.0.1:8090/monitor");}
    private MonitorNoticeStore.Channel channel(String name) {return new MonitorNoticeStore.Channel(1,"oss://bucket/"+name+"/",name,"bucket",List.of(1L),361506,6137079584256L,"IDLE",now,false,null,0);}

    @Test void nativeCardLinksToOwnMonitorAndShowsTodayWithStorageEstimate() throws Exception {
        // 卡片与看板采用同一今日统计，点击自己的 IP 页面，不再跳转云控制台。
        when(dashboard.channels(now)).thenReturn(List.of(Map.of("sourceId",1,"incomingTodayBytes",1_000_000_000L,"sizeBytes",6137079584256L)));
        var message=mapper.readTree(renderer.render("DAILY",List.of(new MonitorNoticeStore.Report(channel("incoming"),0,0,0,0,List.of())),now));
        assertEquals("template_card",message.path("msgtype").asText());
        var card=message.path("template_card");
        assertEquals(settings.getDashboardUrl(),card.path("card_action").path("url").asText());
        assertEquals("6.14 TB",card.path("emphasis_content").path("title").asText());
        assertTrue(card.path("sub_title_text").asText().contains("今日 +1.00 GB"));
        assertTrue(card.path("sub_title_text").asText().contains("总存储1个月预估 ¥648.84"));
        assertFalse(message.toString().contains("console.aliyun"));assertFalse(message.toString().contains("文件夹"));
    }

    @Test void sameFirstLevelInDifferentBucketsRemainsDistinguishable() throws Exception {
        // 一级目录重名时加桶名，避免两个渠道在群里无法区分。
        var left=channel("incoming");
        var right=new MonitorNoticeStore.Channel(2,"oss://other/incoming/","incoming","other",List.of(2L),1,10,"IDLE",now,false,null,0);
        String content=renderer.render("DAILY",List.of(new MonitorNoticeStore.Report(left,0,0,0,0,List.of()),new MonitorNoticeStore.Report(right,0,0,0,0,List.of())),now);
        assertTrue(content.contains("bucket/incoming"));assertTrue(content.contains("other/incoming"));
    }

    @Test void activityCardMonthlyTotalIncludesQuietChannels() throws Exception {
        // 主动通知只列进数渠道，但月费必须覆盖全部监控存量，不能把静默渠道漏算。
        when(dashboard.channels(now)).thenReturn(List.of(Map.of("sourceId",1,"sizeBytes",1_073_741_824L),Map.of("sourceId",2,"sizeBytes",2_147_483_648L)));
        var report=new MonitorNoticeStore.Report(channel("incoming"),1,1_073_741_824L,0,0,List.of());
        var content=mapper.readTree(renderer.render("ACTIVE",List.of(report),now)).path("template_card").path("sub_title_text").asText();
        assertTrue(content.contains("总存储1个月预估 ¥0.34"));
        assertTrue(content.contains("1 USD=6.6777 CNY（2026-11-20）"));
    }

    @Test void changesAreBoundedAndDoNotAddUnchangedDirectories() throws Exception {
        // 群卡最多三条真实变化目录；完整列表在只读看板，不在群里分段刷屏。
        var changes=IntStream.range(0,20).mapToObj(i->new MonitorNoticeStore.Change("temp/new/"+i+"/",1,1_000_000_000,20)).toList();
        var card=mapper.readTree(renderer.render("HOURLY",List.of(new MonitorNoticeStore.Report(channel("incoming"),20,20_000_000_000L,0,0,changes)),now)).path("template_card");
        assertEquals("20.00 GB",card.path("emphasis_content").path("title").asText());
        assertEquals(3,card.path("quote_area").path("quote_text").asText().split("\n").length);
        assertEquals("<0.01 GB",IngestMessageRenderer.size(1000));
    }
}
