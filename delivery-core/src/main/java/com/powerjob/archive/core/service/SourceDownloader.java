package com.powerjob.archive.core.service;

import com.powerjob.archive.core.domain.SourcePackage;

import java.nio.file.Path;

public interface SourceDownloader {
    void download(SourcePackage source, Path destination) throws Exception;
}
