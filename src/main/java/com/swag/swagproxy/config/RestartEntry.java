package com.swag.swagproxy.config;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

/** One entry of the "restart-schedule:" list. */
public final class RestartEntry {

    private final LocalTime time;
    private final ZoneId timezone;
    private final Set<DayOfWeek> days;

    public RestartEntry(LocalTime time, ZoneId timezone, Set<DayOfWeek> days) {
        this.time = time;
        this.timezone = timezone;
        this.days = days;
    }

    public LocalTime time() {
        return time;
    }

    public ZoneId timezone() {
        return timezone;
    }

    public Set<DayOfWeek> days() {
        return days;
    }

    @Override
    public String toString() {
        return time + " " + timezone + " " + days;
    }
}
