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
public class LocalSgDownloadNode extends AbstractOutputNode implements NodeHandler {
    private final MvpProperties properties;
    private final OutputPackageRepository outputs;

    public LocalSgDownloadNode(FileTransferService files, JdbcTemplate jdbc,
                               MvpProperties properties, OutputPackageRepository outputs) {
        super(files, jdbc);
        this.properties = properties;
        this.outputs = outputs;
    }

    @Override
    public String type() { return "local.sg-download"; }

    @Override
    public NodeResult execute(NodeContext context) throws Exception {
        Path sg = Path.of(properties.getSgRoot()).resolve(relativePath(context.output()));
        Path target = files.copyVerified(sg, Path.of(properties.getFtpRoot(), ".staging"),
                relativePath(context.output()), sha(context.output()));
        outputs.updatePath(context.output().id(), "sg_download_path", target.toString());
        return NodeResult.of("publish", "SG_DOWNLOADED", context.output().manifest());
    }
}
