package com.powerjob.archive.core.service;

import com.powerjob.archive.core.domain.SourceData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeliveryPreparationServiceTest {
    private static final long MIB = 1024 * 1024;

    @Test void tenSmallSourcesFormOneTaskWithinLimit() {
        List<SourceData> sources = sources(10, MIB);
        var planned = DeliveryPreparationService.planTasks(sources,
                List.of(new DeliveryPreparationService.TargetSlot(1, 1)), 10 * MIB);
        assertEquals(1, planned.size());
        assertEquals(10, planned.get(0).sources().size());
        assertEquals(sources.stream().map(SourceData::dataId).toList(),
                planned.get(0).sources().stream().map(SourceData::dataId).toList());
    }

    @Test void tenTwoMiBSourcesFormTwoFullPackagesWithDeliveryWideSequence() {
        var planned = DeliveryPreparationService.planTasks(sources(10, 2 * MIB),
                List.of(new DeliveryPreparationService.TargetSlot(1, 1, 1),
                        new DeliveryPreparationService.TargetSlot(2, 1, 2)), 10 * MIB);
        assertEquals(2, planned.size());
        assertEquals(List.of(5, 5), planned.stream().map(task -> task.sources().size()).toList());
        assertEquals(List.of(10 * MIB, 10 * MIB), planned.stream()
                .map(task -> task.sources().stream().mapToLong(SourceData::sizeBytes).sum()).toList());
        assertEquals(List.of(1, 2), planned.stream().map(task -> task.slot().packageSeq()).toList());
    }

    @Test void separateTargetSlotsKeepIndependentTasks() {
        var planned = DeliveryPreparationService.planTasks(sources(3, MIB),
                List.of(new DeliveryPreparationService.TargetSlot(1, 1),
                        new DeliveryPreparationService.TargetSlot(2, 1),
                        new DeliveryPreparationService.TargetSlot(3, 1)), 10 * MIB);
        assertEquals(3, planned.size());
        assertEquals(List.of(1, 1, 1), planned.stream().map(task -> task.sources().size()).toList());
    }

    @Test void threeLargeSourcesContinueIntoSecond100MiBPackage() {
        var planned = DeliveryPreparationService.planTasks(sources(56050498L, 13461328L, 42221409L),
                List.of(new DeliveryPreparationService.TargetSlot(1, 1),
                        new DeliveryPreparationService.TargetSlot(1, 2)), 100 * MIB);
        assertEquals(2, planned.size());
        assertEquals(List.of(2, 1), planned.stream().map(task -> task.sources().size()).toList());
        assertEquals(List.of(1L, 2L), planned.get(0).sources().stream().map(SourceData::dataId).toList());
        assertEquals(List.of(3L), planned.get(1).sources().stream().map(SourceData::dataId).toList());
    }

    @Test void backtrackingMovesBoundarySoBothPackagesReachMinimum() {
        var planned = DeliveryPreparationService.planTasks(
                sources(8 * MIB, 2 * MIB, 2 * MIB, 2 * MIB, 2 * MIB),
                List.of(new DeliveryPreparationService.TargetSlot(1, 1, 1),
                        new DeliveryPreparationService.TargetSlot(2, 1, 2)), 10 * MIB, 8 * MIB);
        assertEquals(List.of(1, 4), planned.stream().map(task -> task.sources().size()).toList());
        assertEquals(List.of(8 * MIB, 8 * MIB), planned.stream()
                .map(task -> task.sources().stream().mapToLong(SourceData::sizeBytes).sum()).toList());
    }

    @Test void requestedExampleAccumulatesWithoutSplittingAndIgnoresTrailingSource() {
        var planned = DeliveryPreparationService.planTasks(
                sources(1 * MIB, 2 * MIB, 3 * MIB, 4 * MIB, 1 * MIB, 5 * MIB, 3 * MIB, 2 * MIB),
                List.of(new DeliveryPreparationService.TargetSlot(1, 1, 1),
                        new DeliveryPreparationService.TargetSlot(2, 1, 2)), 10 * MIB);
        assertEquals(List.of(4, 3), planned.stream().map(task -> task.sources().size()).toList());
        assertEquals(List.of(1L, 2L, 3L, 4L), planned.get(0).sources().stream().map(SourceData::dataId).toList());
        assertEquals(List.of(5L, 6L, 7L), planned.get(1).sources().stream().map(SourceData::dataId).toList());
        assertEquals(10 * MIB, planned.get(0).sources().stream().mapToLong(SourceData::sizeBytes).sum());
        assertEquals(9 * MIB, planned.get(1).sources().stream().mapToLong(SourceData::sizeBytes).sum());
        assertEquals(List.of(1, 2), planned.stream().map(task -> task.slot().packageSeq()).toList());
    }

    @Test void lastTargetClosesAtNextOverflowAndDoesNotConsumeRemainingSources() {
        var planned = DeliveryPreparationService.planTasks(sources(3, 6 * MIB),
                List.of(new DeliveryPreparationService.TargetSlot(1, 1)), 10 * MIB);
        assertEquals(1, planned.size());
        assertEquals(List.of(1L), planned.get(0).sources().stream().map(SourceData::dataId).toList());
    }

    @Test void packageBelowMinimumIsRejectedInsteadOfDelivered() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DeliveryPreparationService.planTasks(sources(3, MIB),
                        List.of(new DeliveryPreparationService.TargetSlot(1, 1)), 10 * MIB, 8 * MIB));
        assertEquals("Source package is below minPackageBytes without splitting: 3145728<8388608", error.getMessage());
    }

    @Test void invalidCapacityAndOversizedInputsFailBeforeAssignment() {
        assertThrows(IllegalArgumentException.class, () -> DeliveryPreparationService.planTasks(sources(1, MIB),
                List.of(new DeliveryPreparationService.TargetSlot(1, 1),
                        new DeliveryPreparationService.TargetSlot(2, 1)), 10 * MIB));
        assertThrows(IllegalArgumentException.class, () -> DeliveryPreparationService.planTasks(sources(1, 11 * MIB),
                List.of(new DeliveryPreparationService.TargetSlot(1, 1)), 10 * MIB));
    }

    private static List<SourceData> sources(int count, long sizeBytes) {
        return LongStream.rangeClosed(1, count).mapToObj(id -> new SourceData(id, 1, "source-" + id + ".tar.gz", "",
                null, null, "PENDING", null, null, 0, null, sizeBytes, sizeBytes,
                String.format("%064x", id), null)).toList();
    }

    private static List<SourceData> sources(long... sizes) {
        return java.util.stream.IntStream.range(0, sizes.length).mapToObj(index -> new SourceData(index + 1, 1,
                "source-" + (index + 1) + ".tar.gz", "", null, null, "PENDING", null, null, 0, null,
                sizes[index], sizes[index], String.format("%064x", index + 1), null)).toList();
    }
}
