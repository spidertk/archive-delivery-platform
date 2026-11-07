package com.powerjob.archive.server.api;

import com.powerjob.archive.core.service.IngestSourceService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
    public IngestSourceController(IngestSourceService sources) { this.sources = sources; }
    @GetMapping public List<Map<String, Object>> list() { return sources.list(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody SourceRequest request) {
        return sources.create(request.ossUri(), request.channelCode(), request.ossType());
    }

    public record SourceRequest(@NotBlank String ossUri, @NotBlank String channelCode, @NotBlank String ossType) {}
}
