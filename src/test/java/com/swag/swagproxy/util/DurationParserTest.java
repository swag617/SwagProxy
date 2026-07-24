package com.swag.swagproxy.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DurationParserTest {

    @Test
    void parsesBareSeconds() {
        assertEquals(Duration.ofSeconds(90), DurationParser.parse("90"));
    }

    @Test
    void parsesSingleUnits() {
        assertEquals(Duration.ofSeconds(10), DurationParser.parse("10s"));
        assertEquals(Duration.ofMinutes(5), DurationParser.parse("5m"));
        assertEquals(Duration.ofHours(2), DurationParser.parse("2h"));
    }

    @Test
    void parsesCombinedUnits() {
        assertEquals(Duration.ofHours(1).plusMinutes(30), DurationParser.parse("1h30m"));
        assertEquals(Duration.ofMinutes(10).plusSeconds(5), DurationParser.parse("10m5s"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("soon"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(""));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("10x"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("10m garbage"));
    }
}
