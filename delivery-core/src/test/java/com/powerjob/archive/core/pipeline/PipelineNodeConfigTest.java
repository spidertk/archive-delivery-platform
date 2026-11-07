package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PipelineNodeConfigTest {
    private final PipelineNodeConfig configs = new PipelineNodeConfig(new ObjectMapper());

    @Test void maskedOssCredentialsCannotBePublished() {
        NodeDefinition node = new NodeDefinition("source", "oss.download", "ACQUIRE", "source", null,
                Map.of("endpoint", "https://oss.example", "bucket", "bucket", "prefix", "prefix/",
                        "accessKeyId", "********", "accessKeySecret", "secret"));
        assertThrows(IllegalArgumentException.class, () -> configs.oss(node, true));
    }

    @Test void placeholderFtpCredentialsCannotBePublished() {
        NodeDefinition node = new NodeDefinition("ftp", "ftp.download-verify", "VERIFY", "ftp", null,
                Map.of("deliveryEntityFrom", "TASK", "ftpCredential", Map.of("entity1", "replace-password")));
        assertThrows(IllegalArgumentException.class, () -> configs.ftp(node));
    }
}
