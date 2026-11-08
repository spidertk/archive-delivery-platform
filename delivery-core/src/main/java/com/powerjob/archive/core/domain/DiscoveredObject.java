package com.powerjob.archive.core.domain;

/** OSS 发现结果使用稳定版本、真实 SHA 和共享缓存路径建立源数据身份，不把 ETag 当作内容 SHA。 */
public record DiscoveredObject(
        String bucketName, String objectKey, String objectVersion, String etag,
        long size, String sha256, String cachePath) {
    public DiscoveredObject(String bucketName, String objectKey, String objectVersion, String etag,
                            long size, String sha256) {
        this(bucketName, objectKey, objectVersion, etag, size, sha256, null);
    }

    public String sourceUri() {
        return "oss://" + bucketName + "/" + objectKey;
    }
}
