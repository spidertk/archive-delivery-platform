package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.config.ArchiveProperties;
import com.powerjob.archive.core.domain.DeliveryEntity;
import com.powerjob.archive.core.domain.DeliveryTask;
import com.powerjob.archive.core.domain.SourceData;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeDefinition;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.pipeline.OssNodeConfig;
import com.powerjob.archive.core.pipeline.PipelineDefinition;
import com.powerjob.archive.core.pipeline.PipelineNodeConfig;
import com.powerjob.archive.core.service.FileTransferService;
import com.powerjob.archive.core.service.OssTransferService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OssDownloadNodeTest {
    @TempDir Path temp;

    @Test void verifiedSharedCacheAvoidsAnotherOssDownload() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        FileTransferService files = new FileTransferService();
        OssTransferService transfers = mock(OssTransferService.class);
        PipelineNodeConfig configs = mock(PipelineNodeConfig.class);
        when(configs.oss(any(), anyBoolean())).thenReturn(new OssNodeConfig("https://oss.example", "bucket", "prefix/", "ak", "sk"));
        ArchiveProperties properties = new ArchiveProperties();
        properties.setWorkRoot(temp.toString());
        OssDownloadNode handler = new OssDownloadNode(transfers, files, configs, properties, mapper);

        Path cached = temp.resolve("shared/source.tar.gz");
        Files.createDirectories(cached.getParent());
        Files.writeString(cached, "complete-original-package");
        long size = Files.size(cached);
        String sha = files.sha256(cached);
        var locator = mapper.createObjectNode().put("sourceUri", "oss://bucket/prefix/source.tar.gz")
                .put("sourceVersion", "v1").put("cachePath", cached.toString());
        SourceData source = new SourceData(11, 8, "source.tar.gz", "", locator, null,
                "PENDING", null, null, 0, size, size, sha, null);
        DeliveryTask task = new DeliveryTask(7, 8, 10, "jun", "group1",
                LocalDate.parse("2026-11-09"), OffsetDateTime.parse("2026-11-09T18:00:00+08:00"),
                "batch_00001.tar.gz", size, size, null, null, "sourceDownload", "READY",
                null, null, 0, 0, null, null, null);
        DeliveryEntity entity = new DeliveryEntity(10, "alibaba", "jun", "Local", "FTP", "entity1", "group1", null, true);
        NodeDefinition definition = new NodeDefinition("sourceDownload", "oss.download", "ACQUIRE", "Download", "mergePackages", Map.of());
        NodeDefinition next = new NodeDefinition("mergePackages", "package.merge", "MERGE", "Merge", null, Map.of());
        PipelineDefinition pipeline = new PipelineDefinition("archive-delivery", 1, Map.of(), Map.of(), List.of(definition, next));
        NodeContext context = new NodeContext(task, source, List.of(source), entity, definition, pipeline, 100, 1, "worker", Map.of());

        NodeResult result = handler.execute(context);
        Path taskCopy = temp.resolve("delivery-8/task-7/sources/data-11/source.tar.gz");
        assertTrue(result.outputLocator().required("sources").required(0).required("cacheHit").asBoolean());
        assertEquals(sha, files.sha256(taskCopy));
        assertEquals(size, Files.size(taskCopy));
        verify(transfers, never()).download(any(), any(), any(), any(), any(), any());
    }
}
