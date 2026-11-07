package com.powerjob.archive.core.domain;

import com.fasterxml.jackson.databind.JsonNode;

/** 厂商交付实体只保存业务账户和非 Secret 定位；节点凭据由 Pipeline 当前节点提供。 */
public record DeliveryEntity(
        long deliveryEntityId, String vendorCode, String entityCode, String entityName,
        String protocol, String accountName, JsonNode endpointLocator, boolean enabled) {
}
