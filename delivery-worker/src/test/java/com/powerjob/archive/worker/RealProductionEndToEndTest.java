package com.powerjob.archive.worker;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.powerjob.archive.core.config.FtpDeliveryProperties;
import com.powerjob.archive.core.config.OssDeliveryProperties;
import com.powerjob.archive.core.service.RemotePathService;
import com.powerjob.archive.core.service.SshCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ARCHIVE_REAL_E2E", matches = "true")
class RealProductionEndToEndTest {
    @Test
    void verifiesTenDeliveredPackagesAcrossRealHzSgAndFtp() throws Exception {
        long runId = Long.parseLong(required("ARCHIVE_VERIFY_RUN_ID"));
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("ARCHIVE_DB_URL", "jdbc:postgresql://host.docker.internal:55432/archive_delivery?currentSchema=archive_delivery"),
                env("ARCHIVE_DB_USER", "archive"), env("ARCHIVE_DB_PASSWORD", "archive_local"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<Map<String, Object>> packages = jdbc.queryForList("SELECT p.id,p.status,p.delivery_date,p.new_package_name," +
                "p.hz_path,p.sg_source_path,p.sg_download_path,p.published_path,p.production_line_id," +
                "s.run_id,r.job_date,a.sha256,a.size_bytes FROM output_package p JOIN source_package s ON s.id=p.source_package_id " +
                "JOIN delivery_run r ON r.id=s.run_id JOIN output_artifact a ON a.output_package_id=p.id AND a.artifact_seq=1 " +
                "WHERE s.run_id=? ORDER BY p.production_line_id,p.id", runId);
        assertThat(packages).hasSize(10);
        assertThat(packages).allMatch(p -> p.get("status").equals("DELIVERED"));
        assertThat(packages).allMatch(p -> p.get("delivery_date").equals(p.get("job_date")));
        assertThat(packages.stream().filter(p -> ((Number) p.get("production_line_id")).longValue() == 1)).hasSize(4);
        assertThat(packages.stream().filter(p -> ((Number) p.get("production_line_id")).longValue() == 2)).hasSize(3);
        assertThat(packages.stream().filter(p -> ((Number) p.get("production_line_id")).longValue() == 3)).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM (SELECT rm.new_request_id FROM request_mapping rm " +
                "JOIN source_member sm ON sm.id=rm.source_member_id JOIN source_package s ON s.id=sm.source_package_id " +
                "WHERE s.run_id=? GROUP BY rm.new_request_id HAVING count(*)>1) duplicates", Integer.class, runId)).isZero();

        OssDeliveryProperties oss = ossProperties();
        RemotePathService paths = new RemotePathService(jdbc);
        OSS hz = client(oss.getHz());
        OSS sg = client(oss.getSg());
        FtpDeliveryProperties ftp = ftpProperties();
        SshCommandRunner ssh = new SshCommandRunner(ftp);
        try {
            for (var output : packages) {
                long id = ((Number) output.get("id")).longValue();
                String key = paths.objectKey(id);
                String expectedSha = output.get("sha256").toString();
                String hzPath = "oss://" + oss.getHz().getBucket() + "/" + key;
                String sgPath = "oss://" + oss.getSg().getBucket() + "/" + key;
                assertThat(output.get("hz_path")).isEqualTo(hzPath);
                assertThat(output.get("sg_source_path")).isEqualTo(sgPath);
                var hzMetadata = hz.getObjectMetadata(oss.getHz().getBucket(), key);
                var sgMetadata = sg.getObjectMetadata(oss.getSg().getBucket(), key);
                assertThat(hzMetadata.getContentLength()).isEqualTo(((Number) output.get("size_bytes")).longValue());
                assertThat(sgMetadata.getContentLength()).isEqualTo(((Number) output.get("size_bytes")).longValue());
                assertThat(hzMetadata.getUserMetadata().get("sha256")).isEqualTo(expectedSha);
                assertThat(sgMetadata.getUserMetadata().get("sha256")).isEqualTo(expectedSha);
                long lineId = ((Number) output.get("production_line_id")).longValue();
                String finalDir = ftp.line(lineId).getRemoteRoot() + "/" + paths.ftpDirectory(id);
                String packageName = output.get("new_package_name").toString();
                assertThat(output.get("published_path")).isEqualTo(finalDir + "/" + packageName);
                ssh.run(lineId, ftp.line(lineId).getPublishScript(), List.of(
                        output.get("sg_download_path").toString().substring(0,
                                output.get("sg_download_path").toString().lastIndexOf('/')),
                        packageName, finalDir, expectedSha), Duration.ofSeconds(60));
            }
        } finally {
            hz.shutdown();
            sg.shutdown();
        }
    }

    private static OssDeliveryProperties ossProperties() {
        OssDeliveryProperties properties = new OssDeliveryProperties();
        fill(properties.getSource(), "OSS_SOURCE");
        fill(properties.getHz(), "OSS_HZ");
        fill(properties.getSg(), "OSS_SG");
        return properties;
    }

    private static void fill(OssDeliveryProperties.Endpoint endpoint, String prefix) {
        endpoint.setEndpoint(required(prefix + "_ENDPOINT"));
        endpoint.setBucket(required(prefix + "_BUCKET"));
        endpoint.setAccessKeyId(required(prefix + "_ACCESS_KEY_ID"));
        endpoint.setAccessKeySecret(required(prefix + "_ACCESS_KEY_SECRET"));
    }

    private static OSS client(OssDeliveryProperties.Endpoint endpoint) {
        return new OSSClientBuilder().build(endpoint.getEndpoint(), endpoint.getAccessKeyId(), endpoint.getAccessKeySecret());
    }

    private static FtpDeliveryProperties ftpProperties() {
        FtpDeliveryProperties properties = new FtpDeliveryProperties();
        for (long line = 1; line <= 3; line++) {
            String prefix = "FTP_LINE_" + line + "_";
            FtpDeliveryProperties.Line config = new FtpDeliveryProperties.Line();
            config.setHost(required(prefix + "SSH_HOST"));
            config.setUser(required(prefix + "SSH_USER"));
            config.setKey(required(prefix + "SSH_KEY"));
            config.setJumpHost(System.getenv(prefix + "JUMP_HOST"));
            config.setJumpUser(System.getenv(prefix + "JUMP_USER"));
            config.setJumpKey(System.getenv(prefix + "JUMP_KEY"));
            properties.getLines().put(line, config);
        }
        return properties;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
