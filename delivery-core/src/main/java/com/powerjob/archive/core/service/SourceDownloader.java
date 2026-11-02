package com.powerjob.archive.core.service;

import com.powerjob.archive.core.domain.SourcePackage;
import com.powerjob.archive.core.pipeline.OssNodeConfig;

import java.nio.file.Path;

public interface SourceDownloader {
    void download(SourcePackage source, OssNodeConfig config, Path destination) throws Exception;
}
