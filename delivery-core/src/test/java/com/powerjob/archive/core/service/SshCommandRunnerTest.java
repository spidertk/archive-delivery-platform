package com.powerjob.archive.core.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SshCommandRunnerTest {
    @Test
    void validatesHostsKeysAndArguments() {
        for (String value : new String[]{"-oProxyCommand=bad", "host;id", "host name", "../secret"}) {
            assertThatThrownBy(() -> SshCommandRunner.validateHost(value)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> SshCommandRunner.validateArgument(value)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new SshCommandRunner("ssh").run(line(),
                "/tmp/arbitrary.sh", List.of("false"), Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usesArgvAndExplicitlyDisablesFtpCleaning() throws Exception {
        Path log = Files.createTempFile("ssh-argv", ".log");
        Path fakeSsh = Files.createTempFile("fake-ssh", ".sh");
        Files.writeString(fakeSsh, "#!/usr/bin/env bash\nprintf '%s\\n' \"$@\" > \"" + log + "\"\n");
        fakeSsh.toFile().setExecutable(true);
        SshCommandRunner runner = new SshCommandRunner(fakeSsh.toString());
        runner.run(line(), "/opt/archive-delivery/bin/sg_download.sh",
                List.of("oss://sg/june/date/batch_00001.tar.gz", "a".repeat(64),
                        "/srv/maas-sftp/maasData/archive/.archive-mvp-staging/run-1", "batch_00001.tar.gz", "false"),
                Duration.ofSeconds(2));
        String argv = Files.readString(log);
        assertThat(argv).contains("BatchMode=yes", "ConnectTimeout=10", "oss://sg/june/date/batch_00001.tar.gz", "false");
    }

    @Test
    void destroysProcessAfterTimeout() throws Exception {
        Path fakeSsh = Files.createTempFile("fake-ssh-timeout", ".sh");
        Files.writeString(fakeSsh, "#!/usr/bin/env bash\nsleep 5\n");
        fakeSsh.toFile().setExecutable(true);
        SshCommandRunner runner = new SshCommandRunner(fakeSsh.toString());
        assertThatThrownBy(() -> runner.run(line(), "/opt/archive-delivery/bin/sg_download.sh",
                List.of("false"), Duration.ofMillis(50))).isInstanceOf(java.net.SocketTimeoutException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void remoteScriptsPreserveHistoricalFilesAndRejectCleaning() throws Exception {
        Path root = Files.createTempDirectory("archive-ftp-scripts");
        Path bin = Files.createDirectory(root.resolve("bin"));
        Path ossutil = bin.resolve("ossutil64");
        Files.writeString(ossutil, "#!/usr/bin/env bash\nprintf payload > \"$3\"\n");
        ossutil.toFile().setExecutable(true);
        Path history = Files.createDirectory(root.resolve("history"));
        Path keep = history.resolve("keep.tar.gz");
        Files.writeString(keep, "keep");
        FileTime mtime = FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(keep, mtime);
        Path staging = root.resolve(".archive-mvp-staging/run-1/package-1");
        Files.createDirectories(staging);
        Path payload = root.resolve("payload");
        Files.writeString(payload, "payload");
        String sha = new FileTransferService().sha256(payload);
        Path scripts = Path.of("../nodes/shell");
        ProcessBuilder download = new ProcessBuilder("bash", scripts.resolve("sg_download.sh").toAbsolutePath().toString(),
                "oss://sg/june/date/batch_00001.tar.gz", sha, staging.toString(), "batch_00001.tar.gz", "false");
        download.environment().put("ARCHIVE_FTP_ROOT", root.toString());
        download.environment().put("PATH", bin + ":" + download.environment().get("PATH"));
        assertThat(download.start().waitFor()).isZero();
        Path finalDir = root.resolve("june/2026-10-30");
        Files.createDirectories(finalDir);
        Path previous = finalDir.resolve("batch_00000.tar.gz");
        Files.writeString(previous, "previous");
        Files.setLastModifiedTime(previous, mtime);
        ProcessBuilder publish = new ProcessBuilder("bash", scripts.resolve("publish_package.sh").toAbsolutePath().toString(),
                staging.toString(), "batch_00001.tar.gz", finalDir.toString(), sha);
        publish.environment().put("ARCHIVE_FTP_ROOT", root.toString());
        assertThat(publish.start().waitFor()).isZero();

        ProcessBuilder clean = new ProcessBuilder("bash", scripts.resolve("sg_download.sh").toAbsolutePath().toString(),
                "oss://sg/june/date/batch_00001.tar.gz", sha, staging.toString(), "batch_00001.tar.gz", "true");
        clean.environment().put("ARCHIVE_FTP_ROOT", root.toString());
        assertThat(clean.start().waitFor()).isEqualTo(64);
        assertThat(Files.readString(keep)).isEqualTo("keep");
        assertThat(Files.getLastModifiedTime(keep)).isEqualTo(mtime);
        assertThat(Files.readString(previous)).isEqualTo("previous");
        assertThat(Files.getLastModifiedTime(previous)).isEqualTo(mtime);
        assertThat(finalDir.resolve("batch_00001.tar.gz")).exists();
        String downloadScript = Files.readString(scripts.resolve("sg_download.sh"));
        String publishScript = Files.readString(scripts.resolve("publish_package.sh"));
        assertThat(downloadScript + publishScript).doesNotContain("rm -rf", "rsync --delete", "rm -f -- *");
    }

    private static com.powerjob.archive.core.pipeline.FtpNodeConfig.Line line() {
        return new com.powerjob.archive.core.pipeline.FtpNodeConfig.Line(
                "ftp.example.com", "archive", "/keys/archive", null, null, null,
                "/srv/maas-sftp/maasData/archive", "/opt/archive-delivery/bin/sg_download.sh",
                "/opt/archive-delivery/bin/publish_package.sh");
    }
}
