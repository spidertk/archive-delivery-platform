package com.powerjob.archive.core.service;

import com.powerjob.archive.core.config.FtpDeliveryProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
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
    private final FtpDeliveryProperties properties;
    private final String sshExecutable;

    @org.springframework.beans.factory.annotation.Autowired
    public SshCommandRunner(FtpDeliveryProperties properties) {
        this(properties, "ssh");
    }

    SshCommandRunner(FtpDeliveryProperties properties, String sshExecutable) {
        this.properties = properties;
        this.sshExecutable = sshExecutable;
    }

    public String run(long lineId, String script, List<String> arguments, Duration timeout) throws Exception {
        FtpDeliveryProperties.Line line = properties.line(lineId);
        if (!script.equals(line.getDownloadScript()) && !script.equals(line.getPublishScript())) {
            throw new IllegalArgumentException("Only fixed FTP scripts are allowed");
        }
        validateHost(line.getHost());
        validateUser(line.getUser());
        validateKey(line.getKey());
        if (line.getJumpHost() != null && !line.getJumpHost().isBlank()) {
            validateHost(line.getJumpHost());
            validateUser(line.getJumpUser());
            validateKey(line.getJumpKey());
        }
        arguments.forEach(SshCommandRunner::validateArgument);
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
        command.add("-i");
        command.add(line.getKey());
        if (line.getJumpHost() != null && !line.getJumpHost().isBlank()) {
            command.add("-o");
            command.add("ProxyCommand=ssh -o BatchMode=yes -o ConnectTimeout=10 -o ConnectionAttempts=1 " +
                    "-o IdentitiesOnly=yes -i " + line.getJumpKey() + " -W %h:%p " +
                    line.getJumpUser() + "@" + line.getJumpHost());
        }
        command.add(line.getUser() + "@" + line.getHost());
        command.add(script);
        arguments.forEach(command::add);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new IllegalStateException("SSH command timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IllegalStateException("SSH command failed with exit " + process.exitValue() + ": " + output.strip());
        }
        return output;
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
