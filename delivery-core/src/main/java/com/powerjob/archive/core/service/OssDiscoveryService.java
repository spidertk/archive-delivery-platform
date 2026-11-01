package com.powerjob.archive.core.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.ListObjectsV2Result;
import com.aliyun.oss.model.OSSObjectSummary;
import com.powerjob.archive.core.config.MvpProperties;
import com.powerjob.archive.core.domain.DiscoveredObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "archive.discovery-mode", havingValue = "oss")
public class OssDiscoveryService implements DiscoveryService {
    private final OssClientFactory clients;
    private final MvpProperties properties;

    public OssDiscoveryService(OssClientFactory clients, MvpProperties properties) {
        this.clients = clients;
        this.properties = properties;
    }

    @Override
    public List<DiscoveredObject> discover() {
        OSS client = clients.create();
        try {
            ListObjectsV2Result result = client.listObjectsV2(new ListObjectsV2Request(properties.getOssSourceBucket())
                    .withPrefix(properties.getOssSourcePrefix()).withMaxKeys(100));
            List<OSSObjectSummary> objects = result.getObjectSummaries().stream()
                    .filter(object -> object.getKey().endsWith(".tar.gz") && object.getSize() > 0)
                    .sorted(java.util.Comparator.comparing(OSSObjectSummary::getKey))
                    .limit(properties.getGroupSize() * 2L)
                    .collect(Collectors.toList());
            if (objects.size() < properties.getGroupSize() * 2L) {
                throw new IllegalStateException("OSS prefix contains fewer than 10 tar.gz packages");
            }
            return java.util.stream.IntStream.range(0, objects.size()).mapToObj(index -> {
                OSSObjectSummary object = objects.get(index);
                String etag = object.getETag() == null ? null : object.getETag().replace("\"", "");
                return new DiscoveredObject(properties.getOssSourceBucket(), object.getKey(), etag,
                        etag, object.getSize(), index < properties.getGroupSize() ? 1L : 2L);
            }).collect(Collectors.toList());
        } finally {
            client.shutdown();
        }
    }
}
