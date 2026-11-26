package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineNodeConfigTest {
    private final PipelineNodeConfig configs = new PipelineNodeConfig(new ObjectMapper());

    @Test void sourceLocationCannotBePinnedByPipeline() {
        NodeDefinition source = new NodeDefinition("source", "oss.download", "ACQUIRE", "source", null,
                Map.of("endpoint", "https://oss.example", "bucket", "bucket", "prefix", "prefix/",
                        "accessKeyId", "access", "accessKeySecret", "secret"));
        assertThrows(IllegalArgumentException.class, () -> configs.oss(source, true));
        NodeDefinition masked = new NodeDefinition("source", "oss.download", "ACQUIRE", "source", null,
                Map.of("endpoint", "https://oss.example", "accessKeyId", "********", "accessKeySecret", "secret"));
        assertThrows(IllegalArgumentException.class, () -> configs.oss(masked, true));
    }

    @Test void placeholderFtpCredentialsCannotBePublished() {
        NodeDefinition node = new NodeDefinition("ftp", "ftp.download-verify", "VERIFY", "ftp", null,
                Map.of("deliveryEntityFrom", "TASK", "ftpCredential", Map.of("entity1", "replace-password")));
        assertThrows(IllegalArgumentException.class, () -> configs.ftp(node));
    }

    @Test void sharedFtpNameRequiresAnExplicitModeAndIndependentOssNamespaces() {
        // 跨实体同序号与跨日编号重置都可产生同名文件，发布时必须拒绝缺少隔离的模板。
        var fields = new java.util.HashMap<String, Object>(Map.of("endpoint", "https://oss.example", "bucket", "bucket",
                "accessKeyId", "access", "accessKeySecret", "secret"));
        for (String prefix : new String[]{"{providerCode}/{entityCode}", "{deliveryDate}/{entityCode}",
                "{deliveryDate}/{providerCode}/{entityCode}-{accountName}"}) {
            fields.put("prefix", prefix);
            fields.put("useFtpFileName", true);
            NodeDefinition unsafe = new NodeDefinition("target", "oss.upload", "TRANSFER", "target", null, fields);
            assertThrows(IllegalArgumentException.class, () -> configs.oss(unsafe, false));
        }
        fields.put("prefix", "{deliveryDate}/{providerCode}/{entityCode}");
        NodeDefinition target = new NodeDefinition("target", "oss.upload", "TRANSFER", "target", null, fields);
        assertTrue(configs.oss(target, false).useFtpFileName());
        fields.put("useFtpFileName", "true");
        assertThrows(IllegalArgumentException.class, () -> configs.oss(target, false));
        fields.remove("useFtpFileName");
        assertFalse(configs.oss(target, false).useFtpFileName());
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

    @Test void ftpCleanupRequiresExplicitBooleanAndDirectDatePath() {
        // 凭据合法也不能放宽删除范围；只有直属日期目录允许启用保留策略。
        for (Object cleanup : new Object[]{"true", Boolean.TRUE}) {
            NodeDefinition unsafe = new NodeDefinition("ftp", "ftp.download-verify", "VERIFY", "ftp", null,
                    Map.of("deliveryEntityFrom", "TASK", "ftpCredential", Map.of("root", "test-secret"),
                            "cleanupOtherDeliveryDates", cleanup, "remotePathTemplate", "{deliveryId}/{deliveryDate}"));
            assertThrows(IllegalArgumentException.class, () -> configs.ftp(unsafe));
        }
        NodeDefinition valid = new NodeDefinition("ftp", "ftp.download-verify", "VERIFY", "ftp", null,
                Map.of("deliveryEntityFrom", "TASK", "ftpCredential", Map.of("root", "test-secret"),
                        "cleanupOtherDeliveryDates", true, "remotePathTemplate", "{deliveryDate}"));
        org.junit.jupiter.api.Assertions.assertTrue(configs.ftp(valid).cleanupOtherDeliveryDates());
    }
}
