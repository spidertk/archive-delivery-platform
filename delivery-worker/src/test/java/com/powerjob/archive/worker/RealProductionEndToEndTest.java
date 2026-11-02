package com.powerjob.archive.worker;

import com.aliyun.oss.OSS;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.pipeline.FtpNodeConfig;
import com.powerjob.archive.core.pipeline.OssNodeConfig;
import com.powerjob.archive.core.pipeline.PipelineDefinition;
import com.powerjob.archive.core.pipeline.PipelineNodeConfig;
import com.powerjob.archive.core.pipeline.PipelineRepository;
import com.powerjob.archive.core.service.OssClientFactory;
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
        String definitionJson = jdbc.queryForObject("SELECT pv.definition::text FROM delivery_run r " +
                "JOIN pipeline_version pv ON pv.id=r.pipeline_version_id WHERE r.id=?", String.class, runId);
        ObjectMapper mapper = new ObjectMapper();
        PipelineDefinition definition = new PipelineRepository(mapper).read(mapper.readTree(definitionJson));
        PipelineNodeConfig configs = new PipelineNodeConfig(mapper);
        OssNodeConfig hzConfig = configs.oss(definition.node("hzUpload"), false);
        OssNodeConfig sgConfig = configs.oss(definition.node("sgVerify"), false);
        FtpNodeConfig ftp = configs.ftp(definition.node("publish"));
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

        OssClientFactory clients = new OssClientFactory();
        OSS hz = clients.create(hzConfig);
        OSS sg = clients.create(sgConfig);
        RemotePathService paths = new RemotePathService(jdbc);
        SshCommandRunner ssh = new SshCommandRunner();
        try {
            for (var output : packages) {
                long id = ((Number) output.get("id")).longValue();
                String key = paths.objectKey(id);
                String expectedSha = output.get("sha256").toString();
                assertThat(output.get("hz_path")).isEqualTo("oss://" + hzConfig.bucket() + "/" + key);
                assertThat(output.get("sg_source_path")).isEqualTo("oss://" + sgConfig.bucket() + "/" + key);
                var hzMetadata = hz.getObjectMetadata(hzConfig.bucket(), key);
                var sgMetadata = sg.getObjectMetadata(sgConfig.bucket(), key);
                assertThat(hzMetadata.getContentLength()).isEqualTo(((Number) output.get("size_bytes")).longValue());
                assertThat(sgMetadata.getContentLength()).isEqualTo(((Number) output.get("size_bytes")).longValue());
                assertThat(hzMetadata.getUserMetadata().get("sha256")).isEqualTo(expectedSha);
                assertThat(sgMetadata.getUserMetadata().get("sha256")).isEqualTo(expectedSha);
                FtpNodeConfig.Line line = ftp.line(((Number) output.get("production_line_id")).longValue());
                String finalDir = line.remoteRoot() + "/" + paths.ftpDirectory(id);
                String packageName = output.get("new_package_name").toString();
                assertThat(output.get("published_path")).isEqualTo(finalDir + "/" + packageName);
                ssh.run(line, line.publishScript(), List.of(
                        output.get("sg_download_path").toString().substring(0,
                                output.get("sg_download_path").toString().lastIndexOf('/')),
                        packageName, finalDir, expectedSha), Duration.ofSeconds(60));
            }
        } finally {
            hz.shutdown();
            sg.shutdown();
        }
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
