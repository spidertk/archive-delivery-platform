package com.powerjob.archive.server.api;

import com.powerjob.archive.core.service.IdempotencyConflictException;
import com.powerjob.archive.core.service.RunSubmissionService;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/runs")
@Validated
public class RunController {
    private final RunSubmissionService submission;

    public RunController(RunSubmissionService submission) {
        this.submission = submission;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunView submit(@Valid @RequestBody SubmitRunRequest request) {
        RunSubmissionService.Submission result = submission.submit(
                request.ossUri(), request.idempotencyKey(), request.jobDate());
        return new RunView(result.runId(), result.status(), result.jobDate(), result.pipelineVersion());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<Map<String, String>> conflict(IdempotencyConflictException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }

    public record SubmitRunRequest(
            @NotBlank String ossUri,
            @NotBlank String idempotencyKey,
            @Pattern(regexp = "^\\d{8}$") String jobDate) {}

    public record RunView(long runId, String status, String jobDate, int pipelineVersion) {}
}
