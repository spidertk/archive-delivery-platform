package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OssNodeConfig(String endpoint, String bucket, String prefix,
                            String accessKeyId, String accessKeySecret, boolean useFtpFileName) {
    public void validate(boolean source) {
        if (blank(endpoint) || blank(accessKeyId) || blank(accessKeySecret) || !source && blank(bucket)) {
            throw new IllegalArgumentException("OSS endpoint/credentials and upload bucket are required");
        }
        if (placeholder(endpoint) || placeholder(accessKeyId) || placeholder(accessKeySecret)
                || !blank(bucket) && placeholder(bucket)) {
            throw new IllegalArgumentException("OSS placeholder or masked configuration cannot be published");
        }
        if (source && (!blank(bucket) || !blank(prefix) || useFtpFileName)) {
            throw new IllegalArgumentException("Source location must come from the submitted ossUri");
        }
        if (!blank(bucket) && (bucket.contains("oss://") || bucket.contains("/"))) {
            throw new IllegalArgumentException("Invalid OSS bucket");
        }
        List<String> directories = prefix == null ? List.of() : List.of(prefix.split("/", -1));
        if (directories.contains("..")) {
            throw new IllegalArgumentException("OSS prefix must not contain ..");
        }
        // FTP 序号按业务日和实体独立分配；厂商和实体各占一个路径段，避免同名对象冲突。
        if (useFtpFileName && (!directories.contains("{providerCode}") || !directories.contains("{entityCode}")
                || !prefix.contains("{deliveryDate}"))) {
            throw new IllegalArgumentException("OSS useFtpFileName requires {deliveryDate}, {providerCode} and {entityCode} isolation");
        }
    }

    private static boolean placeholder(String value) {
        return value.contains("replace-") || value.contains("********");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
