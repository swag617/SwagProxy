package com.swag.swagproxy.process;

import com.swag.swagproxy.download.UpdateManager;
import com.swag.swagproxy.schedule.RestartScheduler;
import com.swag.swagproxy.util.DurationParser;
import com.swag.swagproxy.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;

/**
 * Reads SwagProxy's own console (stdin) on a dedicated thread. Recognized
 * supervisor commands (§4.4): "restart now|in <time>", "update check",
 * "update apply", "status", "help". Anything else is forwarded to Velocity's
 * console untouched.
 */
public final class ConsoleRouter implements Runnable {

    private final ProxySupervisor supervisor;
    private final RestartScheduler scheduler;
    private final UpdateManager updateManager;
    private volatile boolean running = true;

    public ConsoleRouter(ProxySupervisor supervisor, RestartScheduler scheduler, UpdateManager updateManager) {
        this.supervisor = supervisor;
        this.scheduler = scheduler;
        this.updateManager = updateManager;
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                handle(line.trim());
            }
        } catch (IOException e) {
            Log.error("Console input reader failed", e);
        }
    }

    private void handle(String line) {
        if (line.isEmpty()) {
            return;
        }
        String lower = line.toLowerCase();

        if (lower.equals("status")) {
            Log.info(supervisor.status());
            return;
        }
        if (lower.equals("help")) {
            printHelp();
            return;
        }
        if (lower.equals("restart now")) {
            scheduler.restartNow();
            return;
        }
        if (lower.startsWith("restart in ")) {
            String arg = line.substring("restart in ".length()).trim();
            try {
                Duration delay = DurationParser.parse(arg);
                scheduler.restartIn(delay);
            } catch (IllegalArgumentException e) {
                Log.error(e.getMessage());
            }
            return;
        }
        if (lower.equals("update check")) {
            Log.info("Checking for component updates...");
            updateManager.checkAndStageAll();
            Log.info("Update check complete.");
            return;
        }
        if (lower.equals("update apply")) {
            Log.info("Applying any staged updates now (this will restart the proxy)...");
            supervisor.restartNow("Applying staged updates.");
            return;
        }

        // Not a supervisor command — forward untouched to Velocity's console.
        supervisor.sendToVelocity(line);
    }

    private void printHelp() {
        Log.info("""
                SwagProxy console commands:
                  status              - show supervisor/process status
                  restart now         - gracefully restart Velocity now (applies staged updates)
                  restart in <time>   - schedule a one-off restart, e.g. "restart in 10m"
                  update check        - check for and stage component updates immediately
                  update apply        - alias for restart now, phrased for applying updates
                  help                - show this message
                Anything else is forwarded directly to Velocity's own console.""");
    }
}
