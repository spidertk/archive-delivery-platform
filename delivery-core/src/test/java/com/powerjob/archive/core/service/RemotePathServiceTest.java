package com.powerjob.archive.core.service;

import com.powerjob.archive.core.domain.DeliveryTask;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RemotePathServiceTest {
    private final RemotePathService paths = new RemotePathService();

    @Test void productionOssAndFtpPathsFollowTheirDistinctLayouts() {
        DeliveryTask task = task();
        assertEquals("june/2026-11-08-3-june/batch_00001.tar.gz", paths.objectKey(
                "{accountName}/{deliveryDate}-{deliveryBatch}-{accountName}", task, "alibaba", "june", "3"));
        assertEquals("june-2/2026-11-08-3-june-2/batch_00001.tar.gz", paths.objectKey(
                "{accountName}/{deliveryDate}-{deliveryBatch}-{accountName}", task, "alibaba", "june-2", "3"));
        assertEquals("2026-11-08/batch_00001.tar.gz", paths.ftpRelativePath(
                "{deliveryDate}", task, "alibaba", "june", "3"));
    }

    @Test void localPathsIsolateRetriesByDeliveryBatch() {
        DeliveryTask task = task();
        assertEquals("archive-delivery/BATCH-7/8/entity-10/2026-11-08/batch_00001.tar.gz",
                paths.objectKey("archive-delivery", task, "alibaba", "june", "BATCH-7"));
        assertEquals("BATCH-7/8/2026-11-08/entity-10/batch_00001.tar.gz", paths.ftpRelativePath(
                "{deliveryBatch}/{deliveryId}/{deliveryDate}/entity-{deliveryEntityId}", task, "alibaba", "june", "BATCH-7"));
    }

    @Test void pathValuesCannotEscapeRemoteRoot() {
        assertThrows(IllegalArgumentException.class, () -> paths.ftpRelativePath(
                "{deliveryBatch}", task(), "alibaba", "june", "../other"));
    }

    private static DeliveryTask task() {
        return new DeliveryTask(7, 8, 10, "ENTITY-10", "group1",
                LocalDate.parse("2026-11-08"), OffsetDateTime.parse("2026-11-08T18:00:00+08:00"),
                "batch_00001.tar.gz", 1, 1, null, null,
                "targetUpload", "READY", null, null, 0, 0, null, null, null);
    }
}
