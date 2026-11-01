package com.powerjob.archive.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RepairGoldenEndToEndTest {
    private static final Path PROJECT = Path.of(System.getProperty("user.dir")).getParent();

    @Test
    void javaInvokesLegacyRulesAndReusesStableNumbers() throws Exception {
        Path sample = System.getenv("ARCHIVE_TEST_SAMPLE") == null
                ? PROJECT.getParent().resolve("old/sample/batch_00002.tar.gz")
                : Path.of(System.getenv("ARCHIVE_TEST_SAMPLE"));
        Path temp = Files.createTempDirectory("archive-repair-golden");
        JsonNode first = repair(sample, temp.resolve("first"));
        JsonNode second = repair(sample, temp.resolve("second"));
        assertThat(first.required("mappings")).isEqualTo(second.required("mappings"));
        assertThat(first.required("artifacts").get(0).required("sha256"))
                .isEqualTo(second.required("artifacts").get(0).required("sha256"));
        Path artifact = Path.of(first.required("artifacts").get(0).required("path").asText());
        assertThat(Files.size(artifact)).isLessThanOrEqualTo(100L * 1024 * 1024);
        assertThat(artifact.getFileName().toString()).isEqualTo(sample.getFileName().toString());
    }

    private static JsonNode repair(Path sample, Path target) throws Exception {
        Path manifest = target.resolve("manifest.json");
        Process process = new ProcessBuilder("python3",
                PROJECT.resolve("nodes/python/archive_repair.py").toString(),
                "--source", sample.toString(), "--target", target.toString(),
                "--archive-date", "2026-11-01", "--provider-code", "june",
                "--start-number", "41", "--manifest", manifest.toString()).start();
        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());
        assertThat(process.waitFor()).as(stderr).isZero();
        JsonNode result = new ObjectMapper().readTree(stdout);
        assertThat(result.required("requestCount").asInt()).isEqualTo(4);
        assertThat(result.required("mappings").get(0).required("newRequestId").asText())
                .isEqualTo("req_20261101_000000041");
        return result;
    }
}
