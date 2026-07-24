package com.swag.swagproxy.velocity;

import com.swag.swagproxy.BootstrapException;
import com.swag.swagproxy.Layout;
import com.swag.swagproxy.config.SwagProxyConfig;
import com.swag.swagproxy.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ensures Geyser-Velocity's config.yml exists (writing a bundled default
 * template on first run) and patches only the keys SwagProxy owns — the
 * Bedrock listen port and the Java auth-type needed for Floodgate — leaving
 * every other key and every comment exactly as the admin left them (§4.3).
 *
 * <p>Schema verified live 2026-07-22 by inspecting the config.yml a real
 * Geyser-Velocity 2.11.0 (config-version 7) instance generated on boot — see
 * DECISIONS.md. Note this is a newer schema than older GeyserMC docs/mirrors
 * describe: the old "remote:" section (address/port/auth-type) has been
 * split up — auth-type now lives under top-level "java:", and with
 * "advanced.java.use-direct-connection" defaulting true, plugin installs
 * don't need an explicit remote address/port at all.
 */
public final class GeyserConfigManager {

    private static final String DEFAULT_RESOURCE = "/default-geyser-config.yml";

    private GeyserConfigManager() {
    }

    public static void ensureAndPatch(Layout layout, SwagProxyConfig config) throws BootstrapException {
        Path file = layout.geyserConfigFile;
        boolean firstWrite = !Files.exists(file);
        if (firstWrite) {
            try {
                Files.createDirectories(file.getParent());
                try (InputStream in = GeyserConfigManager.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                    if (in == null) {
                        throw new BootstrapException("Internal error: missing bundled default-geyser-config.yml resource.");
                    }
                    Files.copy(in, file);
                }
                Log.info("Generated default Geyser config at " + file);
            } catch (IOException e) {
                throw new BootstrapException("Could not write default Geyser config to " + file + ": " + e.getMessage(), e);
            }
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BootstrapException("Could not read Geyser config at " + file + ": " + e.getMessage(), e);
        }

        patchKey(lines, "bedrock", "port", String.valueOf(config.bedrockPort()));
        patchKey(lines, "java", "auth-type", "floodgate");

        try {
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BootstrapException("Could not write patched Geyser config to " + file + ": " + e.getMessage(), e);
        }
        Log.info("Geyser config patched: bedrock.port=" + config.bedrockPort() + ", java.auth-type=floodgate");
    }

    /**
     * Replaces the scalar value of {@code key} within the given top-level
     * {@code section} (or top-level, if section is null), preserving
     * indentation and any trailing inline comment. A section is considered
     * to span from its column-0 header line until the next column-0,
     * non-blank line.
     */
    private static void patchKey(List<String> lines, String section, String key, String newValue) {
        int start = 0;
        int end = lines.size();
        if (section != null) {
            Pattern header = Pattern.compile("^" + Pattern.quote(section) + ":\\s*$");
            int headerIdx = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (header.matcher(lines.get(i)).matches()) {
                    headerIdx = i;
                    break;
                }
            }
            if (headerIdx == -1) {
                Log.warn("Geyser config: could not find section \"" + section + ":\" to patch \"" + key + "\".");
                return;
            }
            start = headerIdx + 1;
            end = lines.size();
            for (int i = start; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!line.isBlank() && !Character.isWhitespace(line.charAt(0))) {
                    end = i;
                    break;
                }
            }
        }

        Pattern keyLine = Pattern.compile("^(\\s*)" + Pattern.quote(key) + ":(\\s*)([^#]*?)(\\s*#.*)?$");
        for (int i = start; i < end; i++) {
            Matcher m = keyLine.matcher(lines.get(i));
            if (m.matches()) {
                String indent = m.group(1);
                String trailingComment = m.group(4) == null ? "" : m.group(4);
                lines.set(i, indent + key + ": " + newValue + trailingComment);
                return;
            }
        }
        Log.warn("Geyser config: could not find key \"" + key + "\" in section \""
                + (section == null ? "(top-level)" : section) + "\" to patch.");
    }
}
