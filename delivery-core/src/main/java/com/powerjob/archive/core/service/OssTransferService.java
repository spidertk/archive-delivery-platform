package com.powerjob.archive.core.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.powerjob.archive.core.pipeline.OssNodeConfig;
import org.springframework.stereotype.Service;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;

@Service
public class OssTransferService {
    private final OssClientFactory clients;
    private final FileTransferService files;

    public OssTransferService(OssClientFactory clients, FileTransferService files) {
        this.clients = clients;
        this.files = files;
    }

    public String download(OssNodeConfig config, String bucket, String key, Path destination,
                           Long expectedSize, String expectedSha) throws Exception {
        OSS client = clients.create(config);
        try {
            ObjectMetadata metadata = client.getObjectMetadata(bucket, key);
            if (expectedSize != null && metadata.getContentLength() != expectedSize) throw new IOExceptionSizeMismatch();
            if (Files.isRegularFile(destination) && (expectedSize == null || Files.size(destination) == expectedSize)
                    && (expectedSha == null || files.sha256(destination).equals(expectedSha))) return files.sha256(destination);
            try (InputStream input = client.getObject(bucket, key).getObjectContent()) {
                files.copyStream(input, destination);
            }
            if (expectedSize != null && Files.size(destination) != expectedSize) throw new IOExceptionSizeMismatch();
            String sha = files.sha256(destination);
            if (expectedSha != null && !expectedSha.equals(sha)) throw new RemoteConflictException("Source OSS SHA changed after discovery");
            return sha;
        } finally {
            client.shutdown();
        }
    }

    /** 上传后再次 GET 到任务私有缓存，后续 FTP 节点只消费这份已从交付 OSS 核验的副本。 */
    public UploadResult upload(OssNodeConfig config, Path artifact, String key, String expectedSha,
                               long deliveryId, long taskId, Path verifiedDownload) throws Exception {
        RemotePathService.validatePackageName(Path.of(key).getFileName().toString());
        OSS client = clients.create(config);
        try {
            if (client.doesObjectExist(config.bucket(), key)) {
                ObjectMetadata existing = client.getObjectMetadata(config.bucket(), key);
                if (existing.getContentLength() != Files.size(artifact)
                        || !expectedSha.equals(existing.getUserMetadata().get("sha256"))) {
                    throw new RemoteConflictException("Delivery OSS key exists with different size or SHA: " + key);
                }
            } else {
                ObjectMetadata metadata = new ObjectMetadata();
                metadata.setContentLength(Files.size(artifact));
                metadata.setContentMD5(contentMd5(artifact));
                metadata.addUserMetadata("sha256", expectedSha);
                metadata.addUserMetadata("deliveryId", Long.toString(deliveryId));
                metadata.addUserMetadata("taskId", Long.toString(taskId));
                try (InputStream input = Files.newInputStream(artifact)) {
                    client.putObject(new PutObjectRequest(config.bucket(), key, input, metadata));
                }
            }
            ObjectMetadata uploaded = client.getObjectMetadata(config.bucket(), key);
            if (uploaded.getContentLength() != Files.size(artifact)
                    || !expectedSha.equals(uploaded.getUserMetadata().get("sha256"))) {
                throw new RemoteConflictException("Delivery OSS verification failed for " + key);
            }
            try (InputStream input = client.getObject(config.bucket(), key).getObjectContent()) {
                files.copyStream(input, verifiedDownload);
            }
            if (Files.size(verifiedDownload) != Files.size(artifact) || !files.sha256(verifiedDownload).equals(expectedSha)) {
                throw new RemoteConflictException("Delivery OSS downloaded copy differs from uploaded artifact");
            }
            return new UploadResult("oss://" + config.bucket() + "/" + key, uploaded.getContentLength(), expectedSha);
        } finally {
            client.shutdown();
        }
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

    private static class IOExceptionSizeMismatch extends java.io.IOException {
        IOExceptionSizeMismatch() { super("OSS object size does not match expected fact"); }
    }

    public record UploadResult(String uri, long sizeBytes, String sha256) {}
}
