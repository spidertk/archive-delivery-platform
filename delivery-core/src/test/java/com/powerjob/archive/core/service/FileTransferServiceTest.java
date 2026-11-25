package com.powerjob.archive.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileTransferServiceTest {
    @TempDir Path temp;

    @Test void cleanupRemovesOnlyVerifiedBulkCopiesAndRetainsManifests() throws Exception {
        Path task = temp.resolve("task-7");
        Path sources = Files.createDirectories(task.resolve("sources/data-1"));
        Path requests = Files.createDirectories(task.resolve("merge/requests/request-1"));
        Path repair = Files.createDirectories(task.resolve("repair"));
        Path oss = Files.createDirectories(task.resolve("oss-verified/date"));
        Files.writeString(sources.resolve("source.tar.gz"), "source");
        Files.writeString(requests.resolve("metadata.json"), "request");
        Files.writeString(repair.resolve("batch_0000001.tar.gz"), "repair");
        Files.writeString(oss.resolve("batch_0000001.tar.gz"), "verified");
        Files.writeString(task.resolve("merge/merge-result.json"), "merge");
        Files.writeString(repair.resolve("repair-result.json"), "repair-manifest");

        new FileTransferService().cleanupAfterVerification(task, oss.resolve("batch_0000001.tar.gz"), "batch_0000001.tar.gz");

        assertFalse(Files.exists(task.resolve("sources")));
        assertFalse(Files.exists(task.resolve("merge/requests")));
        assertFalse(Files.exists(repair.resolve("batch_0000001.tar.gz")));
        assertFalse(Files.exists(oss.resolve("batch_0000001.tar.gz")));
        assertTrue(Files.isRegularFile(task.resolve("merge/merge-result.json")));
        assertTrue(Files.isRegularFile(repair.resolve("repair-result.json")));
        assertThrows(IllegalArgumentException.class, () -> new FileTransferService()
                .cleanupAfterVerification(task, temp.resolve("other.tar.gz"), "batch_0000001.tar.gz"));
    }
}
