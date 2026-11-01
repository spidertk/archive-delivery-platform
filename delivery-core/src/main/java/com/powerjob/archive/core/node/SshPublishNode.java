package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.powerjob.archive.core.config.FtpDeliveryProperties;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeHandler;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.repository.OutputPackageRepository;
import com.powerjob.archive.core.service.RemotePathService;
import com.powerjob.archive.core.service.SshCommandRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class SshPublishNode implements NodeHandler {
    private final SshCommandRunner ssh;
    private final FtpDeliveryProperties ftp;
    private final RemotePathService paths;
    private final OutputPackageRepository outputs;

    public SshPublishNode(SshCommandRunner ssh, FtpDeliveryProperties ftp,
                          RemotePathService paths, OutputPackageRepository outputs) {
        this.ssh = ssh;
        this.ftp = ftp;
        this.paths = paths;
        this.outputs = outputs;
    }

    @Override
    public String type() { return "ssh.publish"; }

    @Override
    public NodeResult execute(NodeContext context) throws Exception {
        long outputId = context.output().id();
        FtpDeliveryProperties.Line line = ftp.line(context.output().productionLineId());
        String finalName = context.output().newPackageName();
        RemotePathService.validatePackageName(finalName);
        String stagingPath = context.output().manifest().required("sgDownloadPath").asText();
        String stagingDir = stagingPath.substring(0, stagingPath.length() - finalName.length() - 1);
        String finalDir = line.getRemoteRoot() + "/" + paths.ftpDirectory(outputId);
        String sha = context.output().manifest().required("artifacts").required(0).required("sha256").asText();
        int timeout = ((Number) context.node().config().getOrDefault("commandTimeoutSeconds", 60)).intValue();
        ssh.run(context.output().productionLineId(), line.getPublishScript(),
                List.of(stagingDir, finalName, finalDir, sha), Duration.ofSeconds(timeout));
        String publishedPath = finalDir + "/" + finalName;
        outputs.updatePath(outputId, "published_path", publishedPath);
        outputs.updateArtifactPath(outputId, "published_path", publishedPath);
        JsonNode manifest = context.output().manifest().deepCopy();
        ((ObjectNode) manifest).put("publishedPath", publishedPath);
        return NodeResult.of("receipt", "PUBLISHED", manifest);
    }
}
