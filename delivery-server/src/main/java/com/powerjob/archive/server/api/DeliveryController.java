package com.powerjob.archive.server.api;

import com.powerjob.archive.core.service.DeliveryRolloverService;
import com.powerjob.archive.core.service.DeliverySubmissionService;
import com.powerjob.archive.core.service.IdempotencyConflictException;
import com.powerjob.archive.core.service.ResumeConflictException;
import com.powerjob.archive.core.service.TaskOperationService;
import com.powerjob.archive.server.service.DashboardQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
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
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/deliveries")
@Validated
public class DeliveryController {
    private final DeliverySubmissionService submission;
    private final TaskOperationService operations;
    private final DeliveryRolloverService rollovers;
    private final DashboardQueryService dashboard;

    public DeliveryController(DeliverySubmissionService submission, TaskOperationService operations,
                              DeliveryRolloverService rollovers, DashboardQueryService dashboard) {
        this.submission = submission;
        this.operations = operations;
        this.rollovers = rollovers;
        this.dashboard = dashboard;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DeliverySubmissionService.Submission submit(@Valid @RequestBody SubmitDeliveryRequest request) {
        return submission.submit(new DeliverySubmissionService.SubmissionCommand(request.sourceId(), request.pipelineVersionId(),
                request.deliveryDate(), request.cutoffAt(), request.deliveryBatch(), request.idempotencyKey(),
                request.targets().stream().map(target -> new DeliverySubmissionService.TargetCommand(
                        target.targetCount(), target.deliveryEntityId())).toList()));
    }

    @GetMapping("/{deliveryId}")
    public Map<String, Object> delivery(@PathVariable long deliveryId) { return dashboard.delivery(deliveryId); }

    @GetMapping("/{deliveryId}/tasks")
    public Map<String, Object> tasks(@PathVariable long deliveryId) { return dashboard.tasks(deliveryId, false); }

    @PostMapping("/{deliveryId}/resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> resume(@PathVariable long deliveryId, @Valid @RequestBody ResumeRequest request) {
        int count = operations.resume(deliveryId, request.fromNode(), request.taskIds(), request.reason(), "api");
        return Map.of("deliveryId", deliveryId, "fromNode", request.fromNode(), "resumed", count);
    }

    @PostMapping("/{deliveryId}/rollover")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> rollover(@PathVariable long deliveryId, @Valid @RequestBody RolloverRequest request) {
        int count = rollovers.rolloverDelivery(deliveryId, request.expectedDeliveryDate(), request.deliveryDate(), request.reason(), "api");
        return Map.of("deliveryId", deliveryId, "deliveryDate", request.deliveryDate(), "rolledOver", count);
    }

    @ExceptionHandler({IdempotencyConflictException.class, ResumeConflictException.class})
    ResponseEntity<Map<String, String>> conflict(RuntimeException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }

    public record TargetRequest(@Positive int targetCount, @Positive long deliveryEntityId) {}
    public record SubmitDeliveryRequest(@Positive long sourceId, @Positive long pipelineVersionId,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deliveryDate,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime cutoffAt,
            @NotBlank String deliveryBatch, @NotBlank String idempotencyKey,
            @NotEmpty List<@Valid TargetRequest> targets) {}
    public record ResumeRequest(@NotBlank String fromNode, List<Long> taskIds, @NotBlank String reason) {}
    public record RolloverRequest(@NotNull LocalDate expectedDeliveryDate, @NotNull LocalDate deliveryDate, @NotBlank String reason) {}
}
