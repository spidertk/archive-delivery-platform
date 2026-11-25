package com.powerjob.archive.server.service.ingestmonitor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 仅 API 部署显式启用监控时注册调度，不向八个 Worker 添加重复任务。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "archive.ingest-monitor.enabled", havingValue = "true")
public class MonitorScheduling {}
