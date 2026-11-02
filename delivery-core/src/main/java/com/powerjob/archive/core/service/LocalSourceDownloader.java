package com.powerjob.archive.core.service;

import com.powerjob.archive.core.domain.SourcePackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
@ConditionalOnProperty(name = "archive.discovery-mode", havingValue = "local")
public class LocalSourceDownloader implements SourceDownloader {
    private final FileTransferService files;

    public LocalSourceDownloader(FileTransferService files) {
        this.files = files;
    }

    @Override
    public void download(SourcePackage source, com.powerjob.archive.core.pipeline.OssNodeConfig config,
                         Path destination) throws Exception {
        try (var input = Files.newInputStream(Path.of(source.objectKey()))) {
            files.copyStream(input, destination);
        }
    }
}
