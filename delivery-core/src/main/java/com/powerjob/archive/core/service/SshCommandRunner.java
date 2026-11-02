package com.powerjob.archive.core.service;

import com.powerjob.archive.core.pipeline.FtpNodeConfig;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class SshCommandRunner {
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern USER = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*");
    private static final Pattern ARGUMENT = Pattern.compile("/?[A-Za-z0-9][A-Za-z0-9._:/-]*");
    private final String sshExecutable;

    public SshCommandRunner() {
        this("ssh");
    }

    SshCommandRunner(String sshExecutable) {
        this.sshExecutable = sshExecutable;
    }

    public String run(FtpNodeConfig.Line line, String script, List<String> arguments, Duration timeout) throws Exception {
        if (!script.equals(line.downloadScript()) && !script.equals(line.publishScript())) {
            throw new IllegalArgumentException("Only fixed FTP scripts are allowed");
        }
        validateHost(line.host());
        validateUser(line.user());
        validateKey(line.key());
        if (line.jumpHost() != null && !line.jumpHost().isBlank()) {
            validateHost(line.jumpHost());
            validateUser(line.jumpUser());
            validateKey(line.jumpKey());
        }
        arguments.forEach(SshCommandRunner::validateArgument);
        Path tempDirectory = Files.createTempDirectory("archive-ssh-");
        Path knownHosts = tempDirectory.resolve("known_hosts");
        try {
            String hostKeys = line.hostKey() + "\n";
            if (line.jumpHost() != null && !line.jumpHost().isBlank()) {
                hostKeys += line.jumpHostKey() + "\n";
            }
            Files.writeString(knownHosts, hostKeys);
            Files.setPosixFilePermissions(knownHosts, PosixFilePermissions.fromString("rw-------"));
            List<String> command = new ArrayList<>();
            command.add(sshExecutable);
            command.add("-o");
            command.add("BatchMode=yes");
            command.add("-o");
            command.add("ConnectTimeout=10");
            command.add("-o");
            command.add("ConnectionAttempts=1");
            command.add("-o");
            command.add("IdentitiesOnly=yes");
            command.add("-o");
            command.add("UserKnownHostsFile=" + knownHosts);
            command.add("-o");
            command.add("GlobalKnownHostsFile=/dev/null");
            command.add("-o");
            command.add("StrictHostKeyChecking=yes");
            command.add("-i");
            command.add(line.key());
            if (line.jumpHost() != null && !line.jumpHost().isBlank()) {
                command.add("-o");
                command.add("ProxyCommand=ssh -o BatchMode=yes -o ConnectTimeout=10 -o ConnectionAttempts=1 " +
                        "-o IdentitiesOnly=yes -o UserKnownHostsFile=" + knownHosts +
                        " -o GlobalKnownHostsFile=/dev/null -o StrictHostKeyChecking=yes -i " + line.jumpKey() +
                        " -W %h:%p " + line.jumpUser() + "@" + line.jumpHost());
            }
            command.add(line.user() + "@" + line.host());
            command.add(script);
            arguments.forEach(command::add);
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new java.net.SocketTimeoutException("SSH command timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() == 65 || process.exitValue() == 66) {
                throw new RemoteConflictException("Remote package conflict: " + output.strip());
            }
            if (process.exitValue() != 0) {
                throw new java.io.IOException("SSH command failed with exit " + process.exitValue() + ": " + output.strip());
            }
            return output;
        } finally {
            Files.deleteIfExists(knownHosts);
            Files.deleteIfExists(tempDirectory);
        }
    }

    static void validateHost(String host) {
        if (host == null || !HOST.matcher(host).matches() || host.startsWith("-")) {
            throw new IllegalArgumentException("Invalid SSH host");
        }
    }

    static void validateUser(String user) {
        if (user == null || !USER.matcher(user).matches() || user.startsWith("-")) {
            throw new IllegalArgumentException("Invalid SSH user");
        }
    }

    static void validateKey(String key) {
        if (key == null || !key.matches("/?[A-Za-z0-9._/-]+") || key.contains("..") || key.startsWith("-")) {
            throw new IllegalArgumentException("Unsafe SSH key path");
        }
    }

    static void validateArgument(String argument) {
        if (argument == null || !ARGUMENT.matcher(argument).matches() || argument.startsWith("-")
                || argument.contains("..")) {
            throw new IllegalArgumentException("Unsafe SSH argument");
        }
    }
}
