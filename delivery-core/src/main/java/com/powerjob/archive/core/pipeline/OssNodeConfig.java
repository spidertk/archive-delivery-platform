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
        if (placeholder(endpoint) || placeholder(bucket) || placeholder(accessKeyId) || placeholder(accessKeySecret)) {
            throw new IllegalArgumentException("OSS placeholder or masked configuration cannot be published");
        }
        if (bucket.contains("oss://") || bucket.contains("/")) throw new IllegalArgumentException("Invalid OSS bucket");
        if (prefixRequired && blank(prefix)) throw new IllegalArgumentException("Source OSS prefix is required");
        if (prefix != null && List.of(prefix.split("/", -1)).contains("..")) {
            throw new IllegalArgumentException("OSS prefix must not contain ..");
        }
    }

    private static boolean placeholder(String value) {
        return value.contains("replace-") || value.contains("********");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
