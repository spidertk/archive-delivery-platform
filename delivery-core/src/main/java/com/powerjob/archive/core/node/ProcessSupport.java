package com.powerjob.archive.core.node;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class ProcessSupport {
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
        if (process.exitValue() != 0) {
            String detail = text.replaceAll("(?i)(password|accessKeySecret|accessKeyId)=[^,;\\s]+", "$1=***");
            throw new IllegalStateException("Local data-plane process failed: " + detail.substring(0, Math.min(800, detail.length())));
        }
        return text;
    }
}
