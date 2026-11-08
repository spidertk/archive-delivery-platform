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

    /** 受控交付路径同 SHA 幂等，不同 SHA 冲突；临时文件校验成功后才原子封存。 */
    public Path copyVerified(Path source, Path target, String expectedSha) throws IOException {
        if (Files.isRegularFile(target)) {
            if (Files.size(target) == Files.size(source) && sha256(target).equals(expectedSha)) return target;
            throw new RemoteConflictException("Target exists with different size or SHA: " + target.getFileName());
        }
        Files.createDirectories(target.getParent());
        Path partial = target.resolveSibling(target.getFileName() + ".partial");
        Files.copy(source, partial, StandardCopyOption.REPLACE_EXISTING);
        if (!sha256(partial).equals(expectedSha)) {
            Files.deleteIfExists(partial);
            throw new IOException("Artifact SHA-256 mismatch");
        }
        moveAtomically(partial, target, false);
        return target;
    }

    public void copyStream(InputStream input, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        Path partial = destination.resolveSibling(destination.getFileName() + ".partial");
        try {
            try (var channel = java.nio.channels.FileChannel.open(partial, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, java.nio.file.StandardOpenOption.WRITE);
                 OutputStream output = java.nio.channels.Channels.newOutputStream(channel)) {
                input.transferTo(output);
                output.flush();
                channel.force(true);
            }
            moveAtomically(partial, destination, true);
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(partial);
            throw error;
        }
    }

    private void moveAtomically(Path source, Path target, boolean replace) throws IOException {
        try {
            if (replace) Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            if (replace) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(source, target);
        }
    }
}
