package com.powerjob.archive.core.domain;

import java.time.LocalDate;

public record DeliveryRun(
        long id,
        String runCode,
        String ossUri,
        String bucketName,
        String objectPrefix,
        String idempotencyKey,
        LocalDate jobDate,
        long pipelineVersionId,
        String status) {
}
