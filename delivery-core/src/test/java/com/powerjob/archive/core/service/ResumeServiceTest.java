package com.powerjob.archive.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.domain.DiscoveredObject;
import com.powerjob.archive.core.repository.DeliveryRunRepository;
import com.powerjob.archive.core.repository.OperationAuditRepository;
import com.powerjob.archive.core.repository.SourcePackageRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ResumeServiceTest {
    private static final String OSS = "oss://junflow-ysai-source-hz-20260906/_derived/ysai-sftp-20261027/delivery_plan_0005/";
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static ResumeService resumes;
    static long runId;
    static long sourceId;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + "&currentSchema=archive_delivery", POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).schemas("archive_delivery")
                .defaultSchema("archive_delivery").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ObjectMapper mapper = new ObjectMapper();
        DeliveryRunRepository runs = new DeliveryRunRepository(jdbc, transactions);
        SourcePackageRepository sources = new SourcePackageRepository(jdbc, transactions, mapper);
        resumes = new ResumeService(jdbc, transactions, new OperationAuditRepository(jdbc), mapper);
        runId = runs.create(OSS, "resume-test", LocalDate.of(2026, 10, 30), 2);
        sourceId = sources.upsertDiscovered(runId, new DiscoveredObject("junflow-ysai-source-hz-20260906",
                "_derived/ysai-sftp-20261027/delivery_plan_0005/batch_00001.tar.gz", "etag", "etag", 7, 0));
        jdbc.update("UPDATE source_package SET production_line_id=1,process_date='2026-10-30' WHERE id=?", sourceId);
    }

    @Test
    void verifiesPrerequisitesBeforeResumeAndRetry() {
        long sgVerify = output("batch_00001.tar.gz", "HZ_UPLOADED", "sgVerify", "hz_uploaded", true, true);
        assertThat(resumes.resume(runId, "sgVerify", List.of(sgVerify), "CRR completed")).isEqualTo(1);
        assertThat(runStatus(sgVerify)).isEqualTo("READY");

        long sgDownload = output("batch_00002.tar.gz", "SG_VERIFIED", "sgDownload", "sg_source", true, true);
        assertThat(resumes.resume(runId, "sgDownload", List.of(sgDownload), "continue FTP")).isEqualTo(1);

        long receipt = output("batch_00003.tar.gz", "PUBLISHED", "receipt", "published", true, true);
        assertThat(resumes.resume(runId, "receipt", List.of(receipt), "local receipt only")).isEqualTo(1);

        long invalid = output("batch_00004.tar.gz", "GROUPED", "hzUpload", null, false, false);
        assertThatThrownBy(() -> resumes.resume(runId, "hzUpload", List.of(invalid), "invalid"))
                .isInstanceOf(ResumeConflictException.class);
        assertThatThrownBy(() -> resumes.resume(runId, "hzUpload", List.of(sgVerify), "rollback"))
                .isInstanceOf(ResumeConflictException.class);

        long stale = output("batch_00005.tar.gz", "GROUPED", "hzUpload", null, true, false);
        jdbc.update("UPDATE output_package SET run_status='RUNNING',lease_until=now()-interval '1 minute' WHERE id=?", stale);
        assertThat(resumes.resume(runId, "hzUpload", List.of(stale), "expired worker lease")).isEqualTo(1);
        assertThat(runStatus(stale)).isEqualTo("READY");
        long active = output("batch_00006.tar.gz", "GROUPED", "hzUpload", null, true, false);
        jdbc.update("UPDATE output_package SET run_status='RUNNING',lease_until=now()+interval '5 minutes' WHERE id=?", active);
        assertThatThrownBy(() -> resumes.resume(runId, "hzUpload", List.of(active), "active worker"))
                .isInstanceOf(ResumeConflictException.class);

        jdbc.update("UPDATE output_package SET run_status='BLOCKED',fence=7 WHERE id=?", sgDownload);
        resumes.retry(sgDownload, 7, "network recovered");
        assertThat(runStatus(sgDownload)).isEqualTo("READY");
        assertThatThrownBy(() -> resumes.retry(sgDownload, 7, "stale fence"))
                .isInstanceOf(ResumeConflictException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operation_audit", Integer.class)).isEqualTo(5);
    }

    private static long output(String name, String status, String node, String pathKind,
                               boolean manifestArtifact, boolean path) {
        String sha = "a".repeat(64);
        String manifest = manifestArtifact
                ? "{\"artifacts\":[{\"path\":\"/tmp/" + name + "\",\"sha256\":\"" + sha + "\"}]}" : "{}";
        Long id = jdbc.queryForObject("INSERT INTO output_package(source_package_id,pipeline_version_id,production_group_id," +
                "production_line_id,archive_date,delivery_date,cutoff_at,group_seq,new_package_name,status,run_status,current_node," +
                "manifest,hz_path,sg_source_path,sg_download_path,published_path) VALUES (?,2,1,1,'2026-10-30','2026-10-30'," +
                "'2026-10-31 02:00+08',?,?,?,'BLOCKED',?,?::jsonb,?,?,?,?) RETURNING id", Long.class,
                sourceId, Integer.parseInt(name.substring(6, 11)), name, status, node, manifest,
                path && "hz_uploaded".equals(pathKind) ? "oss://hz/" + name : null,
                path && "sg_source".equals(pathKind) ? "oss://sg/" + name : null,
                path && "sg_download".equals(pathKind) ? "/staging/" + name : null,
                path && "published".equals(pathKind) ? "/final/" + name : null);
        jdbc.update("INSERT INTO output_artifact(output_package_id,artifact_seq,new_file_name,size_bytes,sha256) " +
                "VALUES (?,1,?,7,?)", id, name, sha);
        return id;
    }

    private static String runStatus(long id) {
        return jdbc.queryForObject("SELECT run_status FROM output_package WHERE id=?", String.class, id);
    }
}
