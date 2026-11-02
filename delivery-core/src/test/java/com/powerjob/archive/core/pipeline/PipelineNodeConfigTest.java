package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineNodeConfigTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PipelineNodeConfig configs = new PipelineNodeConfig(mapper);

    @Test
    void readsOssAndFtpFromTheirOwnNodes() throws Exception {
        NodeDefinition ossNode = node("hzUpload", "oss.hz-upload", """
                {"endpoint":"https://oss-cn-hangzhou.aliyuncs.com","bucket":"hz-bucket",
                "accessKeyId":"id","accessKeySecret":"secret"}
                """);
        OssNodeConfig oss = configs.oss(ossNode, false);
        assertThat(oss.bucket()).isEqualTo("hz-bucket");
        assertThat(oss.prefix()).isNull();

        NodeDefinition ftpNode = node("publish", "ssh.publish", ftpJson("sg-1"));
        assertThat(configs.ftp(ftpNode).line(1).host()).isEqualTo("sg-1");
    }

    @Test
    void rejectsMissingSecretsAndUnsafeSourcePrefix() throws Exception {
        NodeDefinition badOss = node("hzUpload", "oss.hz-upload", """
                {"endpoint":"","bucket":"oss://bad","accessKeyId":"","accessKeySecret":""}
                """);
        assertThatThrownBy(() -> configs.oss(badOss, false)).isInstanceOf(IllegalArgumentException.class);

        NodeDefinition badPrefix = node("download", "archive.download", """
                {"endpoint":"https://oss-cn-hangzhou.aliyuncs.com","bucket":"source","prefix":"a/../b/",
                "accessKeyId":"id","accessKeySecret":"secret"}
                """);
        assertThatThrownBy(() -> configs.oss(badPrefix, true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesSshHostPublicKeys() {
        String valid = "sg-1 ssh-ed25519 AAAA";
        assertThat(FtpNodeConfig.validateHostKey(valid)).isSameAs(valid);
        for (String invalid : new String[]{null, "", "sg-1 ssh-ed25519", "sg-1 not-a-key AAAA",
                "sg-1 ssh-ed25519 AAAA\nother ssh-ed25519 AAAA"}) {
            assertThatThrownBy(() -> FtpNodeConfig.validateHostKey(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void requiresAllThreeFtpLines() throws Exception {
        NodeDefinition ftpNode = node("publish", "ssh.publish", """
                {"lines":{"1":{"host":"sg-1","user":"root","key":"/run/secrets/line-1-id_ed25519",
                "hostKey":"sg-1 ssh-ed25519 AAAA","remoteRoot":"/srv/maas-sftp/maasData/archive",
                "downloadScript":"/opt/archive-delivery/bin/sg_download.sh",
                "publishScript":"/opt/archive-delivery/bin/publish_package.sh"}}}
                """);
        assertThatThrownBy(() -> configs.ftp(ftpNode)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FTP line 2");
    }

    private NodeDefinition node(String id, String type, String json) throws Exception {
        return new NodeDefinition(id, "OUTPUT", type,
                mapper.convertValue(mapper.readTree(json), new TypeReference<Map<String, Object>>() {}));
    }

    private static String ftpJson(String firstHost) {
        StringBuilder json = new StringBuilder("{\"lines\":{");
        for (int line = 1; line <= 3; line++) {
            if (line > 1) json.append(',');
            json.append('"').append(line).append("\":{")
                    .append("\"host\":\"").append(line == 1 ? firstHost : "sg-" + line).append("\",")
                    .append("\"user\":\"root\",\"key\":\"/run/secrets/line-").append(line).append("-id_ed25519\",")
                    .append("\"hostKey\":\"sg-").append(line).append(" ssh-ed25519 AAAA\",")
                    .append("\"remoteRoot\":\"/srv/maas-sftp/maasData/archive\",")
                    .append("\"downloadScript\":\"/opt/archive-delivery/bin/sg_download.sh\",")
                    .append("\"publishScript\":\"/opt/archive-delivery/bin/publish_package.sh\"}");
        }
        return json.append("}}").toString();
    }
}
