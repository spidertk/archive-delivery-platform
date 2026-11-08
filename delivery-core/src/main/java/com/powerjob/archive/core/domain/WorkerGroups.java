package com.powerjob.archive.core.domain;

/** 执行组标识对应一台服务器的本地工作目录；同一任务的所有节点只能由同组 Worker 推进。 */
public final class WorkerGroups {
    private WorkerGroups() {}

    public static String requireValid(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("workerGroup must be 1-32 letters, digits, underscores or hyphens");
        }
        return value;
    }
}
