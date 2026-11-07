package com.powerjob.archive.server.api;

import com.powerjob.archive.core.service.ResumeConflictException;
import com.powerjob.archive.core.service.TaskOperationService;
import com.powerjob.archive.server.service.DashboardQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tasks")
public class DeliveryTaskController {
    private final TaskOperationService operations;
    private final DashboardQueryService dashboard;

    public DeliveryTaskController(TaskOperationService operations, DashboardQueryService dashboard) {
        this.operations = operations;
        this.dashboard = dashboard;
    }

    @GetMapping("/{taskId}")
    public Map<String, Object> detail(@PathVariable long taskId) { return dashboard.taskDetail(taskId); }

    @GetMapping("/{taskId}/timeline")
    public Map<String, Object> timeline(@PathVariable long taskId) { return dashboard.timeline(taskId); }

    @PostMapping("/{taskId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> retry(@PathVariable long taskId, @Valid @RequestBody RetryRequest request) {
        operations.retry(taskId, request.expectedFence(), request.reason(), "api");
        return Map.of("taskId", taskId, "executionStatus", "READY");
    }

    @ExceptionHandler(ResumeConflictException.class)
    ResponseEntity<Map<String, String>> conflict(ResumeConflictException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }

    public record RetryRequest(long expectedFence, @NotBlank String reason) {}
}
