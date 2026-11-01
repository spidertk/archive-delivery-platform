package com.powerjob.archive.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class FileTransferServiceTest {
    private final FileTransferService files = new FileTransferService();

    @Test
    void copiesVerifiesAndPublishes(@TempDir Path temp) throws Exception {
        Path source = temp.resolve("source.tar.gz");
        Files.writeString(source, "archive-content");
        String sha = files.sha256(source);
        Path hz = files.copyVerified(source, temp.resolve("hz"), "june/2026-10-30/batch.tar.gz", sha);
        Path published = files.publishAtomically(hz, temp.resolve("ftp"), "june/2026-10-30/batch.tar.gz", sha);
        assertThat(published).exists();
        assertThat(files.sha256(published)).isEqualTo(sha);
        assertThat(published.resolveSibling("batch.tar.gz.partial")).doesNotExist();
    }

    @Test
    void rejectsChecksumMismatch(@TempDir Path temp) throws Exception {
        Path source = temp.resolve("source.tar.gz");
        Files.writeString(source, "archive-content");
        assertThatThrownBy(() -> files.copyVerified(source, temp.resolve("hz"), "june/batch.tar.gz",
                "0000000000000000000000000000000000000000000000000000000000000000"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("mismatch");
        assertThat(temp.resolve("hz/june/batch.tar.gz")).doesNotExist();
    }
}
