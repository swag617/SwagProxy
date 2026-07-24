package com.swag.swagproxy;

import java.nio.file.Path;

/**
 * Central definition of the on-disk working directory layout SwagProxy manages.
 * See §2 of the gameplan document for the canonical directory tree.
 */
public final class Layout {

    public final Path root;
    public final Path configFile;
    public final Path proxyDir;
    public final Path velocityJar;
    public final Path velocityToml;
    public final Path forwardingSecret;
    public final Path pluginsDir;
    public final Path geyserJar;
    public final Path floodgateJar;
    public final Path geyserConfigDir;
    public final Path geyserConfigFile;
    public final Path logsDir;
    public final Path backupsDir;
    public final Path dataDir;
    public final Path buildsJson;

    public Layout(Path root) {
        this.root = root;
        this.configFile = root.resolve("swagproxy.yml");
        this.proxyDir = root.resolve("proxy");
        this.velocityJar = proxyDir.resolve("velocity.jar");
        this.velocityToml = proxyDir.resolve("velocity.toml");
        this.forwardingSecret = proxyDir.resolve("forwarding.secret");
        this.pluginsDir = proxyDir.resolve("plugins");
        this.geyserJar = pluginsDir.resolve("Geyser-Velocity.jar");
        this.floodgateJar = pluginsDir.resolve("floodgate-velocity.jar");
        this.geyserConfigDir = pluginsDir.resolve("Geyser-Velocity");
        this.geyserConfigFile = geyserConfigDir.resolve("config.yml");
        this.logsDir = root.resolve("logs");
        this.backupsDir = root.resolve("backups");
        this.dataDir = root.resolve("data");
        this.buildsJson = dataDir.resolve("builds.json");
    }

    /** Path for the staged replacement of a live file, e.g. velocity.jar -> velocity.jar.staged. */
    public static Path stagedPathFor(Path live) {
        return live.resolveSibling(live.getFileName().toString() + ".staged");
    }
}
