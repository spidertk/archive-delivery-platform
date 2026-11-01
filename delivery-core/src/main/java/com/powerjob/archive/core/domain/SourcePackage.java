package com.powerjob.archive.core.domain;

import com.fasterxml.jackson.databind.JsonNode;

public record SourcePackage(
        long id,
        long pipelineVersionId,
        JsonNode pipelineSnapshot,
        String bucketName,
        String objectKey,
        String objectVersion,
        long sourceSize,
        String status,
        String runStatus,
        long productionLineId,
        String currentNode,
        String leaseOwner,
        long fence,
        JsonNode manifest) {
}
