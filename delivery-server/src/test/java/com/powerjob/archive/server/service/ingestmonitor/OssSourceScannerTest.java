package com.powerjob.archive.server.service.ingestmonitor;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.ListObjectsV2Result;
import com.aliyun.oss.model.OSSObjectSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.pipeline.PipelineNodeConfig;
import com.powerjob.archive.core.pipeline.PipelineRepository;
import com.powerjob.archive.core.service.OssClientFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OssSourceScannerTest {
    final OSS oss = mock(OSS.class);
    final MonitorSettings settings = new MonitorSettings();
    final SourceSnapshot.Source source = new SourceSnapshot.Source(1, "oss://bucket/Prefix/");

    private OssSourceScanner scanner() {
        // 只用无效测试凭据创建内存配置；所有 SDK 外部请求由替身接管。
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("archive-batch"))).thenReturn("""
                {"pipelineCode":"archive-batch","version":1,"config":{},"nodeDefinitions":{},"nodes":[
                {"id":"sourceDownload","type":"oss.download","config":{"endpoint":"https://example.test",
                "accessKeyId":"test-id","accessKeySecret":"test-value"}}]}
                """);
        var factory = mock(OssClientFactory.class);
        when(factory.create(any())).thenReturn(oss);
        var mapper = new ObjectMapper();
        return new OssSourceScanner(jdbc, mapper, new PipelineRepository(mapper), new PipelineNodeConfig(mapper), factory, settings);
    }

    @Test void paginatesRecursivelyKeepsZeroByteFilesAndSkipsDirectoryMarkers() throws Exception {
        // 覆盖跨页、递归子目录和零字节文件，避免目录占位符污染文件数。
        var first = page(true, "next", "Prefix/a", 10);
        first.getObjectSummaries().add(object("Prefix/folder/", 0));
        var second = page(false, null, "Prefix/folder/empty", 0);
        when(oss.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(invocation -> {
            ListObjectsV2Request request = invocation.getArgument(0);
            assertEquals("bucket", request.getBucketName());
            assertEquals("Prefix/", request.getPrefix());
            assertNull(request.getDelimiter());
            return request.getContinuationToken() == null ? first : second;
        });
        var result = new java.util.HashMap<String, SourceSnapshot.Item>();
        scanner().scan(source, page -> page.forEach(item -> result.put(item.key(), item)));
        assertEquals(2, result.size());
        assertEquals(0, result.get("Prefix/folder/empty").size());
        verify(oss, times(2)).listObjectsV2(any(ListObjectsV2Request.class));
        verify(oss).shutdown();
        verify(oss, never()).getObject(anyString(), anyString());
    }

    @Test void partialPaginationAndLimitFailuresNeverReturnIncompleteFacts() {
        // 页续接错误或上限截断必须失败，禁止把部分清单传成成功扫描。
        when(oss.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page(true, null, "Prefix/a", 10));
        assertThrows(IllegalStateException.class, () -> scanner().scan(source, ignored -> {}));
        verify(oss).shutdown();
        reset(oss);
        settings.setMaxObjects(1);
        var page = page(false, null, "Prefix/a", 10);
        page.getObjectSummaries().add(object("Prefix/b", 10));
        when(oss.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page);
        assertThrows(IllegalStateException.class, () -> scanner().scan(source, ignored -> {}));
        verify(oss).shutdown();
    }

    private static ListObjectsV2Result page(boolean truncated, String next, String key, long size) {
        var result = new ListObjectsV2Result();
        result.setTruncated(truncated); result.setNextContinuationToken(next);
        result.getObjectSummaries().add(object(key, size));
        return result;
    }
    private static OSSObjectSummary object(String key, long size) {
        var object = new OSSObjectSummary();
        object.setKey(key); object.setSize(size); object.setETag("etag"); object.setLastModified(Date.from(Instant.EPOCH));
        return object;
    }
}
