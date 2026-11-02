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
        assertThat(jdbc.queryForObject("SELECT count(*) FROM source_package s JOIN delivery_run r ON r.id=s.run_id " +
                "WHERE s.run_id=? AND s.status='REPAIRED' AND s.process_date<>r.job_date",
                Integer.class, runId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM output_package p JOIN source_package s ON s.id=p.source_package_id " +
                "WHERE s.run_id=? AND (p.current_node<>'receipt' OR p.archive_date<>p.delivery_date)",
                Integer.class, runId)).isZero();
        for (String node : List.of("hzUpload", "sgVerify", "sgDownload", "publish", "receipt")) {
            assertThat(jdbc.queryForObject("SELECT count(DISTINCT p.id) FROM output_package p " +
                    "JOIN source_package s ON s.id=p.source_package_id JOIN package_node_run n ON n.output_package_id=p.id " +
                    "WHERE s.run_id=? AND n.node_id=? AND n.status='SUCCEEDED'", Integer.class, runId, node)).isEqualTo(10);
        }
        int crrWaitSeconds = definition.intConfig("crrWaitSeconds");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM output_package p JOIN source_package s ON s.id=p.source_package_id " +
                "WHERE s.run_id=? AND (SELECT min(n.started_at) FROM package_node_run n " +
                "WHERE n.output_package_id=p.id AND n.node_id='sgVerify' AND n.attempt>1) " +
                "< p.hz_uploaded_at + (? * interval '1 second')",
                Integer.class, runId, crrWaitSeconds)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM package_node_run a JOIN package_node_run b ON b.id>a.id " +
                "JOIN source_package sa ON sa.id=a.source_package_id JOIN source_package sb ON sb.id=b.source_package_id " +
                "WHERE sa.run_id=? AND sb.run_id=? AND sa.production_line_id=sb.production_line_id " +
                "AND a.started_at < b.finished_at AND b.started_at < a.finished_at", Integer.class, runId, runId)).isZero();
        assertThat(jdbc.queryForObject("WITH node_run AS (SELECT n.* FROM package_node_run n " +
                "JOIN output_package p ON p.id=n.output_package_id JOIN source_package s ON s.id=p.source_package_id " +
                "WHERE s.run_id=?), events AS (SELECT started_at AS at,1 AS delta FROM node_run UNION ALL " +
                "SELECT finished_at,-1 FROM node_run), slots AS (SELECT at,sum(delta) OVER (ORDER BY at,delta DESC) " +
                "AS concurrent FROM events) SELECT coalesce(max(concurrent),0) FROM slots", Integer.class, runId))
                .isLessThanOrEqualTo(10);
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
