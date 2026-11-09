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
        // 固定 argv 避免 Shell 拼接，合并 stderr 以保留节点失败原因。
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        // 子进程只能执行调用方给定的受控程序。
        Process process = builder.start();
        // FTP 密码等敏感输入仅通过 stdin 传递。
        if (stdin != null) {
            process.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        // 关闭输入流让数据面程序获得完整请求并开始执行。
        process.getOutputStream().close();
        // 异步读取输出防止管道缓冲区写满后死锁。
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        // 超时进程必须强制结束，不能无限占用 Worker 槽位。
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new SocketTimeoutException("Local data-plane process timed out");
        }
        // 进程结束后收集完整结构化输出和诊断信息。
        String text = output.join();
        // 每行日志先脱敏，禁止密码或访问密钥进入应用日志。
        text.lines().filter(line -> !line.isBlank()).forEach(line -> log.info("Local data-plane: {}", sanitize(line)));
        // 非零退出必须阻断节点并限制错误文本长度。
        if (process.exitValue() != 0) {
            String detail = sanitize(text);
            throw new IllegalStateException("Local data-plane process failed: " + detail.substring(0, Math.min(800, detail.length())));
        }
        // 只有成功输出才交给 runJson 提取标记结果。
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
