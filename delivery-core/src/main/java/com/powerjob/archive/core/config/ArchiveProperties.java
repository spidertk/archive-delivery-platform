package com.powerjob.archive.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Worker 仅配置本地执行资源；业务日期、节点顺序、交付路径与 Secret 均来自 PipelineVersion。 */
@Component
@ConfigurationProperties(prefix = "archive")
public class ArchiveProperties {
    private String workerId = "local-worker";
    private int maxRunningTasks = 3;
    private String workRoot = "/data/work";
    private String ftpVerificationRoot = "/data/ftp-verify";
    private String pythonCommand = "python3";
    private String pythonScript = "/app/nodes/python/archive_repair.py";
    private String ftpVerifyScript = "/app/nodes/python/ftp_download_verify.py";

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public int getMaxRunningTasks() { return maxRunningTasks; }
    public void setMaxRunningTasks(int maxRunningTasks) { this.maxRunningTasks = maxRunningTasks; }
    public String getWorkRoot() { return workRoot; }
    public void setWorkRoot(String workRoot) { this.workRoot = workRoot; }
    public String getFtpVerificationRoot() { return ftpVerificationRoot; }
    public void setFtpVerificationRoot(String ftpVerificationRoot) { this.ftpVerificationRoot = ftpVerificationRoot; }
    public String getPythonCommand() { return pythonCommand; }
    public void setPythonCommand(String pythonCommand) { this.pythonCommand = pythonCommand; }
    public String getPythonScript() { return pythonScript; }
    public void setPythonScript(String pythonScript) { this.pythonScript = pythonScript; }
    public String getFtpVerifyScript() { return ftpVerifyScript; }
    public void setFtpVerifyScript(String ftpVerifyScript) { this.ftpVerifyScript = ftpVerifyScript; }
}
