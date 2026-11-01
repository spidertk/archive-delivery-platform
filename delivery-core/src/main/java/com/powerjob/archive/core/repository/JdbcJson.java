package com.powerjob.archive.core.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class JdbcJson {
    private JdbcJson() {
    }

    static JsonNode read(ObjectMapper mapper, String value) {
        try {
            return value == null ? null : mapper.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSON stored in database", e);
        }
    }
}
