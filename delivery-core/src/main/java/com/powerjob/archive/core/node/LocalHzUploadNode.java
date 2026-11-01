package com.powerjob.archive.core.node;

import com.powerjob.archive.core.config.MvpProperties;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeHandler;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.repository.OutputPackageRepository;
import com.powerjob.archive.core.service.FileTransferService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class LocalHzUploadNode extends AbstractOutputNode implements NodeHandler {
    private final MvpProperties properties;
    private final OutputPackageRepository outputs;

    public LocalHzUploadNode(FileTransferService files, JdbcTemplate jdbc,
                             MvpProperties properties, OutputPackageRepository outputs) {
        super(files, jdbc);
        this.properties = properties;
        this.outputs = outputs;
    }

    @Override
    public String type() { return "local.hz-upload"; }

    @Override
    public NodeResult execute(NodeContext context) throws Exception {
        Path target = files.copyVerified(artifactPath(context.output()), Path.of(properties.getHzRoot()),
                relativePath(context.output()), sha(context.output()));
        outputs.updatePath(context.output().id(), "hz_path", target.toString());
        outputs.updateArtifactPath(context.output().id(), "hz_path", target.toString());
        return NodeResult.of("sgVerify", "HZ_UPLOADED", context.output().manifest());
    }
}
