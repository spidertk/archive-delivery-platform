package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.config.MvpProperties;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeHandler;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.service.NumberReservationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Component
public class PythonRepairNode implements NodeHandler {
    private final MvpProperties properties;
    private final NumberReservationService numbers;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PythonRepairNode(MvpProperties properties, NumberReservationService numbers,
                            ObjectMapper mapper, JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.properties = properties;
        this.numbers = numbers;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public String type() { return "python.repair"; }

    @Override
    public NodeResult execute(NodeContext context) throws Exception {
        Path source = Path.of(context.source().manifest().required("localPath").asText());
        JsonNode inspection = run("--source", source.toString(), "--inspect");
        int count = inspection.required("requestCount").asInt();
        String archiveDate = jdbc.queryForObject("SELECT COALESCE((SELECT r.job_date::text FROM delivery_run r " +
                "WHERE r.id=s.run_id),?) FROM source_package s WHERE s.id=?", String.class,
                properties.getArchiveDate(), context.source().id());
        String namespace = "customer-request:" + archiveDate.replace("-", "");
        NumberReservationService.NumberRange range = numbers.reserve(context.source().id(), count, namespace);
        Path target = Path.of(properties.getWorkRoot(), "source-" + context.source().id(), "repair");
        Path manifestPath = target.resolve("manifest.json");
        JsonNode manifest = run("--source", source.toString(), "--target", target.toString(),
                "--archive-date", archiveDate, "--provider-code", provider(context.source().productionLineId()),
                "--start-number", Long.toString(range.start()), "--manifest", manifestPath.toString());
        if (manifest.required("requestCount").asInt() != count) {
            throw new IllegalStateException("Repair request count changed between inspect and repair");
        }
        transactions.executeWithoutResult(status -> persistLineage(context.source().id(), range.reservationId(), manifest));
        return NodeResult.of("qualify", "REPAIRED", manifest);
    }

    private String provider(long lineId) {
        return jdbc.queryForObject("SELECT provider_code FROM production_line WHERE id=?", String.class, lineId);
    }

    private JsonNode run(String... arguments) throws Exception {
        String[] command = new String[arguments.length + 2];
        command[0] = properties.getPythonCommand();
        command[1] = properties.getPythonScript();
        System.arraycopy(arguments, 0, command, 2, arguments.length);
        Process process = new ProcessBuilder(command).start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException("Python repair timed out");
        }
        if (process.exitValue() != 0) throw new IllegalStateException("Python repair failed: " + stderr);
        return mapper.readTree(stdout);
    }

    private void persistLineage(long sourceId, long reservationId, JsonNode manifest) {
        for (JsonNode mapping : manifest.required("mappings")) {
            jdbc.update("INSERT INTO source_member(source_package_id,member_key,original_path,original_request_id," +
                    "qualification,rule_version,repaired_manifest) VALUES (?,?,?,?,?,'fix-archive-v1',?::jsonb) " +
                    "ON CONFLICT(source_package_id,member_key) DO UPDATE SET original_request_id=excluded.original_request_id," +
                    "qualification=excluded.qualification,repaired_manifest=excluded.repaired_manifest",
                    sourceId, mapping.required("memberKey").asText(), mapping.required("originalPath").asText(),
                    mapping.path("originalRequestId").isNull() ? null : mapping.path("originalRequestId").asText(),
                    mapping.required("qualification").asText(), mapping.toString());
            Long memberId = jdbc.queryForObject("SELECT id FROM source_member WHERE source_package_id=? AND member_key=?",
                    Long.class, sourceId, mapping.required("memberKey").asText());
            jdbc.update("INSERT INTO request_mapping(source_member_id,original_request_id,new_request_id," +
                    "reservation_id,mapping_rule_version) VALUES (?,?,?,?,'fix-archive-v1') " +
                    "ON CONFLICT(source_member_id) DO UPDATE SET new_request_id=excluded.new_request_id," +
                    "reservation_id=excluded.reservation_id", memberId,
                    mapping.path("originalRequestId").isNull() ? null : mapping.path("originalRequestId").asText(),
                    mapping.required("newRequestId").asText(), reservationId);
        }
    }
}
