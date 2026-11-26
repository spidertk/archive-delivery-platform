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
        // 已发布版本未启用同名模式时，OSS 仍按原目录和批次 canonical 包名寻址。
        DeliveryTask task = task();
        assertEquals("june/2026-11-08-3-june/batch_0000001.tar.gz", paths.objectKey(
                "{accountName}/{deliveryDate}-{deliveryBatch}-{accountName}", false, task, "alibaba", "june", "3"));
        assertEquals("june-2/2026-11-08-3-june-2/batch_0000001.tar.gz", paths.objectKey(
                "{accountName}/{deliveryDate}-{deliveryBatch}-{accountName}", false, task, "alibaba", "june-2", "3"));
        assertEquals("2026-11-08/batch_0000001.tar.gz", paths.ftpRelativePath(
                "{deliveryDate}", task, "alibaba", "june", "3"));
    }

    @Test void localPathsIsolateRetriesByDeliveryBatch() {
        DeliveryTask task = task();
        assertEquals("archive-delivery/BATCH-7/8/entity-10/2026-11-08/batch_0000001.tar.gz",
                paths.objectKey("archive-delivery", false, task, "alibaba", "june", "BATCH-7"));
        assertEquals("BATCH-7/8/2026-11-08/entity-10/batch_0000001.tar.gz", paths.ftpRelativePath(
                "{deliveryBatch}/{deliveryId}/{deliveryDate}/entity-{deliveryEntityId}", task, "alibaba", "june", "BATCH-7"));
    }

    @Test void ftpDailyNameCanDifferFromCanonicalBatchPackage() {
        DeliveryTask task = task(10, "jun", "group2", 7L);
        assertEquals("2026-11-08/batch_0000007.tar.gz", paths.ftpRelativePath("{deliveryDate}", task, "alibaba", "june", "3"));
        assertEquals("3/batch_0000001.tar.gz", paths.objectKey("{deliveryBatch}", false, task, "alibaba", "june", "3"));
    }

    @Test void sharedFtpNameStillIsolatesOssKeysByEntityAndVendor() {
        // 两个实体可共用账户和日序号；同名交付必须生成两个不同的 OSS 对象键。
        String prefix = "{accountName}/{deliveryDate}-{deliveryBatch}-{accountName}/{providerCode}/{entityCode}";
        DeliveryTask jun = task(10, "jun", "group2", 7L);
        DeliveryTask jun2 = task(11, "jun-2", "group1", 7L);
        assertEquals("root/2026-11-08-3-root/alibaba/jun/batch_0000007.tar.gz",
                paths.objectKey(prefix, true, jun, "alibaba", "root", "3"));
        assertEquals("root/2026-11-08-3-root/alibaba/jun-2/batch_0000007.tar.gz",
                paths.objectKey(prefix, true, jun2, "alibaba", "root", "3"));
        assertEquals("root/2026-11-08-3-root/other/jun/batch_0000007.tar.gz",
                paths.objectKey(prefix, true, jun, "other", "root", "3"));
        assertEquals(paths.ftpRelativePath("{deliveryDate}", jun, "alibaba", "root", "3"),
                paths.ftpRelativePath("{deliveryDate}", jun2, "alibaba", "root", "3"));
    }

    @Test void pathValuesCannotEscapeRemoteRoot() {
        assertThrows(IllegalArgumentException.class, () -> paths.ftpRelativePath(
                "{deliveryBatch}", task(), "alibaba", "june", "../other"));
    }

    private static DeliveryTask task() {
        return task(10, "ENTITY-10", "group1", 1L);
    }

    private static DeliveryTask task(long entityId, String entityCode, String group, long ftpSequence) {
        return new DeliveryTask(7, 8, entityId, entityCode, group,
                LocalDate.parse("2026-11-08"), OffsetDateTime.parse("2026-11-08T18:00:00+08:00"),
                "batch_0000001.tar.gz", 1, 1, null,
                "targetUpload", "READY", null, null, 0, 0, null, null, null,
                String.format("batch_%07d.tar.gz", ftpSequence), ftpSequence);
    }
}
