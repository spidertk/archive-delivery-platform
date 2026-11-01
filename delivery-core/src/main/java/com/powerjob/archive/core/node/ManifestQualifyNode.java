package com.powerjob.archive.core.node;

import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeHandler;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.service.FileTransferService;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class ManifestQualifyNode implements NodeHandler {
    private final FileTransferService files;

    public ManifestQualifyNode(FileTransferService files) {
        this.files = files;
    }

    @Override
    public String type() { return "manifest.qualify"; }

    @Override
    public NodeResult execute(NodeContext context) throws Exception {
        var manifest = context.source().manifest();
        if (manifest.required("requestCount").asInt() <= 0 || manifest.required("mappings").size() == 0) {
            throw new IllegalStateException("No qualified requests in repair manifest");
        }
        var artifact = manifest.required("artifacts").required(0);
        Path path = Path.of(artifact.required("path").asText());
        if (!files.sha256(path).equals(artifact.required("sha256").asText())) {
            throw new IllegalStateException("Repair artifact checksum mismatch");
        }
        return NodeResult.of("group", "REPAIRED", manifest);
    }
}
