package com.powerjob.archive.core.node;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class ProcessSupport {
    private static final Logger log = LoggerFactory.getLogger(ProcessSupport.class);
    private ProcessSupport() {}

    /** 固定 argv 调用本地数据面程序；stdin 可传 Secret 但不进入命令行、日志或结构化结果. */
    static String run(List<String> command, String stdin, Duration timeout) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Process process = builder.start();
        if (stdin != null) {
            process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        process.getOutputStream().close();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new SocketTimeoutException("Local data-plane process timed out");
        }
        String text = output.join();
        text.lines().filter(line -> !line.isBlank()).forEach(line -> log.info("Local data-plane: {}", sanitize(line)));
        if (process.exitValue() != 0) {
            String detail = sanitize(text);
            throw new IllegalStateException("Local data-plane process failed: " + detail.substring(0, Math.min(800, detail.length())));
        }
        return text;
    }

    static String runJson(List<String> command, String stdin, Duration timeout, String marker) throws Exception {
        String text = run(command, stdin, timeout);
        return text.lines().filter(line -> line.startsWith(marker)).reduce((first, second) -> second)
                .map(line -> line.substring(marker.length()))
                .orElseThrow(() -> new IllegalStateException("Local data-plane JSON result is missing: " + marker));
    }

    private static String sanitize(String text) {
        return text.replaceAll("(?i)(password|accessKeySecret|accessKeyId)=[^,;\\s]+", "$1=***");
    }
}
