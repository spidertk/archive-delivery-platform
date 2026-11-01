package com.powerjob.archive.core.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.powerjob.archive.core.config.OssDeliveryProperties;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;

@Service
public class OssTransferService {
    private final OssDeliveryProperties properties;
    private final OSS hzOverride;

    public OssTransferService(OssDeliveryProperties properties) {
        this(properties, null);
    }

    OssTransferService(OssDeliveryProperties properties, OSS hzOverride) {
        this.properties = properties;
        this.hzOverride = hzOverride;
    }

    public UploadResult uploadHz(Path artifact, String key, String expectedSha, long runId, long packageId) throws Exception {
        RemotePathService.validatePackageName(Path.of(key).getFileName().toString());
        OSS client = hzOverride == null ? client(properties.getHz()) : hzOverride;
        boolean close = hzOverride == null;
        try {
            String bucket = properties.getHz().getBucket();
            if (client.doesObjectExist(bucket, key)) {
                ObjectMetadata existing = client.getObjectMetadata(bucket, key);
                if (existing.getContentLength() == Files.size(artifact)
                        && expectedSha.equals(existing.getUserMetadata().get("sha256"))) {
                    return new UploadResult(key, false);
                }
                throw new RemoteConflictException("HZ OSS key exists with different size or SHA: " + key);
            }
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(Files.size(artifact));
            metadata.setContentMD5(contentMd5(artifact));
            metadata.addUserMetadata("sha256", expectedSha);
            metadata.addUserMetadata("runId", Long.toString(runId));
            metadata.addUserMetadata("packageId", Long.toString(packageId));
            try (InputStream input = Files.newInputStream(artifact)) {
                client.putObject(new PutObjectRequest(bucket, key, input, metadata));
            }
            ObjectMetadata uploaded = client.getObjectMetadata(bucket, key);
            if (uploaded.getContentLength() != Files.size(artifact)
                    || !expectedSha.equals(uploaded.getUserMetadata().get("sha256"))) {
                throw new IllegalStateException("HZ OSS verification failed for " + key);
            }
            return new UploadResult(key, true);
        } finally {
            if (close) client.shutdown();
        }
    }

    public String hzUri(String key) {
        return "oss://" + properties.getHz().getBucket() + "/" + key;
    }

    private static OSS client(OssDeliveryProperties.Endpoint endpoint) {
        ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
        configuration.setConnectionTimeout(10_000);
        configuration.setSocketTimeout(60_000);
        configuration.setMaxErrorRetry(2);
        return new OSSClientBuilder().build(endpoint.getEndpoint(), endpoint.getAccessKeyId(),
                endpoint.getAccessKeySecret(), configuration);
    }

    private static String contentMd5(Path artifact) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        try (InputStream input = Files.newInputStream(artifact)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return Base64.getEncoder().encodeToString(digest.digest());
    }

    public record UploadResult(String key, boolean uploaded) {}
}
