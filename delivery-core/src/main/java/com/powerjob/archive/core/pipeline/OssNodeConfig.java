package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OssNodeConfig(String endpoint, String bucket, String prefix,
                            String accessKeyId, String accessKeySecret) {
    public void validate(boolean source) {
        if (blank(endpoint) || blank(accessKeyId) || blank(accessKeySecret) || !source && blank(bucket)) {
            throw new IllegalArgumentException("OSS endpoint/credentials and upload bucket are required");
        }
        if (placeholder(endpoint) || placeholder(accessKeyId) || placeholder(accessKeySecret)
                || !blank(bucket) && placeholder(bucket)) {
            throw new IllegalArgumentException("OSS placeholder or masked configuration cannot be published");
        }
        if (source && (!blank(bucket) || !blank(prefix))) {
            throw new IllegalArgumentException("Source bucket/prefix must come from the submitted ossUri");
        }
        if (!blank(bucket) && (bucket.contains("oss://") || bucket.contains("/"))) {
            throw new IllegalArgumentException("Invalid OSS bucket");
        }
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
