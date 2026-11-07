package com.powerjob.archive.core.domain;

/** OSS 发现结果使用稳定版本与真实下载 SHA 建立源数据身份，不把 ETag 当作内容 SHA。 */
public record DiscoveredObject(
        String bucketName, String objectKey, String objectVersion, String etag,
        long size, String sha256) {
    public String sourceUri() {
        return "oss://" + bucketName + "/" + objectKey;
    }
}
