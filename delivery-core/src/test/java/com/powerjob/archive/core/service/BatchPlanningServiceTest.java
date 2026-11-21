package com.powerjob.archive.core.service;

import com.powerjob.archive.core.domain.DeliveryEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BatchPlanningServiceTest {
    private static final long MIB = 1024 * 1024;

    @Test void plansContiguousSourcesWithoutDroppingAny() {
        var planned = BatchPlanningService.planPackages(List.of(6 * MIB, 6 * MIB, 4 * MIB),
                new BatchPlanningService.Limits(10 * MIB), 10 * MIB);
        assertEquals(List.of(List.of(0), List.of(1, 2)), planned);
        assertEquals(3, planned.stream().flatMap(List::stream).distinct().count());
    }

    @Test void legalSourceAboveReservedTargetCanFormSingleSourcePackage() {
        var planned = BatchPlanningService.planPackages(List.of(96 * MIB),
                new BatchPlanningService.Limits(95 * MIB), 100 * MIB);
        assertEquals(List.of(List.of(0)), planned);
    }

    @Test void oneKibTailIsStillADeliverablePackage() {
        var planned = BatchPlanningService.planPackages(List.of(10 * MIB, 1024L),
                new BatchPlanningService.Limits(10 * MIB), 10 * MIB);
        assertEquals(List.of(List.of(0), List.of(1)), planned);
    }

    @Test void emptyAndOversizedFilesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> BatchPlanningService.planPackages(List.of(0L),
                new BatchPlanningService.Limits(10 * MIB), 10 * MIB));
        assertThrows(IllegalArgumentException.class, () -> BatchPlanningService.planPackages(List.of(11 * MIB),
                new BatchPlanningService.Limits(10 * MIB), 10 * MIB));
    }

    @Test void threeHundredPackagesRoundRobinAcrossTwoHundredSlotMachines() {
        DeliveryEntity jun = entity(1, "jun");
        List<BatchPlanningService.EntityWorker> workers = List.of(
                new BatchPlanningService.EntityWorker(jun, "group1", 100),
                new BatchPlanningService.EntityWorker(jun, "group2", 100));
        List<BatchPlanningService.PlanningSlot> slots = BatchPlanningService.slots(300, workers);
        assertEquals(300, slots.size());
        assertEquals(200, slots.stream().filter(slot -> slot.workerGroup().equals("group1")).count());
        assertEquals(100, slots.stream().filter(slot -> slot.workerGroup().equals("group2")).count());
        assertEquals("group1", slots.get(0).workerGroup());
        assertEquals("group2", slots.get(100).workerGroup());
        assertEquals("group1", slots.get(200).workerGroup());
        assertEquals(1, slots.get(200).round());
    }

    private static DeliveryEntity entity(long id, String code) {
        return new DeliveryEntity(id, "alibaba", code, code, "FTP", "june", "group1", null, true);
    }
}
