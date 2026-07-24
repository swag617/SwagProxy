package com.swag.swagproxy;

import com.swag.swagproxy.config.SwagProxyConfig;
import com.swag.swagproxy.download.BuildTracker;
import com.swag.swagproxy.download.DownloadException;
import com.swag.swagproxy.download.HttpUtil;
import com.swag.swagproxy.download.UpdateManager;
import com.swag.swagproxy.process.ConsoleRouter;
import com.swag.swagproxy.process.ProxySupervisor;
import com.swag.swagproxy.schedule.RestartScheduler;
import com.swag.swagproxy.util.Log;
import com.swag.swagproxy.velocity.GeyserConfigManager;
import com.swag.swagproxy.velocity.VelocityTomlWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Orchestrates SwagProxy's startup sequence (§4.1): ensure jars are present,
 * generate/patch Velocity + Geyser config, then launch the supervised
 * Velocity process.
 */
public final class Bootstrapper {

    private final Layout layout;
    private final SwagProxyConfig config;

    private ProxySupervisor supervisor;
    private UpdateManager updateManager;
    private RestartScheduler restartScheduler;
    private ConsoleRouter consoleRouter;

    public Bootstrapper(Layout layout, SwagProxyConfig config) {
        this.layout = layout;
        this.config = config;
    }

    public ProxySupervisor supervisor() {
        return supervisor;
    }

    public UpdateManager updateManager() {
        return updateManager;
    }

    public RestartScheduler restartScheduler() {
        return restartScheduler;
    }

    public void run() throws BootstrapException {
        HttpUtil.configure(config.updates().contact());
        if (HttpUtil.DEFAULT_CONTACT_PLACEHOLDER.equals(config.updates().contact())) {
            Log.info("updates.contact is still the default placeholder — set it to your own URL or email "
                    + "in swagproxy.yml (PaperMC's download API policy requires identifiable contact info).");
        }

        BuildTracker tracker = BuildTracker.load(layout.buildsJson);
        updateManager = new UpdateManager(layout, config, tracker);

        ensureComponentInstalled(UpdateManager.COMPONENT_VELOCITY);
        ensureComponentInstalled(UpdateManager.COMPONENT_GEYSER);
        ensureComponentInstalled(UpdateManager.COMPONENT_FLOODGATE);

        Log.info("Checking for component updates...");
        updateManager.checkAndStageAll();

        VelocityTomlWriter.write(layout, config);
        ensureForwardingSecret();
        GeyserConfigManager.ensureAndPatch(layout, config);

        supervisor = new ProxySupervisor(layout, config, updateManager);
        supervisor.launchInitial();

        restartScheduler = new RestartScheduler(config, supervisor, updateManager);
        restartScheduler.start();

        consoleRouter = new ConsoleRouter(supervisor, restartScheduler, updateManager);
        Thread consoleThread = new Thread(consoleRouter, "swagproxy-console");
        consoleThread.setDaemon(true);
        consoleThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("SwagProxy is shutting down...");
            restartScheduler.shutdown();
            supervisor.shutdownForExit();
            Log.shutdown();
        }, "swagproxy-shutdown-hook"));

        Log.info("SwagProxy bootstrap complete — Velocity is starting. Type 'help' for console commands.");
    }

    private void ensureComponentInstalled(String component) throws BootstrapException {
        try {
            updateManager.ensureInstalled(component);
        } catch (DownloadException e) {
            throw new BootstrapException("Could not obtain " + component + " (required for first boot): "
                    + e.getMessage(), e);
        }
    }

    private void ensureForwardingSecret() throws BootstrapException {
        if (Files.exists(layout.forwardingSecret)) {
            return;
        }
        try {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            Files.createDirectories(layout.proxyDir);
            Files.writeString(layout.forwardingSecret, secret);
            Log.info("Generated a new forwarding.secret at " + layout.forwardingSecret
                    + " — paste its contents into every backend's paper-global.yml (see README).");
        } catch (IOException e) {
            throw new BootstrapException("Could not write forwarding.secret: " + e.getMessage(), e);
        }
    }
}
