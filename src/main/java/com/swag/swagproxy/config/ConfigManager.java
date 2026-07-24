package com.swag.swagproxy.config;

import com.swag.swagproxy.util.Log;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads swagproxy.yml (generating a commented default on first run) and
 * validates it into a {@link SwagProxyConfig}. Every validation failure is
 * collected and reported together as a human-readable message — never a raw
 * stack trace — per the project's quality bar.
 */
public final class ConfigManager {

    private static final String DEFAULT_RESOURCE = "/default-swagproxy.yml";

    private final List<String> errors = new ArrayList<>();

    private ConfigManager() {
    }

    public static SwagProxyConfig loadOrCreate(Path configFile) throws ConfigException {
        if (!Files.exists(configFile)) {
            writeDefault(configFile);
            Log.info("Generated default config at " + configFile.toAbsolutePath());
        }

        Object root;
        try (var in = Files.newInputStream(configFile)) {
            root = new Yaml().load(in);
        } catch (IOException e) {
            throw new ConfigException("Could not read " + configFile.toAbsolutePath() + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // SnakeYAML throws unchecked YAMLException/ScannerException on malformed YAML.
            throw new ConfigException("swagproxy.yml is not valid YAML: " + e.getMessage(), e);
        }

        if (!(root instanceof Map)) {
            throw new ConfigException("swagproxy.yml must contain a YAML mapping at the top level.");
        }

        ConfigManager manager = new ConfigManager();
        @SuppressWarnings("unchecked")
        SwagProxyConfig config = manager.parse((Map<String, Object>) root);

        if (!manager.errors.isEmpty()) {
            throw ConfigException.fromErrors(manager.errors);
        }
        return config;
    }

    private static void writeDefault(Path configFile) throws ConfigException {
        try {
            Files.createDirectories(configFile.toAbsolutePath().getParent());
            try (InputStream in = ConfigManager.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                if (in == null) {
                    throw new ConfigException("Internal error: missing bundled default-swagproxy.yml resource.");
                }
                Files.copy(in, configFile);
            }
        } catch (IOException e) {
            throw new ConfigException("Could not write default config to " + configFile.toAbsolutePath()
                    + ": " + e.getMessage(), e);
        }
    }

    private SwagProxyConfig parse(Map<String, Object> root) {
        int javaPort = intVal(root, "java-port", 25565);
        int bedrockPort = intVal(root, "bedrock-port", 19132);
        String bind = stringVal(root, "bind", "0.0.0.0");
        String motd = stringVal(root, "motd", "A SwagProxy Server");
        int maxPlayers = intVal(root, "max-players", 100);
        boolean onlineMode = boolVal(root, "online-mode", true);
        Map<String, String> servers = stringMapVal(root, "servers");
        List<String> tryOrder = stringListVal(root, "try-order");
        String forwarding = stringVal(root, "forwarding", "modern");

        if (!forwarding.equalsIgnoreCase("modern")) {
            errors.add("'forwarding' must be \"modern\" (the only mode SwagProxy manages), found: " + forwarding);
        }
        for (String name : tryOrder) {
            if (!servers.containsKey(name)) {
                errors.add("'try-order' references unknown server \"" + name + "\" (not present in 'servers')");
            }
        }

        UpdatesConfig updates = parseUpdates(root.get("updates"));
        List<RestartEntry> restartSchedule = parseRestartSchedule(root.get("restart-schedule"));
        List<Integer> warnings = parseWarnings(root.get("warnings"));
        String warningMessage = stringVal(root, "warning-message", "<yellow>Restarting in <white>{time}</white>!</yellow>");
        String restartMessage = stringVal(root, "restart-message", "<red>Restarting now!</red>");
        String warningCommandTemplate = stringVal(root, "warning-command-template", "alert {message}");
        SupervisorConfig supervisor = parseSupervisor(root.get("supervisor"));

        return new SwagProxyConfig(javaPort, bedrockPort, bind, motd, maxPlayers, onlineMode, servers, tryOrder,
                forwarding, updates, restartSchedule, warnings, warningMessage, restartMessage,
                warningCommandTemplate, supervisor);
    }

    private UpdatesConfig parseUpdates(Object node) {
        Map<String, Object> map = asMap(node, "updates");
        ComponentUpdateConfig velocity = parseComponent(map, "velocity", true);
        ComponentUpdateConfig geyser = parseComponent(map, "geyser", false);
        ComponentUpdateConfig floodgate = parseComponent(map, "floodgate", false);
        int checkIntervalMinutes = intVal(map, "check-interval-minutes", 60);
        String apply = stringVal(map, "apply", "on-restart");
        if (!apply.equalsIgnoreCase("on-restart")) {
            errors.add("'updates.apply' must be \"on-restart\" (the only supported mode in v1), found: " + apply);
        }
        // Keep in sync with HttpUtil.DEFAULT_CONTACT_PLACEHOLDER — duplicated (not imported) to
        // avoid a config -> download package dependency cycle.
        String contact = stringVal(map, "contact", "https://github.com/SwagDev");
        return new UpdatesConfig(velocity, geyser, floodgate, checkIntervalMinutes, apply, contact);
    }

    private ComponentUpdateConfig parseComponent(Map<String, Object> updatesMap, String key, boolean hasChannel) {
        Map<String, Object> map = asMap(updatesMap.get(key), "updates." + key);
        boolean auto = boolVal(map, "auto", true);
        String channel = null;
        if (hasChannel) {
            channel = stringVal(map, "channel", "stable");
            if (!channel.equalsIgnoreCase("stable") && !channel.equalsIgnoreCase("experimental")) {
                errors.add("'updates.velocity.channel' must be \"stable\" or \"experimental\", found: " + channel);
            }
        }
        String pin = stringVal(map, "pin", null);
        if (pin != null && pin.isBlank()) {
            errors.add("'updates." + key + ".pin' must not be blank — omit the key entirely to leave it unpinned.");
            pin = null;
        }
        return new ComponentUpdateConfig(auto, channel, pin);
    }

    private List<RestartEntry> parseRestartSchedule(Object node) {
        List<RestartEntry> result = new ArrayList<>();
        if (node == null) {
            return result;
        }
        if (!(node instanceof List<?> list)) {
            errors.add("'restart-schedule' must be a list of entries.");
            return result;
        }
        for (Object item : list) {
            Map<String, Object> map = asMap(item, "restart-schedule[]");
            if (map.isEmpty()) {
                continue;
            }
            String timeStr = stringVal(map, "time", null);
            LocalTime time = null;
            if (timeStr == null) {
                errors.add("A 'restart-schedule' entry is missing required key 'time' (e.g. \"04:30\").");
            } else {
                try {
                    time = LocalTime.parse(timeStr);
                } catch (DateTimeParseException e) {
                    errors.add("'restart-schedule' entry has invalid 'time' \"" + timeStr
                            + "\" — expected 24-hour HH:mm, e.g. \"04:30\".");
                }
            }

            String tzStr = stringVal(map, "timezone", null);
            ZoneId zone = null;
            if (tzStr == null) {
                errors.add("A 'restart-schedule' entry is missing required key 'timezone' (e.g. \"America/New_York\").");
            } else {
                try {
                    zone = ZoneId.of(tzStr);
                } catch (RuntimeException e) {
                    errors.add("'restart-schedule' entry has invalid 'timezone' \"" + tzStr
                            + "\" — must be a valid IANA zone id, e.g. \"America/New_York\" or \"UTC\".");
                }
            }

            Set<DayOfWeek> days = new LinkedHashSet<>();
            Object daysNode = map.get("days");
            if (daysNode == null) {
                days.addAll(List.of(DayOfWeek.values()));
            } else if (daysNode instanceof List<?> dayList) {
                for (Object d : dayList) {
                    String s = String.valueOf(d).trim().toUpperCase();
                    DayOfWeek parsed = parseDay(s);
                    if (parsed == null) {
                        errors.add("'restart-schedule' entry has invalid day \"" + d
                                + "\" — expected one of MON, TUE, WED, THU, FRI, SAT, SUN.");
                    } else {
                        days.add(parsed);
                    }
                }
            } else {
                errors.add("'restart-schedule' entry's 'days' must be a list, e.g. [MON, TUE].");
            }

            if (time != null && zone != null) {
                result.add(new RestartEntry(time, zone, days));
            }
        }
        return result;
    }

    private static DayOfWeek parseDay(String s) {
        return switch (s) {
            case "MON", "MONDAY" -> DayOfWeek.MONDAY;
            case "TUE", "TUESDAY" -> DayOfWeek.TUESDAY;
            case "WED", "WEDNESDAY" -> DayOfWeek.WEDNESDAY;
            case "THU", "THURSDAY" -> DayOfWeek.THURSDAY;
            case "FRI", "FRIDAY" -> DayOfWeek.FRIDAY;
            case "SAT", "SATURDAY" -> DayOfWeek.SATURDAY;
            case "SUN", "SUNDAY" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    private List<Integer> parseWarnings(Object node) {
        List<Integer> result = new ArrayList<>();
        if (node == null) {
            result.addAll(List.of(600, 300, 60, 10));
            return result;
        }
        if (!(node instanceof List<?> list)) {
            errors.add("'warnings' must be a list of second-offsets, e.g. [600, 300, 60, 10].");
            return result;
        }
        for (Object o : list) {
            if (o instanceof Number n) {
                result.add(n.intValue());
            } else {
                errors.add("'warnings' entries must be numbers (seconds), found: " + o);
            }
        }
        result.sort((a, b) -> b - a);
        return result;
    }

    private SupervisorConfig parseSupervisor(Object node) {
        Map<String, Object> map = asMap(node, "supervisor");
        boolean restartOnCrash = boolVal(map, "restart-on-crash", true);
        int crashLoopThreshold = intVal(map, "crash-loop-threshold", 3);
        int rollbackWindowSeconds = intVal(map, "rollback-window-seconds", 120);
        List<String> jvmArgs = stringListVal(map, "jvm-args");
        return new SupervisorConfig(restartOnCrash, crashLoopThreshold, rollbackWindowSeconds, jvmArgs);
    }

    // --- typed extraction helpers, collecting human-readable errors ---

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object node, String path) {
        if (node == null) {
            return new LinkedHashMap<>();
        }
        if (node instanceof Map) {
            return (Map<String, Object>) node;
        }
        errors.add("'" + path + "' must be a mapping of keys, but was: " + node);
        return new LinkedHashMap<>();
    }

    private int intVal(Map<String, Object> map, String key, int def) {
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        errors.add("'" + key + "' must be a whole number, found: " + v);
        return def;
    }

    private boolean boolVal(Map<String, Object> map, String key, boolean def) {
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        errors.add("'" + key + "' must be true or false, found: " + v);
        return def;
    }

    private String stringVal(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        if (v == null) {
            return def;
        }
        return String.valueOf(v);
    }

    private Map<String, String> stringMapVal(Map<String, Object> map, String key) {
        Map<String, String> result = new LinkedHashMap<>();
        Object v = map.get(key);
        if (v == null) {
            errors.add("Missing required section '" + key + "'.");
            return result;
        }
        if (!(v instanceof Map<?, ?> m)) {
            errors.add("'" + key + "' must be a mapping of name -> host:port.");
            return result;
        }
        for (var e : m.entrySet()) {
            result.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        }
        return result;
    }

    private List<String> stringListVal(Map<String, Object> map, String key) {
        List<String> result = new ArrayList<>();
        Object v = map.get(key);
        if (v == null) {
            return result;
        }
        if (!(v instanceof List<?> list)) {
            errors.add("'" + key + "' must be a list.");
            return result;
        }
        for (Object o : list) {
            result.add(String.valueOf(o));
        }
        return result;
    }
}
