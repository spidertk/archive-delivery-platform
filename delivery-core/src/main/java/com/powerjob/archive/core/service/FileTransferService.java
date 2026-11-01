package com.powerjob.archive.core.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

@Service
public class FileTransferService {
    public String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Path copyVerified(Path source, Path targetRoot, String relativePath, String expectedSha) throws IOException {
        Path target = targetRoot.resolve(relativePath).normalize();
        if (!target.startsWith(targetRoot.normalize())) throw new IOException("Invalid target path");
        Files.createDirectories(target.getParent());
        Path partial = target.resolveSibling(target.getFileName() + ".partial");
        Files.copy(source, partial, StandardCopyOption.REPLACE_EXISTING);
        String actual = sha256(partial);
        if (!actual.equals(expectedSha)) {
            Files.deleteIfExists(partial);
            throw new IOException("Artifact SHA-256 mismatch");
        }
        moveAtomically(partial, target);
        return target;
    }

    public Path publishAtomically(Path source, Path targetRoot, String relativePath, String expectedSha) throws IOException {
        return copyVerified(source, targetRoot, relativePath, expectedSha);
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void copyStream(InputStream input, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        Path partial = destination.resolveSibling(destination.getFileName() + ".partial");
        try (var channel = java.nio.channels.FileChannel.open(partial,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE);
             OutputStream output = java.nio.channels.Channels.newOutputStream(channel)) {
            input.transferTo(output);
            output.flush();
            channel.force(true);
        }
        moveAtomically(partial, destination);
    }
}
