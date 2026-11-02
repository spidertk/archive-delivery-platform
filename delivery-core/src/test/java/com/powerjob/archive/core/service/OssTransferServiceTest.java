package com.powerjob.archive.core.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.powerjob.archive.core.pipeline.OssNodeConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OssTransferServiceTest {
    @Test
    void uploadsWithMd5AndShaThenSkipsIdenticalRemoteObject() throws Exception {
        Path artifact = Files.createTempFile("artifact", ".tar.gz");
        Files.writeString(artifact, "content");
        String sha = new FileTransferService().sha256(artifact);
        OSS client = mock(OSS.class);
        when(client.doesObjectExist(anyString(), anyString())).thenReturn(false);
        when(client.getObjectMetadata(anyString(), anyString())).thenReturn(metadata(artifact, sha));
        OssTransferService service = new OssTransferService(client);
        assertThat(service.uploadHz(config(), artifact, "june/date/batch_00001.tar.gz", sha, 1, 2).uploaded()).isTrue();
        verify(client).putObject(any(PutObjectRequest.class));

        when(client.doesObjectExist(anyString(), anyString())).thenReturn(true);
        assertThat(service.uploadHz(config(), artifact, "june/date/batch_00001.tar.gz", sha, 1, 2).uploaded()).isFalse();
        verify(client).putObject(any(PutObjectRequest.class));
        assertThat(service.hzUri(config(), "june/date/batch_00001.tar.gz"))
                .isEqualTo("oss://hz-bucket/june/date/batch_00001.tar.gz");
    }

    @Test
    void rejectsSameKeyWithDifferentSha() throws Exception {
        Path artifact = Files.createTempFile("artifact", ".tar.gz");
        Files.writeString(artifact, "content");
        OSS client = mock(OSS.class);
        when(client.doesObjectExist(anyString(), anyString())).thenReturn(true);
        when(client.getObjectMetadata(anyString(), anyString())).thenReturn(metadata(artifact, "different"));
        OssTransferService service = new OssTransferService(client);
        assertThatThrownBy(() -> service.uploadHz(config(), artifact, "june/date/batch_00001.tar.gz",
                new FileTransferService().sha256(artifact), 1, 2)).isInstanceOf(RemoteConflictException.class);
        verify(client, never()).putObject(any(PutObjectRequest.class));
    }

    private static ObjectMetadata metadata(Path artifact, String sha) throws Exception {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(Files.size(artifact));
        metadata.setUserMetadata(Map.of("sha256", sha));
        return metadata;
    }

    private static OssNodeConfig config() {
        return new OssNodeConfig("https://oss-cn-hangzhou.aliyuncs.com", "hz-bucket", null, "id", "secret");
    }
}
