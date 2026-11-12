package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test void pythonScriptsResolveBelowTheFixedMountedDirectory() {
        NodeDefinition local = new NodeDefinition("repair", "python.repair", "TRANSFORM", "repair", null,
                Map.of("scriptPath", "archive_repair.py"));
        assertEquals("/app/nodes/python/archive_repair.py", configs.script(local));
        NodeDefinition versioned = new NodeDefinition("repair", "python.repair", "TRANSFORM", "repair", null,
                Map.of("scriptPath", "versions/archive_repair_v2.py"));
        assertEquals("/app/nodes/python/versions/archive_repair_v2.py", configs.script(versioned));
        for (String invalid : new String[]{"/workspace/nodes/python/archive_repair.py", "../repair.py",
                "nodes/../repair.py", "archive_repair.sh"}) {
            NodeDefinition node = new NodeDefinition("repair", "python.repair", "TRANSFORM", "repair", null,
                    Map.of("scriptPath", invalid));
            assertThrows(IllegalArgumentException.class, () -> configs.script(node));
        }
    }
}
