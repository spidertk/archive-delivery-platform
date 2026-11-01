package com.powerjob.archive.core.service;

import com.powerjob.archive.core.domain.DeliveryRun;
import com.powerjob.archive.core.repository.DeliveryRunRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.List;

@Service
public class RunSubmissionService {
    private static final DateTimeFormatter JOB_DATE = DateTimeFormatter.ofPattern("uuuuMMdd")
            .withResolverStyle(ResolverStyle.STRICT);
    private final DeliveryRunRepository runs;
    private final JdbcTemplate jdbc;

    public RunSubmissionService(DeliveryRunRepository runs, JdbcTemplate jdbc) {
        this.runs = runs;
        this.jdbc = jdbc;
    }

    public Submission submit(String ossUri, String idempotencyKey, String jobDate) {
        OssLocation location = parseOssUri(ossUri);
        LocalDate parsedDate = jobDate == null || jobDate.isBlank()
                ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : parseJobDate(jobDate);
        runs.findByIdempotencyKey(idempotencyKey).ifPresent(existing -> verifySameRequest(existing, ossUri, parsedDate));
        Binding binding = currentBinding(location);
        long runId = runs.create(ossUri, idempotencyKey, parsedDate, binding.pipelineVersionId());
        DeliveryRun run = runs.findById(runId).orElseThrow();
        verifySameRequest(run, ossUri, parsedDate);
        return new Submission(run.id(), run.status(), run.jobDate().toString(), binding.versionNo());
    }

    private Binding currentBinding(OssLocation location) {
        List<Binding> bindings = jdbc.query("SELECT pv.id,pv.version_no FROM ingest_source s " +
                        "JOIN source_pipeline_binding b ON b.source_id=s.id AND b.effective_to IS NULL " +
                        "JOIN pipeline_version pv ON pv.id=b.pipeline_version_id " +
                        "WHERE s.enabled=true AND s.bucket_ref=? AND s.object_prefix=? AND pv.status='PUBLISHED' " +
                        "ORDER BY b.effective_from DESC LIMIT 1",
                (rs, rowNum) -> new Binding(rs.getLong("id"), rs.getInt("version_no")),
                location.bucket(), location.prefix());
        if (bindings.isEmpty()) {
            throw new IllegalArgumentException("No enabled Pipeline binding for OSS source");
        }
        return bindings.get(0);
    }

    private static void verifySameRequest(DeliveryRun existing, String ossUri, LocalDate jobDate) {
        if (!existing.ossUri().equals(ossUri) || !existing.jobDate().equals(jobDate)) {
            throw new IdempotencyConflictException("idempotencyKey is already used by different run parameters");
        }
    }

    static OssLocation parseOssUri(String value) {
        try {
            URI uri = URI.create(value);
            if (!"oss".equals(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("ossUri must be oss://bucket/prefix/");
            }
            String prefix = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
            if (List.of(prefix.split("/", -1)).contains("..")) {
                throw new IllegalArgumentException("ossUri prefix must not contain ..");
            }
            return new OssLocation(uri.getHost(), prefix);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid ossUri", e);
        }
    }

    private static LocalDate parseJobDate(String value) {
        try {
            return LocalDate.parse(value, JOB_DATE);
        } catch (Exception e) {
            throw new IllegalArgumentException("jobDate must use yyyyMMdd");
        }
    }

    public record Submission(long runId, String status, String jobDate, int pipelineVersion) {}
    record OssLocation(String bucket, String prefix) {}
    private record Binding(long pipelineVersionId, int versionNo) {}
}
