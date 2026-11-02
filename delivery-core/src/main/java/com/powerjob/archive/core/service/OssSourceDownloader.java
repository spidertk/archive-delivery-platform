package com.powerjob.archive.core.service;

import com.aliyun.oss.OSS;
import com.powerjob.archive.core.domain.SourcePackage;
import com.powerjob.archive.core.pipeline.OssNodeConfig;
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
    public void download(SourcePackage source, OssNodeConfig config, Path destination) throws Exception {
        OSS client = clients.create(config);
        try {
            var metadata = client.getObjectMetadata(source.bucketName(), source.objectKey());
            if (metadata.getContentLength() != source.sourceSize()) {
                throw new java.io.IOException("Source OSS size does not match discovery metadata");
            }
            if (java.nio.file.Files.isRegularFile(destination)
                    && java.nio.file.Files.size(destination) == source.sourceSize()
                    && source.sourceSha256() != null
                    && files.sha256(destination).equals(source.sourceSha256())) {
                return;
            }
            try (var input = client.getObject(source.bucketName(), source.objectKey()).getObjectContent()) {
                files.copyStream(input, destination);
            }
            if (java.nio.file.Files.size(destination) != source.sourceSize()) {
                throw new java.io.IOException("Downloaded size does not match source metadata");
            }
            if (source.sourceSha256() != null && !files.sha256(destination).equals(source.sourceSha256())) {
                throw new java.io.IOException("Downloaded SHA-256 does not match source metadata");
            }
        } finally {
            client.shutdown();
        }
    }
}
