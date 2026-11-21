package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

@Component
public class PipelineNodeConfig {
    private static final Path SCRIPT_ROOT = Path.of("/app/nodes/python");
    private final ObjectMapper mapper;

    public PipelineNodeConfig(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public OssNodeConfig oss(NodeDefinition node, boolean source) {
        OssNodeConfig config = mapper.convertValue(node.config(), OssNodeConfig.class);
        config.validate(source);
        return config;
    }

    public FtpNodeConfig ftp(NodeDefinition node) {
        // 删除策略必须显式为布尔值，且日期必须是直属目录，发布时阻止宽泛路径清理。
        Object cleanup = node.config().get("cleanupOtherDeliveryDates");
        if (cleanup != null && !(cleanup instanceof Boolean)) {
            throw new IllegalArgumentException("FTP cleanupOtherDeliveryDates must be boolean");
        }
        FtpNodeConfig config = mapper.convertValue(node.config(), FtpNodeConfig.class);
        config.validate();
        if (config.cleanupOtherDeliveryDates() && !"{deliveryDate}".equals(node.config().get("remotePathTemplate"))) {
            throw new IllegalArgumentException("FTP date cleanup requires the direct {deliveryDate} path template");
        }
        return config;
    }

    /** Pipeline 只选择固定挂载目录内的脚本，不感知宿主机或容器路径。 */
    public String script(NodeDefinition node) {
        Object configured = node.config().get("scriptPath");
        if (!(configured instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(node.type() + " config.scriptPath is required");
        }
        try {
            // 相对路径经固定根目录解析并阻止目录穿越，最终 argv 不经过 Shell。
            Path path = Path.of(value);
            Path resolved = SCRIPT_ROOT.resolve(path).normalize();
            if (path.isAbsolute() || !path.normalize().equals(path) || !resolved.startsWith(SCRIPT_ROOT)
                    || !path.getFileName().toString().endsWith(".py")) {
                throw new IllegalArgumentException(node.type()
                        + " config.scriptPath must be a normalized relative .py path below the Worker script directory");
            }
            return resolved.toString();
        } catch (InvalidPathException error) {
            throw new IllegalArgumentException(node.type() + " config.scriptPath is invalid", error);
        }
    }
}
