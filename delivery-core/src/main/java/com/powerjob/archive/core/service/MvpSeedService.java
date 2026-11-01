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
        String snapshot = jdbc.queryForObject("SELECT definition::text FROM pipeline_version WHERE id=1", String.class);
        List<DiscoveredObject> objects = discovery.discover();
        return objects.stream().map(object -> sources.upsertPlanned(object, snapshot, properties.getArchiveDate())).toList();
    }
}
