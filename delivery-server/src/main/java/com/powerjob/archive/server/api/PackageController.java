package com.powerjob.archive.server.api;

import com.powerjob.archive.core.service.ResumeConflictException;
import com.powerjob.archive.core.service.ResumeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
@RequestMapping("/api/v1/packages")
public class PackageController {
    private final ResumeService resumes;

    public PackageController(ResumeService resumes) {
        this.resumes = resumes;
    }

    @PostMapping("/{packageId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> retry(@PathVariable long packageId, @Valid @RequestBody RetryRequest request) {
        resumes.retry(packageId, request.expectedFence(), request.reason());
        return Map.of("packageId", packageId, "runStatus", "READY");
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
