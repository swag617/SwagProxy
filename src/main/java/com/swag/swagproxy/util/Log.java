package com.swag.swagproxy.util;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.GZIPOutputStream;

/**
 * Simple console + rotating-file logger. SwagProxy's own lines are prefixed
 * "[SwagProxy]"; raw Velocity console passthrough is written unprefixed via
 * {@link #raw(String)} so it stays greppable/diffable against Velocity's own
 * log format.
 */
public final class Log {

    /** Enabled via -Dswagproxy.debug=true; keeps default operation quiet. */
    private static final boolean DEBUG = Boolean.getBoolean("swagproxy.debug");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static Path logsDir;
    private static Path currentLogFile;
    private static LocalDate currentDay;
    private static PrintStream fileOut;

    private Log() {
    }

    public static synchronized void init(Path logsDirectory) {
        logsDir = logsDirectory;
        try {
            Files.createDirectories(logsDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create logs directory: " + logsDir, e);
        }
        rotateIfNeeded();
    }

    private static void rotateIfNeeded() {
        LocalDate today = LocalDate.now();
        if (currentDay != null && currentDay.equals(today) && fileOut != null) {
            return;
        }
        try {
            Path latest = logsDir.resolve("latest.log");
            if (Files.exists(latest) && Files.size(latest) > 0) {
                archiveExisting(latest);
            }
            if (fileOut != null) {
                fileOut.close();
            }
            fileOut = new PrintStream(Files.newOutputStream(latest,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE), true);
            currentLogFile = latest;
            currentDay = today;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open log file", e);
        }
    }

    private static void archiveExisting(Path latest) throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"));
        Path archived = logsDir.resolve(stamp + ".log.gz");
        int suffix = 1;
        while (Files.exists(archived)) {
            archived = logsDir.resolve(stamp + "-" + (suffix++) + ".log.gz");
        }
        try (var in = Files.newInputStream(latest);
             var out = new GZIPOutputStream(Files.newOutputStream(archived))) {
            in.transferTo(out);
        }
        Files.deleteIfExists(latest);
    }

    private static synchronized void write(String line) {
        if (fileOut == null) {
            // Logging before init() (e.g. very early startup failure) — console only.
            System.out.println(line);
            return;
        }
        rotateIfNeeded();
        System.out.println(line);
        fileOut.println(line);
    }

    private static String timestamp() {
        return LocalDateTime.now().format(TS);
    }

    public static void info(String message) {
        write("[" + timestamp() + "] [SwagProxy] " + message);
    }

    /** Only printed when started with -Dswagproxy.debug=true; silent no-op otherwise. */
    public static void debug(String message) {
        if (DEBUG) {
            write("[" + timestamp() + "] [SwagProxy] [DEBUG] " + message);
        }
    }

    public static void warn(String message) {
        write("[" + timestamp() + "] [SwagProxy] [WARN] " + message);
    }

    public static void error(String message) {
        write("[" + timestamp() + "] [SwagProxy] [ERROR] " + message);
    }

    public static void error(String message, Throwable t) {
        write("[" + timestamp() + "] [SwagProxy] [ERROR] " + message + ": " + t);
    }

    /** Raw passthrough (e.g. Velocity's own console output) — no SwagProxy prefix added. */
    public static void raw(String line) {
        write(line);
    }

    public static synchronized void shutdown() {
        if (fileOut != null) {
            fileOut.flush();
            fileOut.close();
        }
    }
}
