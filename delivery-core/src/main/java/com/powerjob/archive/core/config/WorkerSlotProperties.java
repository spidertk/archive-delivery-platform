package com.powerjob.archive.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "archive.worker-slots")
public class WorkerSlotProperties {
    private int sourceSlotsPerLine = 1;
    private int outputSlotsGlobal = 10;
    private int maxAttempts = 5;

    public int getSourceSlotsPerLine() { return sourceSlotsPerLine; }
    public void setSourceSlotsPerLine(int sourceSlotsPerLine) { this.sourceSlotsPerLine = sourceSlotsPerLine; }
    public int getOutputSlotsGlobal() { return outputSlotsGlobal; }
    public void setOutputSlotsGlobal(int outputSlotsGlobal) { this.outputSlotsGlobal = outputSlotsGlobal; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
}
