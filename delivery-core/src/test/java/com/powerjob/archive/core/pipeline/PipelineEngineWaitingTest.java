package com.powerjob.archive.core.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powerjob.archive.core.domain.OutputPackage;
import com.powerjob.archive.core.repository.NodeRunRepository;
import com.powerjob.archive.core.repository.OutputPackageRepository;
import com.powerjob.archive.core.repository.SourcePackageRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineEngineWaitingTest {
    @Test
    void waitingReleasesLeaseWithoutRetryOrCompletion() {
        SourcePackageRepository sources = mock(SourcePackageRepository.class);
        OutputPackageRepository outputs = mock(OutputPackageRepository.class);
        NodeRunRepository runs = mock(NodeRunRepository.class);
        PipelineRepository pipelines = mock(PipelineRepository.class);
        JsonNode manifest = new ObjectMapper().createObjectNode();
        OutputPackage output = new OutputPackage(10, 20, 2, manifest, 1, 1, "2026-10-30", "2026-10-30",
                1, "batch_00001.tar.gz", "HZ_UPLOADED", "RUNNING", "sgVerify", "worker", 1, manifest);
        when(sources.claimNext(anyLong(), anyString())).thenReturn(Optional.empty());
        when(outputs.claimNext(anyString())).thenReturn(Optional.of(output));
        NodeDefinition definition = new NodeDefinition("sgVerify", "OUTPUT", "oss.sg-verify", Map.of());
        when(pipelines.read(manifest)).thenReturn(new PipelineDefinition("archive", 2, Map.of(), List.of(definition)));
        when(runs.start(org.mockito.ArgumentMatchers.<Long>any(), org.mockito.ArgumentMatchers.<Long>any(),
                anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(100L);
        NodeHandler handler = new NodeHandler() {
            @Override public String type() { return "oss.sg-verify"; }
            @Override public NodeResult execute(NodeContext context) {
                return NodeResult.waiting("sgVerify", manifest, Duration.ofSeconds(30));
            }
        };
        new PipelineEngine(sources, outputs, runs, pipelines, List.of(handler)).executeNext(1, "worker");
        verify(outputs).markWaiting(10, 1, "worker", manifest.toString(), Duration.ofSeconds(30));
        verify(runs).finish(100, "WAITING", manifest.toString(), null);
        verify(outputs, never()).markRetry(anyLong(), anyLong(), anyString(), anyString());
        verify(outputs, never()).complete(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
