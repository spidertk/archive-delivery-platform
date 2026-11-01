package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.powerjob.archive.core.domain.OutputPackage;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeDefinition;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.repository.OutputPackageRepository;
import com.powerjob.archive.core.service.OssTransferService;
import com.powerjob.archive.core.service.RemoteConflictException;
import com.powerjob.archive.core.service.RemotePathService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OssHzUploadNodeTest {
    @Test
    void persistsHzFactsAndMovesToSgVerify() throws Exception {
        Path artifact = Files.createTempFile("batch_00001", ".tar.gz");
        Files.writeString(artifact, "content");
        OssTransferService transfers = mock(OssTransferService.class);
        RemotePathService paths = mock(RemotePathService.class);
        OutputPackageRepository outputs = mock(OutputPackageRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(paths.objectKey(10)).thenReturn("june/2026-10-30-3-june/batch_00001.tar.gz");
        when(transfers.uploadHz(org.mockito.ArgumentMatchers.eq(artifact), anyString(), anyString(), anyLong(), anyLong()))
                .thenReturn(new OssTransferService.UploadResult("june/2026-10-30-3-june/batch_00001.tar.gz", true));
        when(transfers.hzUri(anyString())).thenReturn("oss://hz/june/2026-10-30-3-june/batch_00001.tar.gz");
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class), anyLong())).thenReturn(1L);
        OutputPackage output = output(artifact);
        NodeResult result = new OssHzUploadNode(transfers, paths, outputs, jdbc)
                .execute(new NodeContext(null, output, new NodeDefinition("hzUpload", "OUTPUT", "oss.hz-upload", Map.of()),
                        1, 1, "worker"));
        assertThat(result.nextNode()).isEqualTo("sgVerify");
        assertThat(result.businessStatus()).isEqualTo("HZ_UPLOADED");
        verify(outputs).updatePath(10, "hz_path", "oss://hz/june/2026-10-30-3-june/batch_00001.tar.gz");
        verify(outputs).updateArtifactPath(10, "hz_path", "oss://hz/june/2026-10-30-3-june/batch_00001.tar.gz");
    }

    @Test
    void conflictDoesNotPersistCompletionFacts() throws Exception {
        Path artifact = Files.createTempFile("batch_00001", ".tar.gz");
        Files.writeString(artifact, "content");
        OssTransferService transfers = mock(OssTransferService.class);
        RemotePathService paths = mock(RemotePathService.class);
        OutputPackageRepository outputs = mock(OutputPackageRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(paths.objectKey(10)).thenReturn("june/date/batch_00001.tar.gz");
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class), anyLong())).thenReturn(1L);
        when(transfers.uploadHz(org.mockito.ArgumentMatchers.any(), anyString(), anyString(), anyLong(), anyLong()))
                .thenThrow(new RemoteConflictException("conflict"));
        OutputPackage output = output(artifact);
        OssHzUploadNode node = new OssHzUploadNode(transfers, paths, outputs, jdbc);
        assertThatThrownBy(() -> node.execute(new NodeContext(null, output,
                new NodeDefinition("hzUpload", "OUTPUT", "oss.hz-upload", Map.of()), 1, 1, "worker")))
                .isInstanceOf(RemoteConflictException.class);
        verify(outputs, never()).updatePath(anyLong(), anyString(), anyString());
    }

    private static OutputPackage output(Path artifact) throws Exception {
        String sha = new com.powerjob.archive.core.service.FileTransferService().sha256(artifact);
        JsonNode manifest = new ObjectMapper().readTree("{\"artifacts\":[{\"path\":\"" + artifact +
                "\",\"name\":\"batch_00001.tar.gz\",\"bytes\":7,\"sha256\":\"" + sha + "\"}]}");
        return new OutputPackage(10, 20, 2, manifest, 1, 1, "2026-10-30", "2026-10-30", 1,
                "batch_00001.tar.gz", "GROUPED", "RUNNING", "hzUpload", "worker", 1, manifest);
    }
}
