package com.powerjob.archive.server.api;

import com.powerjob.archive.core.service.DeliveryBatchService;
import com.powerjob.archive.core.service.DeliverySubmissionService;
import com.powerjob.archive.core.service.IdempotencyConflictException;
import com.powerjob.archive.server.service.DashboardQueryService;
import org.springframework.format.annotation.DateTimeFormat;
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
import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/delivery-batches")
public class DeliveryBatchController {
    private final DeliveryBatchService batches;
    private final DashboardQueryService dashboard;

    public DeliveryBatchController(DeliveryBatchService batches, DashboardQueryService dashboard) {
        this.batches = batches;
        this.dashboard = dashboard;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DeliverySubmissionService.Submission create(@Valid @RequestBody CreateBatchRequest request) {
        return batches.create(new DeliveryBatchService.BatchCommand(request.ossUri(), request.pipelineCode(),
                request.deliveryDate(), request.deliveryBatch()));
    }

    @GetMapping("/{deliveryId}")
    public Map<String, Object> batch(@PathVariable long deliveryId) {
        return dashboard.delivery(deliveryId);
    }

    @GetMapping("/{deliveryId}/packages")
    public Map<String, Object> packages(@PathVariable long deliveryId) {
        List<Map<String, Object>> items = dashboard.packages(deliveryId);
        return Map.of("items", items, "total", items.size());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<Map<String, String>> conflict(RuntimeException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }

    public record CreateBatchRequest(@NotBlank String ossUri, @NotBlank String pipelineCode,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deliveryDate,
            @NotBlank String deliveryBatch) {}
}
