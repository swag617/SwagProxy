package com.swag.swagproxy;

import com.swag.swagproxy.config.ConfigException;
import com.swag.swagproxy.config.ConfigManager;
import com.swag.swagproxy.config.SwagProxyConfig;
import com.swag.swagproxy.util.Log;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {

    public static void main(String[] args) {
        Path root = Path.of("").toAbsolutePath();
        Layout layout = new Layout(root);

        try {
            Files.createDirectories(layout.proxyDir);
            Files.createDirectories(layout.pluginsDir);
            Files.createDirectories(layout.backupsDir);
            Files.createDirectories(layout.dataDir);
        } catch (IOException e) {
            System.err.println("[SwagProxy] Could not create working directories under " + root + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        Log.init(layout.logsDir);
        Log.info("SwagProxy starting in " + root);

        SwagProxyConfig config;
        try {
            config = ConfigManager.loadOrCreate(layout.configFile);
        } catch (ConfigException e) {
            Log.error(e.getMessage());
            Log.shutdown();
            System.exit(1);
            return;
        }

        Log.info("Configuration loaded: " + config.servers().size() + " backend server(s), "
                + "java-port=" + config.javaPort() + ", bedrock-port=" + config.bedrockPort());

        Bootstrapper bootstrapper = new Bootstrapper(layout, config);
        try {
            bootstrapper.run();
        } catch (BootstrapException e) {
            Log.error(e.getMessage());
            Log.shutdown();
            System.exit(1);
            return;
        }

        // Everything past this point runs on daemon threads (process I/O passthrough,
        // the scheduler, the console reader) — park the main thread so the JVM stays
        // alive until it's killed (the shutdown hook registered in Bootstrapper then
        // gracefully stops Velocity).
        Object park = new Object();
        synchronized (park) {
            while (true) {
                try {
                    park.wait();
                } catch (InterruptedException ignored) {
                    // keep parking
                }
            }
        }
    }
}
