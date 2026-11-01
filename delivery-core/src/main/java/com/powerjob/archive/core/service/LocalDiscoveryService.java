package com.powerjob.archive.core.service;

import com.powerjob.archive.core.config.MvpProperties;
import com.powerjob.archive.core.domain.DiscoveredObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "archive.discovery-mode", havingValue = "local")
public class LocalDiscoveryService implements DiscoveryService {
    private final MvpProperties properties;
    private final FileTransferService files;

    public LocalDiscoveryService(MvpProperties properties, FileTransferService files) {
        this.properties = properties;
        this.files = files;
    }

    @Override
    public List<DiscoveredObject> discover() {
        try (var paths = Files.list(Path.of(properties.getLocalSourceDir()))) {
            List<Path> sources = paths.filter(path -> path.getFileName().toString().endsWith(".tar.gz"))
                    .sorted().limit(properties.getGroupSize() * 2L).collect(Collectors.toList());
            if (sources.size() < properties.getGroupSize() * 2L) {
                throw new IllegalStateException("Local source directory requires 10 tar.gz packages");
            }
            return java.util.stream.IntStream.range(0, sources.size()).mapToObj(index -> {
                Path path = sources.get(index);
                try {
                    String sha = files.sha256(path);
                    return new DiscoveredObject("local", path.toAbsolutePath().toString(), sha, sha,
                            Files.size(path), index < properties.getGroupSize() ? 1L : 2L);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).collect(Collectors.toList());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot discover local source packages", e);
        }
    }
}
