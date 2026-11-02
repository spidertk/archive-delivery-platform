package com.powerjob.archive.core.service;

import com.powerjob.archive.core.config.MvpProperties;
import com.powerjob.archive.core.domain.DiscoveredObject;
import com.powerjob.archive.core.repository.SourcePackageRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MvpSeedService {
    private final DiscoveryService discovery;
    private final SourcePackageRepository sources;
    private final MvpProperties properties;
    private final JdbcTemplate jdbc;

    public MvpSeedService(DiscoveryService discovery, SourcePackageRepository sources,
                          MvpProperties properties, JdbcTemplate jdbc) {
        this.discovery = discovery;
        this.sources = sources;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    public List<Long> seed() {
        long pipelineVersionId = 1L;
        List<DiscoveredObject> objects = discovery.discover();
        return java.util.stream.IntStream.range(0, objects.size()).mapToObj(index -> {
            DiscoveredObject object = objects.get(index);
            long lineId = index < 4 ? 1L : index < 7 ? 2L : 3L;
            return sources.upsertPlanned(new DiscoveredObject(object.bucketName(), object.objectKey(),
                    object.objectVersion(), object.etag(), object.size(), lineId), pipelineVersionId, properties.getArchiveDate());
        }).toList();
    }
}
