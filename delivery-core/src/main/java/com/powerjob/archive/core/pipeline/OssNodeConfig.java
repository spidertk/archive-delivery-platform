package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OssNodeConfig(String endpoint, String bucket, String prefix,
                            String accessKeyId, String accessKeySecret) {
    public void validate(boolean prefixRequired) {
        if (blank(endpoint) || blank(bucket) || blank(accessKeyId) || blank(accessKeySecret)) {
            throw new IllegalArgumentException("OSS endpoint/bucket/credentials are required");
        }
        if (bucket.contains("oss://")) {
            throw new IllegalArgumentException("OSS bucket must not contain oss://");
        }
        if (prefixRequired && blank(prefix)) {
            throw new IllegalArgumentException("Source OSS prefix is required");
        }
        if (prefix != null && List.of(prefix.split("/", -1)).contains("..")) {
            throw new IllegalArgumentException("OSS prefix must not contain ..");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
