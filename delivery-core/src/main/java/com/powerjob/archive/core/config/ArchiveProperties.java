package com.powerjob.archive.core.config;

import com.powerjob.archive.core.domain.WorkerGroups;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Worker 仅配置本地执行资源；业务日期、节点顺序、交付路径与 Secret 均来自 PipelineVersion。 */
@Component
@ConfigurationProperties(prefix = "archive")
public class ArchiveProperties {
    private String workerId = "local-worker";
    private String workerGroup = "group1";
    private int maxRunningTasks = 3;
    private int preparationDownloadThreads = 4;
    private int taskLeaseSeconds = 900;
    private boolean cleanupWorkAfterVerify;
    private String workRoot = "/data/work";
    private String ftpVerificationRoot = "/data/ftp-verify";
    private String pythonCommand = "python3";

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public String getWorkerGroup() { return workerGroup; }
    public void setWorkerGroup(String workerGroup) { this.workerGroup = WorkerGroups.requireValid(workerGroup); }
    public int getMaxRunningTasks() { return maxRunningTasks; }
    public void setMaxRunningTasks(int maxRunningTasks) { this.maxRunningTasks = maxRunningTasks; }
    public int getPreparationDownloadThreads() { return preparationDownloadThreads; }
    public void setPreparationDownloadThreads(int preparationDownloadThreads) { this.preparationDownloadThreads = preparationDownloadThreads; }
    public int getTaskLeaseSeconds() { return taskLeaseSeconds; }
    public void setTaskLeaseSeconds(int taskLeaseSeconds) { this.taskLeaseSeconds = taskLeaseSeconds; }
    public boolean isCleanupWorkAfterVerify() { return cleanupWorkAfterVerify; }
    public void setCleanupWorkAfterVerify(boolean cleanupWorkAfterVerify) { this.cleanupWorkAfterVerify = cleanupWorkAfterVerify; }
    public String getWorkRoot() { return workRoot; }
    public void setWorkRoot(String workRoot) { this.workRoot = workRoot; }
    public String getFtpVerificationRoot() { return ftpVerificationRoot; }
    public void setFtpVerificationRoot(String ftpVerificationRoot) { this.ftpVerificationRoot = ftpVerificationRoot; }
    public String getPythonCommand() { return pythonCommand; }
    public void setPythonCommand(String pythonCommand) { this.pythonCommand = pythonCommand; }
}
