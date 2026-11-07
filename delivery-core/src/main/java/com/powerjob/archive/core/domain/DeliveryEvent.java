package com.powerjob.archive.core.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/** 不可变业务事件记录过滤、人工操作和跨日证据，载荷只允许脱敏结构化事实。 */
public record DeliveryEvent(
        long eventId, long deliveryId, Long taskId, Long processId, Long deliveryEntityId,
        Long dataId, String eventType, String severity, String reasonCode,
        String message, JsonNode payload, String operatorId, OffsetDateTime occurredAt) {
}
