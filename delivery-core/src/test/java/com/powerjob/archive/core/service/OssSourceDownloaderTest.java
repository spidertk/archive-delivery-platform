package com.powerjob.archive.core.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.fasterxml.jackson.databind.node.NullNode;
import com.powerjob.archive.core.domain.SourcePackage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OssSourceDownloaderTest {
    private final FileTransferService files = new FileTransferService();
    private OssClientFactory clients;
    private OSS client;
    private OssSourceDownloader downloader;
    private Path destination;

    @BeforeEach
    void setUp() throws Exception {
        clients = mock(OssClientFactory.class);
        client = mock(OSS.class);
        when(clients.create()).thenReturn(client);
        downloader = new OssSourceDownloader(clients, files);
        destination = Files.createTempDirectory("archive-download").resolve("batch_00001.tar.gz");
    }

    @Test
    void skipsGetWhenCompleteFileMatchesSizeAndSha() throws Exception {
        byte[] body = "complete".getBytes(StandardCharsets.UTF_8);
        Files.write(destination, body);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(body.length);
        when(client.getObjectMetadata(anyString(), anyString())).thenReturn(metadata);
        downloader.download(source(body.length, files.sha256(destination)), destination);
        verify(client, never()).getObject(anyString(), anyString());
    }

    @Test
    void replacesPartialAndAtomicallyPublishesCompleteDownload() throws Exception {
        byte[] body = "fresh-download".getBytes(StandardCharsets.UTF_8);
        Files.write(destination.resolveSibling(destination.getFileName() + ".partial"), "stale".getBytes(StandardCharsets.UTF_8));
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(body.length);
        when(client.getObjectMetadata(anyString(), anyString())).thenReturn(metadata);
        OSSObject object = new OSSObject();
        object.setObjectContent(new ByteArrayInputStream(body));
        when(client.getObject(anyString(), anyString())).thenReturn(object);
        downloader.download(source(body.length, null), destination);
        assertThat(Files.readAllBytes(destination)).isEqualTo(body);
        assertThat(destination.resolveSibling(destination.getFileName() + ".partial")).doesNotExist();
    }

    @Test
    void interruptedGetNeverPublishesFinalFile() throws Exception {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(100);
        when(client.getObjectMetadata(anyString(), anyString())).thenReturn(metadata);
        OSSObject object = new OSSObject();
        object.setObjectContent(new InputStream() {
            @Override public int read() { return -1; }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                throw new IOException("injected interruption");
            }
        });
        when(client.getObject(anyString(), anyString())).thenReturn(object);
        assertThatThrownBy(() -> downloader.download(source(100, null), destination)).isInstanceOf(IOException.class);
        assertThat(destination).doesNotExist();
    }

    private static SourcePackage source(long size, String sha) {
        return new SourcePackage(1, 2, NullNode.instance, "bucket", "prefix/batch_00001.tar.gz", "etag",
                size, sha, "PLANNED", "RUNNING", 1, "download", "worker", 1, NullNode.instance);
    }
}
