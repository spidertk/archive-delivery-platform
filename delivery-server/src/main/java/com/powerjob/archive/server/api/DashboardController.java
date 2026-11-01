package com.powerjob.archive.server.api;

import com.powerjob.archive.server.service.DashboardQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class DashboardController {
    private final DashboardQueryService dashboard;

    public DashboardController(DashboardQueryService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/dashboard/summary")
    public Map<String, Object> summary(@RequestParam long runId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deliveryDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate archiveDate,
            @RequestParam(required = false) Long lineId,
            @RequestParam(required = false) Boolean rolledOver) {
        return dashboard.summary(new DashboardQueryService.Filter(runId, deliveryDate, archiveDate, lineId, rolledOver));
    }

    @GetMapping("/dashboard/groups")
    public List<Map<String, Object>> groups(@RequestParam long runId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deliveryDate) {
        return dashboard.groups(new DashboardQueryService.Filter(runId, deliveryDate, null, null, null));
    }

    @GetMapping("/packages")
    public Map<String, Object> packages(@RequestParam long runId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deliveryDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate archiveDate,
            @RequestParam(required = false) Long lineId,
            @RequestParam(required = false) Boolean rolledOver,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return dashboard.packages(new DashboardQueryService.Filter(runId, deliveryDate, archiveDate, lineId, rolledOver),
                status, keyword, page, size);
    }

    @GetMapping("/packages/{id}")
    public Map<String, Object> packageDetail(@PathVariable long id) {
        return dashboard.packageDetail(id);
    }

    @GetMapping("/packages/{id}/timeline")
    public Map<String, Object> timeline(@PathVariable long id) {
        return dashboard.timeline(id);
    }

    @GetMapping("/runs/{runId}")
    public Map<String, Object> run(@PathVariable long runId) {
        return dashboard.run(runId);
    }
}
