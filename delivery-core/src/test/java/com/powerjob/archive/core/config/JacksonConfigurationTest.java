package com.powerjob.archive.core.config;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JacksonConfigurationTest {
    @Test void deliveryDatesRoundTripThroughApiObjectMapper() throws Exception {
        var mapper = new JacksonConfiguration().objectMapper();
        assertEquals(LocalDate.parse("2026-11-06"), mapper.readValue("\"2026-11-06\"", LocalDate.class));
        OffsetDateTime cutoff = OffsetDateTime.parse("2026-11-06T18:00:00+08:00");
        assertEquals(cutoff.toInstant(), mapper.readValue(mapper.writeValueAsString(cutoff), OffsetDateTime.class).toInstant());
    }
}
