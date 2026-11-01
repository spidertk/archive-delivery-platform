package com.powerjob.archive.core.domain;

import com.fasterxml.jackson.databind.JsonNode;

public record OutputPackage(
        long id,
        long sourcePackageId,
        long pipelineVersionId,
        JsonNode pipelineSnapshot,
        long productionGroupId,
        long productionLineId,
        String archiveDate,
        String deliveryDate,
        int groupSeq,
        String newPackageName,
        String status,
        String runStatus,
        String currentNode,
        String leaseOwner,
        long fence,
        int retryCount,
        JsonNode manifest) {
}
