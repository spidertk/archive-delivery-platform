package com.powerjob.archive.core.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JdbcJson {
    private JdbcJson() {}

    public static JsonNode read(ObjectMapper mapper, String json) {
        try {
            return json == null ? null : mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid JSONB fact", e);
        }
    }
}
