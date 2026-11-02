package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.config.MvpProperties;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeHandler;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.service.FileTransferService;
import com.powerjob.archive.core.service.SourceDownloader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class DownloadNode implements NodeHandler {
    private final SourceDownloader downloader;
    private final FileTransferService files;
    private final MvpProperties properties;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final com.powerjob.archive.core.pipeline.PipelineNodeConfig configs;

    public DownloadNode(SourceDownloader downloader, FileTransferService files, MvpProperties properties,
                        ObjectMapper mapper, JdbcTemplate jdbc,
                        com.powerjob.archive.core.pipeline.PipelineNodeConfig configs) {
        this.downloader = downloader;
        this.files = files;
        this.properties = properties;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.configs = configs;
    }

    @Override
    public String type() { return "archive.download"; }

    @Override
    public NodeResult execute(NodeContext context) throws Exception {
        String fileName = Path.of(context.source().objectKey()).getFileName().toString();
        Path target = Path.of(properties.getWorkRoot(), "source-" + context.source().id(), "input", fileName);
        var config = "oss".equals(properties.getDiscoveryMode()) ? configs.oss(context.node(), true) : null;
        downloader.download(context.source(), config, target);
        String sha = files.sha256(target);
        if (target.toFile().length() != context.source().sourceSize()) {
            throw new IllegalStateException("Downloaded size does not match source metadata");
        }
        jdbc.update("UPDATE source_package SET source_sha256=? WHERE id=?", sha, context.source().id());
        var manifest = mapper.createObjectNode();
        manifest.put("localPath", target.toAbsolutePath().toString());
        manifest.put("sha256", sha);
        manifest.put("sourceSha256", sha);
        manifest.put("objectVersion", context.source().objectVersion());
        return NodeResult.of("repair", "DOWNLOADED", manifest);
    }
}
