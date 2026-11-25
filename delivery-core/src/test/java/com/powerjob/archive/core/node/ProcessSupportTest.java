package com.powerjob.archive.core.node;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ProcessSupportTest {
    @TempDir Path temp;

    @Test void interruptedWorkerStopsItsDataPlaneBeforeReturning() throws Exception {
        // 模拟实际写任务目录的 Python；调用线程返回后不能仍有旧进程供重试并发使用。
        Path pidFile = temp.resolve("interrupted.pid");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean preservedInterrupt = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                ProcessSupport.run(command(pidFile), null, Duration.ofMinutes(1));
            } catch (Throwable error) {
                failure.set(error);
                preservedInterrupt.set(Thread.currentThread().isInterrupted());
            }
        });
        worker.start();
        ProcessHandle child = null;
        try {
            child = ProcessHandle.of(startedPid(pidFile)).orElseThrow();
            worker.interrupt();
            worker.join(7000);
            assertFalse(worker.isAlive(), "Interrupted worker must finish after bounded child cleanup");
            assertInstanceOf(InterruptedException.class, failure.get());
            assertTrue(preservedInterrupt.get());
            assertFalse(child.isAlive(), "The old data-plane process must not outlive a failed node call");
        } finally {
            worker.interrupt();
            if (child != null && child.isAlive()) child.destroyForcibly();
            worker.join(7000);
        }
    }

    @Test void timedOutDataPlaneIsStoppedBeforeFailureIsReported() throws Exception {
        // 超时与中断必须走相同回收路径，不能只发出 kill 后立即让重试开始。
        Path pidFile = temp.resolve("timeout.pid");
        try {
            assertThrows(SocketTimeoutException.class,
                    () -> ProcessSupport.run(command(pidFile), null, Duration.ofSeconds(2)));
            assertFalse(ProcessHandle.of(startedPid(pidFile)).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            if (Files.exists(pidFile)) ProcessHandle.of(Long.parseLong(Files.readString(pidFile)))
                    .filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    private List<String> command(Path pidFile) {
        return List.of("python3", "-c", "import os,pathlib,sys,time; pathlib.Path(sys.argv[1]).write_text(str(os.getpid())); time.sleep(60)",
                pidFile.toString());
    }

    private long startedPid(Path pidFile) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(Files.exists(pidFile), "Python child did not start");
        return Long.parseLong(Files.readString(pidFile));
    }
}
