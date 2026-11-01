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
public class LocalSgVerifyNode extends AbstractOutputNode implements NodeHandler {
    private final MvpProperties properties;
    private final OutputPackageRepository outputs;

    public LocalSgVerifyNode(FileTransferService files, JdbcTemplate jdbc,
                             MvpProperties properties, OutputPackageRepository outputs) {
        super(files, jdbc);
        this.properties = properties;
        this.outputs = outputs;
    }

    @Override
    public String type() { return "local.sg-verify"; }

    @Override
    public NodeResult execute(NodeContext context) throws Exception {
        Path hz = Path.of(properties.getHzRoot()).resolve(relativePath(context.output()));
        Path target = files.copyVerified(hz, Path.of(properties.getSgRoot()),
                relativePath(context.output()), sha(context.output()));
        outputs.updatePath(context.output().id(), "sg_source_path", target.toString());
        outputs.updateArtifactPath(context.output().id(), "sg_path", target.toString());
        return NodeResult.of("sgDownload", "SG_VERIFIED", context.output().manifest());
    }
}
