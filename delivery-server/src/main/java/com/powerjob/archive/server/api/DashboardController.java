package com.powerjob.archive.server.api;

import com.powerjob.archive.server.service.DashboardQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
    private final DashboardQueryService dashboard;
    public DashboardController(DashboardQueryService dashboard) { this.dashboard = dashboard; }

    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam long deliveryId) { return dashboard.summary(deliveryId); }

    @GetMapping("/targets")
    public List<Map<String, Object>> targets(@RequestParam long deliveryId) { return dashboard.targets(deliveryId); }
}
