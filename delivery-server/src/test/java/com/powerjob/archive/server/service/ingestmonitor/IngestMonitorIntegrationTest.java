package com.powerjob.archive.server.service.ingestmonitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker=true)
class IngestMonitorIntegrationTest {
    static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("archive_delivery").withUsername("archive").withPassword("archive_local");
    static HikariDataSource dataSource;
    static JdbcTemplate jdbc;
    static MonitorStore store;
    static MonitorNoticeStore notices;
    static final Instant BASE=Instant.parse("2026-11-22T00:00:00Z");
    final ObjectMapper mapper=new ObjectMapper();
    final MonitorSettings settings=new MonitorSettings();
    IngestReportService reports;
    MonitorDashboardService dashboard;
    IngestMonitorService service;
    SourceSnapshot.Source source;
    String bucket;

    @BeforeAll static void migrate() {
        // 用真实 V27 对象、目录缓存、两种通知历史验证 7→3→2 表迁移，确认字段和 ACK 不丢失。
        postgres.start();dataSource=new HikariDataSource();
        dataSource.setJdbcUrl(postgres.getJdbcUrl());dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());dataSource.setSchema("archive_delivery");
        Flyway.configure().dataSource(dataSource).schemas("archive_delivery").target("27").load().migrate();
        jdbc=new JdbcTemplate(dataSource);
        long existing=jdbc.queryForObject("SELECT min(source_id) FROM ingest_source",Long.class);
        String uri=jdbc.queryForObject("SELECT oss_uri FROM ingest_source WHERE source_id=?",String.class,existing);
        String prefix=java.net.URI.create(uri).getPath().substring(1);
        jdbc.update("INSERT INTO ingest_monitor_state(source_id,observed_at,object_count,size_bytes) VALUES(?,?,2,30)",existing,java.sql.Timestamp.from(BASE));
        jdbc.update("INSERT INTO ingest_monitor_object(source_id,object_key,size_bytes,etag,modified_at) VALUES(?,?,10,'a',?),(?,?,20,'b',?)",
                existing,prefix+"existing/a/one",java.sql.Timestamp.from(BASE),existing,prefix+"existing/b/two",java.sql.Timestamp.from(BASE));
        jdbc.update("INSERT INTO ingest_monitor_sample(source_id,observed_at,directory,object_count,size_bytes,added_objects,added_bytes,changed_objects,changed_bytes,removed_objects,removed_bytes,is_baseline) VALUES(?,?,'',2,30,2,30,0,0,0,0,true)",existing,java.sql.Timestamp.from(BASE));
        String payload="{\"msgtype\":\"template_card\",\"template_card\":{\"card_type\":\"text_notice\",\"source\":{\"desc\":\"history\",\"desc_color\":1},\"main_title\":{\"title\":\"history\",\"desc\":\"\"},\"emphasis_content\":{\"title\":\"0 GB\",\"desc\":\"total\"},\"card_action\":{\"type\":1,\"url\":\"https://example.com\"}}}";
        for (String kind : List.of("DAILY","ACTIVE")) {
            long id=jdbc.queryForObject("INSERT INTO ingest_monitor_notification(event_type,report_key,payload,created_at,next_attempt_at,sent_at) VALUES(?,?,?::jsonb,?,?,?) RETURNING notification_id",Long.class,kind,
                    kind.equals("DAILY")?"daily:2026-11-22":"active:history",payload,java.sql.Timestamp.from(BASE),java.sql.Timestamp.from(BASE),kind.equals("DAILY")?java.sql.Timestamp.from(BASE):null);
            jdbc.update("INSERT INTO ingest_monitor_notification_channel VALUES(?,?)",id,existing);
        }
        jdbc.update("UPDATE ingest_monitor_broadcast SET last_daily_date='2026-11-22',last_daily_sample_id=(SELECT max(sample_id) FROM ingest_monitor_sample)");
        assertEquals(1,Flyway.configure().dataSource(dataSource).schemas("archive_delivery").target("28").load().migrate().migrationsExecuted);
        String markdown="{\"msgtype\":\"markdown\",\"markdown\":{\"content\":\"preserved V28 summary\"}}";
        jdbc.update("INSERT INTO ingest_monitor_notification(event_type,report_key,payload,source_ids,report_day,record_watermark,created_at,next_attempt_at,sent_at) VALUES('DAILY','daily:2026-11-23',?::jsonb,?::bigint[],'2026-11-23',0,?,?,?)".replace("record_watermark","sample_watermark"),markdown,MonitorStore.ids(List.of(existing)),java.sql.Timestamp.from(BASE),java.sql.Timestamp.from(BASE),java.sql.Timestamp.from(BASE));
        var flyway=Flyway.configure().dataSource(dataSource).schemas("archive_delivery").load();
        assertEquals(2,flyway.migrate().migrationsExecuted);assertEquals(0,flyway.migrate().migrationsExecuted);
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='archive_delivery' AND table_name LIKE 'ingest_monitor_%'",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT monitor_object_count FROM ingest_source WHERE source_id=?",Integer.class,existing));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM ingest_monitor_notification WHERE event_type='HISTORY' AND sent_at IS NOT NULL AND cardinality(source_ids)=1",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ingest_monitor_notification WHERE event_type='HISTORY' AND sent_at IS NULL AND cancelled_at IS NOT NULL",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM ingest_monitor_record WHERE record_type='OBJECT'",Integer.class));
        assertEquals(30,jdbc.queryForObject("SELECT sum(size_bytes) FROM ingest_monitor_record WHERE record_type='OBJECT'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ingest_monitor_record WHERE record_type='SAMPLE' AND is_baseline",Integer.class));
        assertEquals(markdown,jdbc.queryForObject("SELECT payload::text FROM ingest_monitor_notification WHERE payload->'markdown'->>'content'='preserved V28 summary'",String.class).replace(": ",":").replace(", ",","));
        store=new MonitorStore(jdbc,new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        notices=new MonitorNoticeStore(jdbc,store);
    }
    @AfterAll static void close() { dataSource.close();postgres.stop(); }

    @BeforeEach void setup() {
        // 用独立来源隔离窗口与卡片统计；每日游标重置只作用于临时测试数据库。
        jdbc.update("DELETE FROM ingest_monitor_notification WHERE event_type<>'HISTORY'");
        bucket="bucket-"+UUID.randomUUID();
        source=createSource("TEST");
        settings.setDashboardUrl("http://127.0.0.1:8090/monitor");
        dashboard=new MonitorDashboardService(jdbc,store,new OssCostService());
        reports=new IngestReportService(notices,new IngestMessageRenderer(mapper,settings,dashboard),settings);
        service=service(Clock.fixed(BASE,ZoneOffset.UTC),mock(WeComNotifier.class));
    }

    @Test void baselineAndDailyUseOneCompactMessageWithoutHistoricalFolders() throws Exception {
        // 祖先目录各计一次，渠道文件和字节不重复累加；日报跨渠道只创建一条发送记录。
        var second=createSource("SECOND");
        observe(source,0,Map.of("a/b/one",10L,"a/c/two",20L,"root",0L));
        observe(second,0,Map.of("x/one",30L));
        finish(BASE,source,second);finish(BASE.plusSeconds(60),source,second);
        assertEquals(1,count("DAILY"));assertEquals(0,count("ACTIVE"));
        var snapshot=store.status().stream().filter(row -> ((Number)row.get("sourceId")).longValue()==source.id()).findFirst().orElseThrow();
        assertFalse(snapshot.containsKey("folderCount"));
        assertTrue(store.changes(source.id(),BASE,200).isEmpty());
        assertEquals(3,((Number)snapshot.get("objectCount")).intValue());
        var payload=mapper.readTree(payload("DAILY"));
        assertEquals("template_card",payload.path("msgtype").asText());
        assertTrue(payload.toString().contains("SECOND"));
        assertEquals(2,jdbc.queryForObject("SELECT cardinality(source_ids) FROM ingest_monitor_notification ORDER BY notification_id DESC LIMIT 1",Integer.class));
        var trend=store.trend(source.id(),BASE,BASE.plusSeconds(60)).get(0);
        assertEquals(0,((Number)trend.get("addedObjects")).intValue());
    }

    @Test void waitsThirtyContinuousMinutesThenReportsOnlyUnreportedHourlyGrowth() throws Exception {
        // 31 次到数覆盖完整 30 分钟，随后 60 分钟只报窗口增量，不重复报原始存量或未变化目录。
        Map<String,Long> files=new HashMap<>(Map.of("unchanged/base",100L));
        observe(source,0,files);finish(BASE,source);
        for (int minute=1;minute<=30;minute++) { files.put("incoming/file"+minute,1_000_000_000L);observe(source,minute,files);finish(at(minute),source); }
        assertEquals(0,count("ACTIVE"));
        files.put("incoming/file31",1_000_000_000L);observe(source,31,files);finish(at(31),source);
        assertEquals(1,count("ACTIVE"));
        String first=payload("ACTIVE");assertTrue(first.contains("31.00 GB"));assertFalse(first.contains("unchanged/"));
        assertTrue(first.contains("incoming/"));assertFalse(first.contains("oss://"));
        for (int minute=32;minute<=90;minute++) { files.put("incoming/file"+minute,1_000_000_000L);observe(source,minute,files);finish(at(minute),source); }
        assertEquals(0,count("HOURLY"));
        files.put("incoming/file91",1_000_000_000L);observe(source,91,files);finish(at(91),source);
        assertEquals(1,count("HOURLY"));
        String hour=payload("HOURLY");assertTrue(hour.contains("60.00 GB"));assertTrue(hour.contains("91.00 GB"));
        finish(at(91),source);assertEquals(1,count("HOURLY"));
    }

    @Test void quietScanAndFailureRestartContinuousObservation() {
        // 缺进数或失败不能凑满 30 分钟；恢复后的连续成功到数重新计时。
        Map<String,Long> files=new HashMap<>();observe(source,0,files);finish(BASE,source);
        for (int minute=1;minute<=20;minute++) { files.put("a/"+minute,10L);observe(source,minute,files);finish(at(minute),source); }
        observe(source,21,files);finish(at(21),source);
        assertEquals("IDLE",store.state(source.id()).phase());
        files.put("a/22",10L);observe(source,22,files);store.failed(source.id(),at(23));
        for (int minute=24;minute<=53;minute++) { files.put("a/"+minute,10L);observe(source,minute,files);finish(at(minute),source); }
        assertEquals(0,count("ACTIVE"));
        files.put("a/54",10L);observe(source,54,files);finish(at(54),source);assertEquals(1,count("ACTIVE"));
    }

    @Test void simultaneousChannelActivityIsOneAggregateCardAndRetryPersists() {
        // 两个渠道同时满足条件只创建一张主动卡片；失败退避与重新打开 Store 都保留同一消息。
        var second=createSource("SECOND");
        Map<String,Long> files=new HashMap<>();observe(source,0,files);observe(second,0,files);finish(BASE,source,second);
        for (int minute=1;minute<=31;minute++) {
            files.put("arrivals/"+minute,1_000_000_000L);
            observe(source,minute,files);observe(second,minute,files);finish(at(minute),source,second);
        }
        assertEquals(1,count("ACTIVE"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ingest_monitor_notification WHERE event_type='ACTIVE'",Integer.class));
        String active=payload("ACTIVE");assertTrue(active.contains("TEST"));assertTrue(active.contains("SECOND"));
        var item=notices.next(at(32)).get(0);notices.retry(item,at(32));
        var restarted=new MonitorNoticeStore(jdbc,store);
        assertTrue(restarted.next(at(32).plusSeconds(59)).stream().noneMatch(next -> next.id()==item.id()));
        assertEquals(item.id(),restarted.next(at(33)).get(0).id());
    }

    @Test void pendingTailIsReportedOnceButQuietHoursDoNotSendEmptyCards() {
        // 已触发主动通知后的尾部增量在小时到期报告一次，此后静默不会每小时发空消息。
        Map<String,Long> files=new HashMap<>();observe(source,0,files);finish(BASE,source);
        for (int minute=1;minute<=32;minute++) { files.put("a/"+minute,10L);observe(source,minute,files);finish(at(minute),source); }
        observe(source,91,files);finish(at(91),source);
        assertEquals(1,count("ACTIVE"));assertEquals(1,count("HOURLY"));
        assertEquals("IDLE",store.state(source.id()).phase());
        observe(source,151,files);finish(at(151),source);assertEquals(1,count("HOURLY"));
    }

    @Test void beijingCalendarDayAndRestartDoNotDuplicateDailyCard() {
        // UTC 16 点即北京时间次日；重建服务对象不清空持久化日报游标。
        observe(source,0,Map.of());finish(BASE,source);
        var before=Instant.parse("2026-11-22T15:59:00Z");finish(before,source);assertEquals(1,count("DAILY"));
        settings.setDashboardUrl("http://127.0.0.1:8090/monitor");
        dashboard=new MonitorDashboardService(jdbc,store,new OssCostService());
        reports=new IngestReportService(notices,new IngestMessageRenderer(mapper,settings,dashboard),settings);
        finish(Instant.parse("2026-11-22T16:01:00Z"),source);assertEquals(2,count("DAILY"));
        finish(Instant.parse("2026-11-22T16:02:00Z"),source);assertEquals(2,count("DAILY"));
    }

    @Test void reportFailureRollsBackCardAndWatermark() {
        // 卡片和游标必须原子提交，避免发送未入队却丢失增量。
        observe(source,0,Map.of("a/b/file",10L));
        assertThrows(IllegalStateException.class,() -> store.transaction(() -> { reports.report(BASE,Set.of(source.id()),true);throw new IllegalStateException("rollback"); }));
        assertEquals(0,count("DAILY"));assertNull(notices.daily().date());finish(BASE,source);assertEquals(1,count("DAILY"));
    }

    @Test void overwriteDeletionUnicodeAndFolderRemovalRemainAccurate() {
        // 同大小覆盖不混为新增；目录按 Unicode 字符截取，删除后清除空目录而不触发进数通知。
        observe(source,0,Map.of("中文/😀/a",10L,"中文/other/b",20L));finish(BASE,source);
        var items=List.of(new SourceSnapshot.Item(prefix(source)+"中文/😀/a",10,"changed",BASE));
        store.transaction(() -> {store.beginSnapshot();store.stage(items);service.apply(source,at(1));});
        var trend=store.trend(source.id(),BASE,at(2)).get(0);
        assertEquals(1,((Number)trend.get("changedObjects")).intValue());
        assertEquals(1,((Number)trend.get("removedObjects")).intValue());
        assertEquals(1,store.changes(source.id(),BASE,200).size());
        observe(source,2,Map.of());assertEquals(1,store.changes(source.id(),BASE,200).size());assertEquals(0,count("ACTIVE"));
    }

    @Test void failedSnapshotRollsBackObjectsFoldersAndSamples() {
        // 半份清单失败不能造成对象被判删除或文件夹丢失。
        observe(source,0,Map.of("a/b/file",10L));
        assertThrows(IllegalStateException.class,() -> store.transaction(() -> {store.beginSnapshot();store.stage(List.of());service.apply(source,at(1));throw new IllegalStateException("rollback");}));
        assertTrue(store.changes(source.id(),BASE,20).isEmpty());
        assertEquals(BASE,store.state(source.id()).observed());
    }

    @Test void nativePayloadRejectsUnknownFieldsAndHistoryCannotBeDelivered() {
        // 数据库对白名单作最后边界；历史消息只保留事实，队列不能重新发送旧格式。
        String bad="{\"msgtype\":\"markdown\",\"accessKeySecret\":\"not-allowed\",\"markdown\":{\"content\":\"bad\"}}";
        assertThrows(Exception.class,() -> store.transaction(() -> notices.enqueue("DAILY","bad:"+source.id(),bad,List.of(),BASE)));
        assertTrue(notices.next(BASE).stream().noneMatch(item -> item.payload().contains("history report")));
    }

    @Test void firstLevelIdentityMergesDisjointScopesAndContinuousIncoming() {
        // 同桶一级 internal 的两个范围轮流进数，也应共用 30 分钟窗口并只出现一个渠道。
        source=createScope("internal/first/");var second=createScope("internal/second/");
        Map<String,Long> left=new HashMap<>(),right=new HashMap<>();
        observe(source,0,left);observe(second,0,right);finish(BASE,source,second);
        for (int minute=1;minute<=31;minute++) {
            if (minute%2==0) left.put("folder/"+minute,10L);else right.put("folder/"+minute,10L);
            observe(source,minute,left);observe(second,minute,right);finish(at(minute),source,second);
        }
        var channels=store.status().stream().filter(row->row.get("channelKey").equals("oss://"+bucket+"/internal/")).toList();
        assertEquals(1,channels.size());assertEquals("internal",channels.get(0).get("channelCode"));
        assertEquals(31,((Number)channels.get(0).get("objectCount")).intValue());assertEquals(1,count("ACTIVE"));
        String message=payload("ACTIVE");assertTrue(message.contains("internal"));assertTrue(message.contains("first/folder/"));assertTrue(message.contains("second/folder/"));
        assertFalse(message.contains("ARCHIVE_SOURCE"));
    }

    @Test void overlappingCoverageDoesNotDoubleCountAndDifferentBucketsDoNotMerge() {
        // 父范围覆盖子范围时只扫描父范围；另一个桶相同一级目录依然是独立渠道。
        var child=createScope("covered/child/");var parent=createScope("covered/");
        assertTrue(store.sources().stream().anyMatch(item->item.id()==parent.id()));
        assertFalse(store.sources().stream().anyMatch(item->item.id()==child.id()));
        observe(parent,0,Map.of("child/file",10L));
        String otherUri="oss://other-"+UUID.randomUUID()+"/covered/";
        long otherId=jdbc.queryForObject("INSERT INTO ingest_source(oss_uri,channel_code,oss_type) VALUES(?,'legacy','ALIBABA_CLOUD') RETURNING source_id",Long.class,otherUri);
        var otherBucket=new SourceSnapshot.Source(otherId,otherUri);observe(otherBucket,0,Map.of("file",20L));
        assertEquals(2,store.status().stream().filter(row->row.get("channelCode").equals("covered")).count());
        assertEquals(10,((Number)store.status().stream().filter(row->row.get("channelKey").equals("oss://"+bucket+"/covered/")).findFirst().orElseThrow().get("sizeBytes")).intValue());
    }

    @Test void unifiedRecordsKeepBaselineOutOfTodayAndFinancialEstimate() {
        // 同表中的对象、目录和总计不能重复求和；北京时间跨日保留累计进数并清零今日。
        observe(source,0,Map.of("old/file",1_073_741_824L));
        observe(source,1,Map.of("old/file",1_073_741_824L,"new/file",2_147_483_648L));
        var current=dashboard.channels(at(2)).stream().filter(row->row.get("channelKey").equals(source.key())).findFirst().orElseThrow();
        assertEquals(2_147_483_648L,((Number)current.get("incomingTotalBytes")).longValue());
        assertEquals(2_147_483_648L,((Number)current.get("incomingTodayBytes")).longValue());
        var next=dashboard.channels(BASE.plusSeconds(86400)).stream().filter(row->row.get("channelKey").equals(source.key())).findFirst().orElseThrow();
        assertEquals(0,((Number)next.get("incomingTodayBytes")).longValue());
        assertEquals(2_147_483_648L,((Number)next.get("incomingTotalBytes")).longValue());
        assertEquals(new java.math.BigDecimal("0.340563"),((Map<?,?>)current.get("costEstimate")).get("monthlyStorage"));
        assertThrows(Exception.class,()->store.transaction(()->jdbc.update("INSERT INTO ingest_monitor_record(source_id,size_bytes,record_type,object_key) VALUES(?,1,'OBJECT','invalid')",source.id())));
    }

    @Test void destinationTotalsDeduplicateRetriesAndUseFirstSuccessfulDay() {
        // 仅临时测试库构造成功上传事实；同 URI 重试不移到今天，失败节点不计入目标桶总量。
        observe(source,0,Map.of());
        long batch=jdbc.queryForObject("INSERT INTO delivery_submit(delivery_batch,source_id,pipeline_version_id,delivery_date,cutoff_at,submitted_at,target_strategy,submission_type) VALUES(?,?,2,'2026-11-22',now()+interval '1 day',NULL,'{\"schemaVersion\":1,\"targets\":[]}','BATCH_CREATE') RETURNING delivery_id",Long.class,"TEST-"+UUID.randomUUID(),source.id());
        long entity=jdbc.queryForObject("INSERT INTO delivery_entity(vendor_code,entity_code,entity_name,protocol,account_name,worker_group,endpoint_locator) VALUES(?,'test','test','FTP','test','group1','{\"host\":\"localhost\",\"rootPath\":\"/\"}') RETURNING delivery_entity_id",Long.class,"TEST-"+UUID.randomUUID());
        String sha=UUID.randomUUID().toString().replace("-","").repeat(2);
        String artifact="{\"artifactSeq\":1,\"originalFileName\":\"test.tar.gz\",\"newFileName\":\"test.tar.gz\",\"sizeBytes\":1,\"sha256\":\""+sha+"\",\"token\":1,\"file_mapping\":{}}";
        long data=jdbc.queryForObject("INSERT INTO source_data(delivery_id,original_file_name,original_locator,size_bytes,token_count,sha256,unique_key,artifact) VALUES(?,'test.tar.gz','{\"sourceUri\":\"oss://test/file\",\"sourceVersion\":\"1\"}',1,1,?,?,?::jsonb) RETURNING data_id",Long.class,batch,sha,sha,artifact);
        jdbc.update("INSERT INTO delivery_batch(batch_delivery_id,data_id,delivery_batch,original_file_name,new_file_name,source_seq,delivery_entity_id,entity_code,worker_group,delivery_id) SELECT delivery_id,?,delivery_batch,'test.tar.gz','test.tar.gz',1,?,'test','group1',delivery_id FROM delivery_submit WHERE delivery_id=?",data,entity,batch);
        String artifacts="[{\"dataId\":"+data+",\"sourceArtifact\":"+artifact+"}]";
        long task=jdbc.queryForObject("INSERT INTO delivery_task(delivery_id,delivery_entity_id,entity_code,worker_group,delivery_date,cutoff_at,new_file_name,ftp_file_name,current_node_id,artifacts) VALUES(?,?,'test','group1','2026-11-22',now()+interval '1 day','test.tar.gz','test.tar.gz','targetUpload',?::jsonb) RETURNING task_id",Long.class,batch,entity,artifacts);
        String payload="{\"uri\":\"oss://target-bucket/test.tar.gz\",\"sizeBytes\":1073741824}";
        for (int attempt=1;attempt<=3;attempt++) jdbc.update("INSERT INTO delivery_node(delivery_id,task_id,original_file_name,new_file_name,node_id,node_type,stage_code,attempt,fence,status,output_locator,finished_at) VALUES(?,?,'test.tar.gz','test.tar.gz','targetUpload','oss.upload','targetUpload',?,1,'SUCCEEDED',?::jsonb,?)",batch,task,attempt,payload,java.sql.Timestamp.from(attempt==1?BASE.minusSeconds(36000):BASE));
        jdbc.update("INSERT INTO delivery_node(delivery_id,task_id,original_file_name,new_file_name,node_id,node_type,stage_code,attempt,fence,status,output_locator,finished_at) VALUES(?,?,'test.tar.gz','test.tar.gz','failed','oss.upload','targetUpload',1,1,'FAILED','{\"uri\":\"oss://target-bucket/failed.tar.gz\",\"sizeBytes\":999}',?)",batch,task,java.sql.Timestamp.from(BASE));
        var channel=dashboard.channels(at(1)).stream().filter(row->row.get("channelKey").equals(source.key())).findFirst().orElseThrow();
        assertEquals(1_073_741_824L,((Number)channel.get("deliveryTotalBytes")).longValue());
        assertEquals(0,((Number)channel.get("deliveryTodayBytes")).longValue());
        @SuppressWarnings("unchecked") var targets=(List<Map<String,Object>>)channel.get("destinations");
        assertEquals(1,targets.size());assertEquals("target-bucket",targets.get(0).get("bucket"));assertEquals(1,((Number)targets.get(0).get("files")).intValue());
    }

    private SourceSnapshot.Source createScope(String path) {
        String uri="oss://"+bucket+"/"+path;
        long id=new com.powerjob.archive.core.service.IngestSourceService(jdbc).resolve(uri);
        return new SourceSnapshot.Source(id,uri);
    }

    @Test void onlyOneReplicaRunsAndAdvisoryLockIsReleased() throws Exception {
        // 两条真实 PG 会话验证互斥，执行完成后下一实例必须能接手。
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
        try {
            var running=executor.submit(() -> store.exclusively(() -> {entered.countDown();try {assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}}));
            assertTrue(entered.await(5,TimeUnit.SECONDS));var duplicate=new AtomicBoolean();store.exclusively(() -> duplicate.set(true));assertFalse(duplicate.get());
            release.countDown();running.get(5,TimeUnit.SECONDS);store.exclusively(() -> duplicate.set(true));assertTrue(duplicate.get());
        } finally {release.countDown();executor.shutdownNow();}
    }

    private SourceSnapshot.Source createSource(String name) {
        String uri="oss://"+bucket+"/"+name+"/";
        long id=jdbc.queryForObject("INSERT INTO ingest_source(oss_uri,channel_code,oss_type) VALUES(?,?,'ALIBABA_CLOUD') RETURNING source_id",Long.class,uri,name);
        return new SourceSnapshot.Source(id,uri);
    }
    private IngestMonitorService service(Clock clock,WeComNotifier notifier) { return new IngestMonitorService(store,mock(OssSourceScanner.class),notifier,notices,reports,settings,clock,dashboard); }
    private void observe(SourceSnapshot.Source selected,int minute,Map<String,Long> files) {
        List<SourceSnapshot.Item> items=new ArrayList<>();files.forEach((name,size) -> items.add(new SourceSnapshot.Item(prefix(selected)+name,size,"etag-"+size,BASE)));
        store.transaction(() -> {store.beginSnapshot();store.stage(items);service.apply(selected,at(minute));});
    }
    private void finish(Instant now,SourceSnapshot.Source... sources) {store.transaction(() -> reports.report(now,Arrays.stream(sources).map(SourceSnapshot.Source::id).collect(java.util.stream.Collectors.toSet()),true));}
    private int count(String kind) {return jdbc.queryForObject("SELECT count(*) FROM ingest_monitor_notification WHERE source_ids && ?::bigint[] AND event_type=?",Integer.class,MonitorStore.ids(store.sourceIds(source.id())),kind);}
    private String payload(String kind) {return jdbc.queryForObject("SELECT payload::text FROM ingest_monitor_notification WHERE source_ids && ?::bigint[] AND event_type=? ORDER BY notification_id DESC LIMIT 1",String.class,MonitorStore.ids(store.sourceIds(source.id())),kind);}
    private static String prefix(SourceSnapshot.Source source) {return java.net.URI.create(source.uri()).getPath().substring(1);}
    private static Instant at(int minute) {return BASE.plusSeconds(minute*60L);}
}
