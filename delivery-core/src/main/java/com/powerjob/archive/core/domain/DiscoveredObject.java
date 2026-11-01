package com.powerjob.archive.core.domain;

public record DiscoveredObject(
        String bucketName,
        String objectKey,
        String objectVersion,
        String etag,
        long size,
        long productionLineId) {
}
