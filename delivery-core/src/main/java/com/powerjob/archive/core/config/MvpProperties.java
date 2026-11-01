package com.powerjob.archive.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "archive")
public class MvpProperties {
    private String workerId = "local-worker";
    private long lineId;
    private boolean asyncExecution;
    private String discoveryMode = "oss";
    private boolean debugLoop;
    private String archiveDate = "2026-10-30";
    private String businessTimezone = "Asia/Shanghai";
    private String deliveryCutoffTime = "02:00";
    private int groupSize = 5;
    private int maxTicks = 250;
    private String ossSourceEndpoint;
    private String ossSourceAccessKeyId;
    private String ossSourceAccessKeySecret;
    private String ossSourceBucket;
    private String ossSourcePrefix;
    private String localSourceDir = "/samples";
    private String workRoot = "/data/work";
    private String hzRoot = "/data/hz-oss";
    private String sgRoot = "/data/sg-oss";
    private String ftpRoot = "/data/ftp";
    private String pythonCommand = "python3";
    private String pythonScript = "/app/nodes/python/archive_repair.py";

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public long getLineId() { return lineId; }
    public void setLineId(long lineId) { this.lineId = lineId; }
    public boolean isAsyncExecution() { return asyncExecution; }
    public void setAsyncExecution(boolean asyncExecution) { this.asyncExecution = asyncExecution; }
    public String getDiscoveryMode() { return discoveryMode; }
    public void setDiscoveryMode(String discoveryMode) { this.discoveryMode = discoveryMode; }
    public boolean isDebugLoop() { return debugLoop; }
    public void setDebugLoop(boolean debugLoop) { this.debugLoop = debugLoop; }
    public String getArchiveDate() { return archiveDate; }
    public void setArchiveDate(String archiveDate) { this.archiveDate = archiveDate; }
    public String getBusinessTimezone() { return businessTimezone; }
    public void setBusinessTimezone(String businessTimezone) { this.businessTimezone = businessTimezone; }
    public String getDeliveryCutoffTime() { return deliveryCutoffTime; }
    public void setDeliveryCutoffTime(String deliveryCutoffTime) { this.deliveryCutoffTime = deliveryCutoffTime; }
    public int getGroupSize() { return groupSize; }
    public void setGroupSize(int groupSize) { this.groupSize = groupSize; }
    public int getMaxTicks() { return maxTicks; }
    public void setMaxTicks(int maxTicks) { this.maxTicks = maxTicks; }
    public String getOssSourceEndpoint() { return ossSourceEndpoint; }
    public void setOssSourceEndpoint(String ossSourceEndpoint) { this.ossSourceEndpoint = ossSourceEndpoint; }
    public String getOssSourceAccessKeyId() { return ossSourceAccessKeyId; }
    public void setOssSourceAccessKeyId(String ossSourceAccessKeyId) { this.ossSourceAccessKeyId = ossSourceAccessKeyId; }
    public String getOssSourceAccessKeySecret() { return ossSourceAccessKeySecret; }
    public void setOssSourceAccessKeySecret(String ossSourceAccessKeySecret) { this.ossSourceAccessKeySecret = ossSourceAccessKeySecret; }
    public String getOssSourceBucket() { return ossSourceBucket; }
    public void setOssSourceBucket(String ossSourceBucket) { this.ossSourceBucket = ossSourceBucket; }
    public String getOssSourcePrefix() { return ossSourcePrefix; }
    public void setOssSourcePrefix(String ossSourcePrefix) { this.ossSourcePrefix = ossSourcePrefix; }
    public String getLocalSourceDir() { return localSourceDir; }
    public void setLocalSourceDir(String localSourceDir) { this.localSourceDir = localSourceDir; }
    public String getWorkRoot() { return workRoot; }
    public void setWorkRoot(String workRoot) { this.workRoot = workRoot; }
    public String getHzRoot() { return hzRoot; }
    public void setHzRoot(String hzRoot) { this.hzRoot = hzRoot; }
    public String getSgRoot() { return sgRoot; }
    public void setSgRoot(String sgRoot) { this.sgRoot = sgRoot; }
    public String getFtpRoot() { return ftpRoot; }
    public void setFtpRoot(String ftpRoot) { this.ftpRoot = ftpRoot; }
    public String getPythonCommand() { return pythonCommand; }
    public void setPythonCommand(String pythonCommand) { this.pythonCommand = pythonCommand; }
    public String getPythonScript() { return pythonScript; }
    public void setPythonScript(String pythonScript) { this.pythonScript = pythonScript; }
}
