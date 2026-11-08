package com.powerjob.archive.core.domain;

import com.fasterxml.jackson.databind.JsonNode;

/** 厂商交付实体保存业务账户、单服务器执行组和非 Secret 定位；节点凭据由 Pipeline 当前节点提供。 */
public record DeliveryEntity(
        long deliveryEntityId, String vendorCode, String entityCode, String entityName,
        String protocol, String accountName, String workerGroup, JsonNode endpointLocator, boolean enabled) {
}
