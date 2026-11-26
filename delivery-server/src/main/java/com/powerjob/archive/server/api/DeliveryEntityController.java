package com.powerjob.archive.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.powerjob.archive.core.domain.DeliveryEntity;
import com.powerjob.archive.core.service.DeliveryEntityService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/delivery-entities")
public class DeliveryEntityController {
    private final DeliveryEntityService entities;
    public DeliveryEntityController(DeliveryEntityService entities) { this.entities = entities; }

    @GetMapping public List<DeliveryEntity> list() { return entities.list(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeliveryEntity create(@Valid @RequestBody EntityRequest request) {
        return entities.create(request.vendorCode(), request.entityCode(), request.entityName(), request.protocol(),
                request.accountName(), request.workerGroup(), request.endpointLocator());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }

    public record EntityRequest(@NotBlank String vendorCode, @NotBlank String entityCode, @NotBlank String entityName,
                                @NotBlank String protocol, @NotBlank String accountName, @NotBlank String workerGroup,
                                @NotNull JsonNode endpointLocator) {}

    /** 运维仅替换已核对的 FTP 地址；业务键、账户和机器组不在可修改字段内。 */
    @PutMapping("/{vendorCode}/{entityCode}/endpoint")
    public DeliveryEntity updateEndpoint(@PathVariable String vendorCode, @PathVariable String entityCode,
                                         @Valid @RequestBody EndpointRequest request) {
        return entities.updateEndpoint(vendorCode, entityCode, request.expectedEndpointLocator(), request.endpointLocator());
    }

    public record EndpointRequest(@NotNull JsonNode expectedEndpointLocator, @NotNull JsonNode endpointLocator) {}
}
