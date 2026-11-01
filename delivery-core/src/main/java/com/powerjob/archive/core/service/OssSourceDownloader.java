package com.powerjob.archive.core.service;

import com.aliyun.oss.OSS;
import com.powerjob.archive.core.domain.SourcePackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
@ConditionalOnProperty(name = "archive.discovery-mode", havingValue = "oss")
public class OssSourceDownloader implements SourceDownloader {
    private final OssClientFactory clients;
    private final FileTransferService files;

    public OssSourceDownloader(OssClientFactory clients, FileTransferService files) {
        this.clients = clients;
        this.files = files;
    }

    @Override
    public void download(SourcePackage source, Path destination) throws Exception {
        OSS client = clients.create();
        try (var input = client.getObject(source.bucketName(), source.objectKey()).getObjectContent()) {
            files.copyStream(input, destination);
        } finally {
            client.shutdown();
        }
    }
}
