package com.powerjob.archive.core.node;

import com.aliyun.oss.model.ObjectMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.powerjob.archive.core.domain.OutputPackage;
import com.powerjob.archive.core.pipeline.NodeContext;
import com.powerjob.archive.core.pipeline.NodeDefinition;
import com.powerjob.archive.core.pipeline.NodeResult;
import com.powerjob.archive.core.pipeline.PipelineDefinition;
import com.powerjob.archive.core.pipeline.PipelineNodeConfig;
import com.powerjob.archive.core.repository.OutputPackageRepository;
import com.powerjob.archive.core.service.CrrWaitTimeoutException;
import com.powerjob.archive.core.service.OssTransferService;
import com.powerjob.archive.core.service.RemoteConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OssSgVerifyNodeTest {
    private static final Instant T0 = Instant.parse("2026-10-30T00:00:00Z");

    @Test
    void waitsUntilOwnUploadPlus180Seconds() {
        Fixture fixture = new Fixture(T0.plusSeconds(179), T0, 180);
        NodeResult result = fixture.execute();
        assertThat(result.kind()).isEqualTo(NodeResult.ResultKind.WAITING);
        assertThat(result.nextPoll()).hasSeconds(1);
        verify(fixture.transfers, never()).headSg(org.mockito.ArgumentMatchers.any(), anyString());
        fixture = new Fixture(T0.plusSeconds(180), T0, 180);
        fixture.remote = Optional.of(metadata(7, "sha"));
        assertThat(fixture.execute().businessStatus()).isEqualTo("SG_VERIFIED");
    }

    @Test
    void packagesExpireIndependentlyAndSnapshotControlsWait() {
        Fixture first = new Fixture(T0.plusSeconds(180), T0, 180);
        first.remote = Optional.of(metadata(7, "sha"));
        assertThat(first.execute().kind()).isEqualTo(NodeResult.ResultKind.SUCCEEDED);
        Fixture second = new Fixture(T0.plusSeconds(180), T0.plusSeconds(60), 180);
        assertThat(second.execute().nextPoll()).hasSeconds(60);
        verify(first.transfers).headSg(org.mockito.ArgumentMatchers.any(), anyString());
        verify(second.transfers, never()).headSg(org.mockito.ArgumentMatchers.any(), anyString());

        Fixture immediate = new Fixture(T0, T0, 0);
        immediate.remote = Optional.of(metadata(7, "sha"));
        assertThat(immediate.execute().kind()).isEqualTo(NodeResult.ResultKind.SUCCEEDED);
        Fixture longWait = new Fixture(T0.plusSeconds(599), T0, 600);
        assertThat(longWait.execute().nextPoll()).hasSeconds(1);
    }

    @Test
    void absentObjectWaitsWithoutFailureAndTimesOutAtTwoHours() {
        Fixture waiting = new Fixture(T0.plusSeconds(180), T0, 180);
        NodeResult result = waiting.execute();
        assertThat(result.kind()).isEqualTo(NodeResult.ResultKind.WAITING);
        assertThat(result.nextPoll()).hasSeconds(30);
        Fixture timeout = new Fixture(T0.plusSeconds(7200), T0, 180);
        assertThatThrownBy(timeout::execute).isInstanceOf(CrrWaitTimeoutException.class)
                .hasMessage("CRR_WAIT_TIMEOUT");
    }

    @Test
    void differentShaIsConflict() {
        Fixture fixture = new Fixture(T0.plusSeconds(180), T0, 180);
        fixture.remote = Optional.of(metadata(7, "other"));
        assertThatThrownBy(fixture::execute).isInstanceOf(RemoteConflictException.class);
    }

    private static final class Fixture {
        final OssTransferService transfers = mock(OssTransferService.class);
        final OutputPackageRepository outputs = mock(OutputPackageRepository.class);
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final Clock clock;
        final int wait;
        Optional<ObjectMetadata> remote = Optional.empty();

        Fixture(Instant now, Instant uploaded, int wait) {
            this.clock = Clock.fixed(now, ZoneOffset.UTC);
            this.wait = wait;
            when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Timestamp.class), anyLong()))
                    .thenReturn(Timestamp.from(uploaded));
            when(transfers.headSg(org.mockito.ArgumentMatchers.any(), anyString())).thenAnswer(invocation -> remote);
            when(transfers.sgUri(org.mockito.ArgumentMatchers.any(), anyString()))
                    .thenReturn("oss://sg/june/date/batch_00001.tar.gz");
        }

        NodeResult execute() {
            NodeDefinition node = new NodeDefinition("sgVerify", "OUTPUT", "oss.sg-verify", Map.of(
                    "endpoint", "https://oss-ap-southeast-1.aliyuncs.com", "bucket", "sg-bucket",
                    "accessKeyId", "id", "accessKeySecret", "secret",
                    "pollSeconds", 30, "maxWaitSeconds", 7200));
            PipelineDefinition pipeline = new PipelineDefinition("archive-delivery", 3,
                    Map.of("crrWaitSeconds", wait), java.util.List.of());
            return new OssSgVerifyNode(transfers, outputs, jdbc, clock,
                    new PipelineNodeConfig(new ObjectMapper()))
                    .execute(new NodeContext(null, output(wait), node, pipeline, 1, 1, "worker"));
        }
    }

    private static OutputPackage output(int wait) {
        JsonNode manifest = new ObjectMapper().createObjectNode();
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest).put("hzPath", "oss://hz/june/date/batch_00001.tar.gz");
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest).putArray("artifacts")
                .addObject().put("path", "/tmp/artifact").put("name", "batch_00001.tar.gz")
                .put("bytes", 7).put("sha256", "sha");
        JsonNode snapshot = new ObjectMapper().createObjectNode();
        ((com.fasterxml.jackson.databind.node.ObjectNode) snapshot).putObject("config").put("crrWaitSeconds", wait);
        return new OutputPackage(10, 20, 2, snapshot, 1, 1, "2026-10-30", "2026-10-30", 1,
                "batch_00001.tar.gz", "HZ_UPLOADED", "RUNNING", "sgVerify", "worker", 1, 0, manifest);
    }

    private static ObjectMetadata metadata(long size, String sha) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(size);
        metadata.setUserMetadata(Map.of("sha256", sha));
        return metadata;
    }
}
