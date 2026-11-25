package com.powerjob.archive.server.api;

import com.powerjob.archive.core.service.IngestSourceService;
import com.powerjob.archive.server.service.ingestmonitor.IngestMonitorService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ingest-sources")
public class IngestSourceController {
    private final IngestSourceService sources;
    private final IngestMonitorService monitor;
    public IngestSourceController(IngestSourceService sources, IngestMonitorService monitor) {
        this.sources = sources;
        this.monitor = monitor;
    }
    @GetMapping public List<Map<String, Object>> list() { return sources.list(); }

    @GetMapping("/monitor")
    public Map<String, Object> monitor() { return monitor.status(); }

    @GetMapping("/{sourceId}/trend")
    public List<Map<String, Object>> trend(@PathVariable long sourceId, @RequestParam(defaultValue = "24") int hours) {
        if (sourceId < 1 || hours < 1 || hours > 720) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceId must be positive and hours must be 1..720");
        }
        return monitor.trend(sourceId, hours);
    }

    @GetMapping("/{sourceId}/changes")
    public List<Map<String,Object>> changes(@PathVariable long sourceId,@RequestParam(defaultValue="24") int hours,@RequestParam(defaultValue="200") int limit) {
        if (sourceId<1 || hours<1 || hours>720 || limit<1 || limit>200) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid directory query");
        return monitor.changes(sourceId,hours,limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody SourceRequest request) {
        return sources.create(request.ossUri(), request.ossType());
    }

    public record SourceRequest(@NotBlank String ossUri, @NotBlank String ossType) {}
}
