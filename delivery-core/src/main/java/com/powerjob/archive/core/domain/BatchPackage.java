package com.powerjob.archive.core.domain;

public record BatchPackage(String newFileName, String workerGroup, int sourceCount, long sourceBytes) {
}
