package com.powerjob.archive.core.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.powerjob.archive.core.domain.OutputPackage;
import com.powerjob.archive.core.service.FileTransferService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

abstract class AbstractOutputNode {
    final FileTransferService files;
    final JdbcTemplate jdbc;

    AbstractOutputNode(FileTransferService files, JdbcTemplate jdbc) {
        this.files = files;
        this.jdbc = jdbc;
    }

    JsonNode artifact(OutputPackage output) {
        return output.manifest().required("artifacts").required(0);
    }

    Path artifactPath(OutputPackage output) {
        return Path.of(artifact(output).required("path").asText());
    }

    String sha(OutputPackage output) {
        return artifact(output).required("sha256").asText();
    }

    String relativePath(OutputPackage output) {
        String provider = jdbc.queryForObject("SELECT provider_code FROM production_line WHERE id=?",
                String.class, output.productionLineId());
        return provider + "/" + output.archiveDate() + "/" + output.newPackageName();
    }
}
