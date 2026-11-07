package com.powerjob.archive.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.powerjob.archive.core.pipeline.PipelineAdminService;
import com.powerjob.archive.core.pipeline.PipelineVersionConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class PipelineController {
    private final PipelineAdminService pipelines;

    public PipelineController(PipelineAdminService pipelines) { this.pipelines = pipelines; }

    @GetMapping("/pipelines/{code}/versions")
    public List<PipelineAdminService.VersionView> list(@PathVariable String code) { return pipelines.list(code); }

    @GetMapping("/pipeline-versions/{id}")
    public PipelineAdminService.VersionView get(@PathVariable long id) { return pipelines.get(id); }

    @PostMapping("/pipelines/{code}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public PipelineAdminService.VersionView draft(@PathVariable String code, @RequestBody DraftRequest request) {
        return pipelines.createDraft(code, request.baseVersionId());
    }

    @PutMapping("/pipeline-versions/{id}/definition")
    public PipelineAdminService.VersionView update(@PathVariable long id, @RequestBody JsonNode definition) {
        return pipelines.updateDraft(id, definition);
    }

    @PostMapping("/pipeline-versions/{id}/publish")
    public PipelineAdminService.VersionView publish(@PathVariable long id) { return pipelines.publish(id); }

    @ExceptionHandler(PipelineVersionConflictException.class)
    ResponseEntity<Map<String, String>> conflict(RuntimeException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }

    public record DraftRequest(long baseVersionId) {}
}
