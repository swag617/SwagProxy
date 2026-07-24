package com.swag.swagproxy.velocity;

import com.swag.swagproxy.BootstrapException;
import com.swag.swagproxy.Layout;
import com.swag.swagproxy.config.SwagProxyConfig;
import com.swag.swagproxy.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/**
 * Generates velocity.toml from scratch on every boot (§4.3 — unlike Geyser's
 * config, this file is fully owned by SwagProxy and never hand-patched).
 * Schema verified live 2026-07-22 against PaperMC/Velocity's
 * default-velocity.toml on the dev/3.0.0 branch (config-version "2.8") — see
 * DECISIONS.md.
 */
public final class VelocityTomlWriter {

    private static final String MANAGED_BANNER = """
            # =====================================================================
            #  THIS FILE IS MANAGED BY SWAGPROXY.
            #  It is regenerated from swagproxy.yml on every boot — edit
            #  swagproxy.yml instead. Manual changes made here will be overwritten.
            # =====================================================================
            """;

    private VelocityTomlWriter() {
    }

    public static void write(Layout layout, SwagProxyConfig config) throws BootstrapException {
        String toml = render(config);
        try {
            Files.createDirectories(layout.proxyDir);
            Files.writeString(layout.velocityToml, toml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BootstrapException("Could not write " + layout.velocityToml + ": " + e.getMessage(), e);
        }
        Log.info("Generated velocity.toml (bind=" + config.bind() + ":" + config.javaPort()
                + ", force-key-authentication=false for Bedrock chat compatibility)");
    }

    private static String render(SwagProxyConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append(MANAGED_BANNER).append('\n');
        sb.append("config-version = \"2.8\"\n\n");
        sb.append("bind = \"").append(config.bind()).append(':').append(config.javaPort()).append("\"\n\n");
        sb.append("motd = \"").append(escapeToml(config.motd())).append("\"\n\n");
        sb.append("show-max-players = ").append(config.maxPlayers()).append("\n\n");
        sb.append("online-mode = ").append(config.onlineMode()).append("\n\n");
        sb.append("""
                # Disabled automatically by SwagProxy: Bedrock/Floodgate players have no
                # Mojang-issued chat signing key, so this must be off for their chat to work.
                """);
        sb.append("force-key-authentication = false\n\n");
        sb.append("prevent-client-proxy-connections = false\n\n");
        sb.append("""
                # SwagProxy manages "modern" forwarding only — it generates and maintains
                # forwarding.secret for you. See README.md for the backend paper-global.yml
                # setup required to match.
                """);
        sb.append("player-info-forwarding-mode = \"").append(mapForwardingMode(config.forwarding())).append("\"\n");
        sb.append("forwarding-secret-file = \"forwarding.secret\"\n\n");
        sb.append("announce-forge = false\n");
        sb.append("kick-existing-players = false\n");
        sb.append("ping-passthrough = \"DISABLED\"\n");
        sb.append("sample-players-in-ping = false\n");
        sb.append("enable-player-address-logging = true\n\n");

        sb.append("[packet-limiter]\n");
        sb.append("interval = 7\n");
        sb.append("packets-per-second = -1\n");
        sb.append("bytes-per-second = -1\n");
        sb.append("decompressed-bytes-per-second = 5242880\n\n");

        sb.append("[servers]\n");
        sb.append("# Generated from swagproxy.yml's 'servers' map — edit it there, not here.\n");
        for (Map.Entry<String, String> e : config.servers().entrySet()) {
            sb.append(tomlKey(e.getKey())).append(" = \"").append(escapeToml(e.getValue())).append("\"\n");
        }
        sb.append('\n');
        sb.append("try = [\n");
        for (String name : config.tryOrder()) {
            sb.append("    \"").append(escapeToml(name)).append("\",\n");
        }
        sb.append("]\n\n");

        sb.append("[forced-hosts]\n");
        sb.append("# SwagProxy does not manage forced hosts in v1. Since this entire file is\n");
        sb.append("# regenerated from swagproxy.yml on every boot, hand-added entries here\n");
        sb.append("# would not persist across restarts.\n\n");

        sb.append("[advanced]\n");
        sb.append("compression-threshold = 256\n");
        sb.append("compression-level = -1\n");
        sb.append("login-ratelimit = 3000\n");
        sb.append("connection-timeout = 5000\n");
        sb.append("read-timeout = 30000\n");
        sb.append("haproxy-protocol = false\n");
        sb.append("tcp-fast-open = false\n");
        sb.append("bungee-plugin-message-channel = true\n");
        sb.append("show-ping-requests = false\n");
        sb.append("failover-on-unexpected-server-disconnect = true\n");
        sb.append("announce-proxy-commands = true\n");
        sb.append("log-command-executions = false\n");
        sb.append("log-player-connections = true\n");
        sb.append("accepts-transfers = false\n");
        sb.append("enable-reuse-port = false\n");
        sb.append("command-rate-limit = 50\n");
        sb.append("forward-commands-if-rate-limited = true\n");
        sb.append("kick-after-rate-limited-commands = 0\n");
        sb.append("tab-complete-rate-limit = 10\n");
        sb.append("kick-after-rate-limited-tab-completes = 0\n\n");

        sb.append("[query]\n");
        sb.append("enabled = false\n");
        sb.append("port = ").append(config.javaPort()).append('\n');
        sb.append("map = \"Velocity\"\n");
        sb.append("show-plugins = false\n");

        return sb.toString();
    }

    private static String mapForwardingMode(String forwarding) {
        // swagproxy.yml only allows "modern" today (validated in ConfigManager);
        // kept as a mapping in case additional modes are supported later.
        return switch (forwarding.toLowerCase()) {
            case "modern" -> "modern";
            case "legacy" -> "legacy";
            case "bungeeguard" -> "bungeeguard";
            case "none" -> "none";
            default -> "modern";
        };
    }

    private static String tomlKey(String key) {
        // Bare TOML keys allow [A-Za-z0-9_-]; quote anything else as a basic string.
        if (key.matches("[A-Za-z0-9_-]+")) {
            return key;
        }
        return "\"" + escapeToml(key) + "\"";
    }

    private static String escapeToml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
