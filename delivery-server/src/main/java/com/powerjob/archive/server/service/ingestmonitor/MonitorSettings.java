package com.powerjob.archive.server.service.ingestmonitor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 监控独立于交付 DAG；唯一新增 Secret 是受控环境中的群机器人地址，禁止从 API 暴露。 */
@Component
@ConfigurationProperties(prefix = "archive.ingest-monitor")
public class MonitorSettings {
    private boolean enabled;
    private Duration continuousPeriod = Duration.ofMinutes(30);
    private Duration reportInterval = Duration.ofHours(1);
    private long pollIntervalMs = 60_000;
    private String pipelineCode = "archive-batch";
    private String webhookUrl = "";
    private int maxObjects = 1_000_000;
    private String dashboardUrl = "";
    public String getDashboardUrl() {return dashboardUrl;}
    public void setDashboardUrl(String value) {dashboardUrl=value;}

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Duration getContinuousPeriod() { return continuousPeriod; }
    public void setContinuousPeriod(Duration continuousPeriod) { this.continuousPeriod = continuousPeriod; }
    public Duration getReportInterval() { return reportInterval; }
    public void setReportInterval(Duration reportInterval) { this.reportInterval = reportInterval; }
    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
    public String getPipelineCode() { return pipelineCode; }
    public void setPipelineCode(String pipelineCode) { this.pipelineCode = pipelineCode; }
    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }
    public int getMaxObjects() { return maxObjects; }
    public void setMaxObjects(int maxObjects) { this.maxObjects = maxObjects; }

    public void validate() {
        // 配置错误在启动时失败，避免静默禁用播报或用无界清单耗尽 API 内存。
        if (continuousPeriod == null || continuousPeriod.compareTo(Duration.ofMinutes(1)) < 0
                || reportInterval == null || reportInterval.compareTo(Duration.ofMinutes(1)) < 0
                || pollIntervalMs < 60_000 || pollIntervalMs > continuousPeriod.toMillis()
                || maxObjects < 1 || pipelineCode == null || pipelineCode.isBlank()) {
            throw new IllegalArgumentException("Invalid ingest monitor timing or inventory limit");
        }
        WeComNotifier.validateAddress(webhookUrl);
        validateDashboard();

    }

    void validateDashboard() {
        // 卡片只跳转明确配置的只读页面；禁止用户信息、查询参数或 Secret 混入 URL。
        try {
            var uri=java.net.URI.create(dashboardUrl);
            if (!java.util.Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                    || !"/monitor".equals(uri.getPath()) || uri.getQuery()!=null || uri.getFragment()!=null) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {throw new IllegalArgumentException("A controlled read-only monitor URL is required");}
    }
}
