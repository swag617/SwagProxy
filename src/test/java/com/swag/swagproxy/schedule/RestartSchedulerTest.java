package com.swag.swagproxy.schedule;

import com.swag.swagproxy.config.RestartEntry;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RestartSchedulerTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    @Test
    void movesToNextDayWhenTimeAlreadyPassedToday() {
        Set<DayOfWeek> allDays = EnumSet.allOf(DayOfWeek.class);
        RestartEntry entry = new RestartEntry(LocalTime.of(4, 30), NY, allDays);
        ZonedDateTime now = ZonedDateTime.of(2026, 7, 22, 10, 0, 0, 0, NY); // 10:00, after 04:30

        ZonedDateTime next = RestartScheduler.computeNextFireTime(entry, now);

        assertEquals(ZonedDateTime.of(2026, 7, 23, 4, 30, 0, 0, NY), next);
    }

    @Test
    void staysTodayWhenTimeStillAhead() {
        Set<DayOfWeek> allDays = EnumSet.allOf(DayOfWeek.class);
        RestartEntry entry = new RestartEntry(LocalTime.of(4, 30), NY, allDays);
        ZonedDateTime now = ZonedDateTime.of(2026, 7, 22, 1, 0, 0, 0, NY); // 01:00, before 04:30

        ZonedDateTime next = RestartScheduler.computeNextFireTime(entry, now);

        assertEquals(ZonedDateTime.of(2026, 7, 22, 4, 30, 0, 0, NY), next);
    }

    @Test
    void skipsToNextAllowedDayOfWeek() {
        // 2026-07-22 is a Wednesday. Only Fridays are allowed.
        RestartEntry entry = new RestartEntry(LocalTime.of(4, 30), NY, Set.of(DayOfWeek.FRIDAY));
        ZonedDateTime now = ZonedDateTime.of(2026, 7, 22, 1, 0, 0, 0, NY);

        ZonedDateTime next = RestartScheduler.computeNextFireTime(entry, now);

        assertEquals(DayOfWeek.FRIDAY, next.getDayOfWeek());
        assertEquals(ZonedDateTime.of(2026, 7, 24, 4, 30, 0, 0, NY), next);
    }

    @Test
    void respectsEntryTimezoneIndependentOfSystemDefault() {
        // A restart at 04:30 Tokyo time should compute correctly regardless of
        // what timezone "now" is expressed in, since we convert into the
        // entry's own zone first — this is the whole point of per-entry zones.
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        RestartEntry entry = new RestartEntry(LocalTime.of(4, 30), tokyo, EnumSet.allOf(DayOfWeek.class));
        ZonedDateTime nowInTokyo = ZonedDateTime.of(2026, 7, 22, 1, 0, 0, 0, tokyo);

        ZonedDateTime next = RestartScheduler.computeNextFireTime(entry, nowInTokyo);

        assertEquals(tokyo, next.getZone());
        assertEquals(ZonedDateTime.of(2026, 7, 22, 4, 30, 0, 0, tokyo), next);
    }
}
