package com.swag.swagproxy.util;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses simple human durations like "10m", "1h30m", "90s", or a bare number of seconds. */
public final class DurationParser {

    private static final Pattern PART = Pattern.compile("(\\d+)([hms])");
    private static final Pattern BARE_NUMBER = Pattern.compile("\\d+");

    private DurationParser() {
    }

    public static Duration parse(String input) {
        String s = input.trim().toLowerCase();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Empty duration.");
        }
        if (BARE_NUMBER.matcher(s).matches()) {
            return Duration.ofSeconds(Long.parseLong(s));
        }
        Matcher m = PART.matcher(s);
        long totalSeconds = 0;
        int matchedChars = 0;
        boolean any = false;
        while (m.find()) {
            any = true;
            matchedChars += m.group().length();
            long value = Long.parseLong(m.group(1));
            totalSeconds += switch (m.group(2)) {
                case "h" -> value * 3600;
                case "m" -> value * 60;
                default -> value;
            };
        }
        if (!any || matchedChars != s.length()) {
            throw new IllegalArgumentException("Could not parse duration \"" + input
                    + "\" — expected something like \"10m\", \"1h30m\", \"90s\", or a bare number of seconds.");
        }
        return Duration.ofSeconds(totalSeconds);
    }
}
