package com.powerjob.archive.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.repository.OperationAuditRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ResumeService {
    private static final Set<String> NODES = Set.of("hzUpload", "sgVerify", "sgDownload", "publish", "receipt");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final OperationAuditRepository audits;
    private final ObjectMapper mapper;

    public ResumeService(JdbcTemplate jdbc, TransactionTemplate transactions,
                         OperationAuditRepository audits, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.audits = audits;
        this.mapper = mapper;
    }

    public int resume(long runId, String fromNode, List<Long> packageIds, String reason) {
        if (!NODES.contains(fromNode)) throw new ResumeConflictException("Unsupported resume node");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason is required");
        String sql = "SELECT p.id,p.status,p.run_status,p.current_node,p.fence,p.manifest::text AS manifest," +
                "p.hz_path,p.sg_source_path,p.sg_download_path,p.published_path,s.run_id,a.sha256 " +
                "FROM output_package p JOIN source_package s ON s.id=p.source_package_id " +
                "JOIN output_artifact a ON a.output_package_id=p.id AND a.artifact_seq=1 WHERE s.run_id=?";
        if (packageIds == null || packageIds.isEmpty()) {
            sql += " AND p.current_node='" + fromNode + "'";
        } else {
            sql += " AND p.id IN (" + packageIds.stream().map(id -> "?").collect(Collectors.joining(",")) + ")";
        }
        Object[] args = packageIds == null || packageIds.isEmpty()
                ? new Object[]{runId} : prepend(runId, packageIds);
        List<Map<String, Object>> packages = jdbc.queryForList(sql, args);
        if (packages.isEmpty() || (packageIds != null && !packageIds.isEmpty() && packages.size() != packageIds.size())) {
            throw new ResumeConflictException("No resumable packages matched the run and node");
        }
        for (var output : packages) {
            transactions.executeWithoutResult(status -> resumeOne(runId, fromNode, output, reason));
        }
        return packages.size();
    }

    public void retry(long packageId, long expectedFence, String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason is required");
        transactions.executeWithoutResult(status -> {
            var output = jdbc.queryForMap("SELECT p.run_status,p.current_node,p.fence,s.run_id FROM output_package p " +
                    "JOIN source_package s ON s.id=p.source_package_id WHERE p.id=?", packageId);
            String runStatus = output.get("run_status").toString();
            long fence = ((Number) output.get("fence")).longValue();
            if (!Set.of("RETRY_WAIT", "BLOCKED").contains(runStatus) || fence != expectedFence) {
                throw new ResumeConflictException("Package fence or retry state does not match");
            }
            int changed = jdbc.update("UPDATE output_package SET run_status='READY',fence=fence+1,lease_owner=NULL," +
                    "lease_until=NULL,next_retry_at=NULL,last_error_code=NULL,updated_at=now() " +
                    "WHERE id=? AND fence=? AND run_status IN ('RETRY_WAIT','BLOCKED')", packageId, expectedFence);
            if (changed != 1) throw new ResumeConflictException("Package changed concurrently");
            long runId = ((Number) output.get("run_id")).longValue();
            audits.append(runId, packageId, "RETRY", reason, mapper.valueToTree(output),
                    mapper.createObjectNode().put("runStatus", "READY").put("fence", expectedFence + 1));
        });
    }

    private void resumeOne(long runId, String fromNode, Map<String, Object> output, String reason) {
        long packageId = ((Number) output.get("id")).longValue();
        long fence = ((Number) output.get("fence")).longValue();
        if (!fromNode.equals(output.get("current_node")) || "RUNNING".equals(output.get("run_status"))) {
            throw new ResumeConflictException("Package is not resumable from " + fromNode);
        }
        verifyPrerequisites(fromNode, output);
        int changed = jdbc.update("UPDATE output_package SET run_status='READY',fence=fence+1,lease_owner=NULL," +
                "lease_until=NULL,next_retry_at=NULL,last_error_code=NULL,updated_at=now() WHERE id=? AND fence=?",
                packageId, fence);
        if (changed != 1) throw new ResumeConflictException("Package changed concurrently");
        audits.append(runId, packageId, "RESUME", reason, mapper.valueToTree(output),
                mapper.createObjectNode().put("currentNode", fromNode).put("runStatus", "READY").put("fence", fence + 1));
    }

    private void verifyPrerequisites(String fromNode, Map<String, Object> output) {
        String status = output.get("status").toString();
        boolean valid = switch (fromNode) {
            case "hzUpload" -> status.equals("GROUPED") && hasArtifact(output);
            case "sgVerify" -> status.equals("HZ_UPLOADED") && present(output.get("hz_path")) && sameSha(output);
            case "sgDownload" -> status.equals("SG_VERIFIED") && present(output.get("sg_source_path"));
            case "publish" -> status.equals("SG_DOWNLOADED") && present(output.get("sg_download_path"));
            case "receipt" -> status.equals("PUBLISHED") && present(output.get("published_path"));
            default -> false;
        };
        if (!valid) throw new ResumeConflictException("Missing verified prerequisite for " + fromNode +
                " (status=" + status + ",shaMatches=" + sameSha(output) + ")");
    }

    private boolean hasArtifact(Map<String, Object> output) {
        try {
            JsonNode manifest = mapper.readTree(output.get("manifest").toString());
            JsonNode artifact = manifest.path("artifacts").path(0);
            return artifact.hasNonNull("path") && artifact.hasNonNull("sha256");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean sameSha(Map<String, Object> output) {
        try {
            JsonNode manifest = mapper.readTree(output.get("manifest").toString());
            return output.get("sha256").equals(manifest.required("artifacts").required(0).required("sha256").asText());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean present(Object value) {
        return value != null && !value.toString().isBlank();
    }

    private static Object[] prepend(long runId, List<Long> ids) {
        Object[] args = new Object[ids.size() + 1];
        args[0] = runId;
        for (int index = 0; index < ids.size(); index++) args[index + 1] = ids.get(index);
        return args;
    }
}
