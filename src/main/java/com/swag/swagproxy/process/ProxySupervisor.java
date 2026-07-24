package com.swag.swagproxy.process;

import com.swag.swagproxy.BootstrapException;
import com.swag.swagproxy.Layout;
import com.swag.swagproxy.config.SwagProxyConfig;
import com.swag.swagproxy.download.AppliedSwap;
import com.swag.swagproxy.download.ProbeOutcome;
import com.swag.swagproxy.download.UpdateManager;
import com.swag.swagproxy.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns the Velocity subprocess: launch, console passthrough, graceful
 * restarts (with staged-update apply), crash detection with backoff, and
 * automatic rollback of a bad update (§4.4, §4.5). This is the meticulous
 * part of the project — do not simplify away the rollback or crash-loop
 * logic.
 */
public final class ProxySupervisor {

    private static final Duration CRASH_WINDOW = Duration.ofMinutes(10);
    private static final Duration GRACEFUL_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);
    private static final long MAX_BACKOFF_SECONDS = 60;

    private final Layout layout;
    private final SwagProxyConfig config;
    private final UpdateManager updateManager;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "swagproxy-supervisor");
        t.setDaemon(true);
        return t;
    });

    private volatile Process process;
    private volatile PrintWriter stdin;
    private volatile boolean restartInFlight = false;
    private volatile boolean stoppedAfterCrashLoop = false;
    private volatile boolean shuttingDownForExit = false;

    private final Deque<Instant> crashTimestamps = new ArrayDeque<>();
    private volatile List<AppliedSwap> pendingSwaps = new ArrayList<>();
    private volatile Instant pendingSwapsAt;

    public ProxySupervisor(Layout layout, SwagProxyConfig config, UpdateManager updateManager) {
        this.layout = layout;
        this.config = config;
        this.updateManager = updateManager;
    }

    /** Launches Velocity for the first time. Throws if the process cannot even be started. */
    public synchronized void launchInitial() throws BootstrapException {
        try {
            launchProcess();
        } catch (IOException e) {
            throw new BootstrapException("Could not launch Velocity process: " + e.getMessage(), e);
        }
    }

    public boolean isRunning() {
        Process p = process;
        return p != null && p.isAlive();
    }

    public String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("Velocity process: ").append(isRunning() ? "RUNNING (pid " + process.pid() + ")" : "STOPPED");
        if (stoppedAfterCrashLoop) {
            sb.append(" [crash-loop threshold exceeded — use 'restart now' to try again]");
        }
        synchronized (crashTimestamps) {
            sb.append("\nRecent crashes (last 10 min): ").append(crashTimestamps.size())
                    .append("/").append(config.supervisor().crashLoopThreshold());
        }
        if (!pendingSwaps.isEmpty()) {
            sb.append("\nWatching for rollback: ").append(pendingSwaps.size())
                    .append(" component(s) updated at ").append(pendingSwapsAt);
        }
        return sb.toString();
    }

    /** Sends a raw line to Velocity's console stdin (used for warning broadcasts and command forwarding). */
    public void sendToVelocity(String line) {
        PrintWriter w = stdin;
        if (w == null) {
            Log.warn("Cannot send \"" + line + "\" — Velocity is not running.");
            return;
        }
        w.println(line);
        w.flush();
    }

    /**
     * Performs a full graceful restart cycle: sends a shutdown message, waits
     * for clean exit, applies any staged updates, and relaunches. Used by
     * the restart scheduler and the manual "restart now" console command.
     */
    public synchronized void restartNow(String shutdownMessage) {
        Log.info("Restart requested: " + shutdownMessage);
        restartInFlight = true;
        stoppedAfterCrashLoop = false;

        Process p = process;
        if (p != null && p.isAlive()) {
            sendToVelocity("shutdown " + shutdownMessage);
            try {
                if (!p.waitFor(GRACEFUL_SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    Log.warn("Velocity did not exit within " + GRACEFUL_SHUTDOWN_TIMEOUT.toSeconds()
                            + "s of shutdown request — forcing termination.");
                    p.destroyForcibly();
                    p.waitFor(10, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        applyUpdatesAndRelaunch();
    }

    private synchronized void applyUpdatesAndRelaunch() {
        List<AppliedSwap> swaps = updateManager.applyStaged();
        if (!swaps.isEmpty()) {
            pendingSwaps = swaps;
            pendingSwapsAt = Instant.now();
            Log.info("Watching " + swaps.size() + " newly-applied component(s) for crashes over the next "
                    + config.supervisor().rollbackWindowSeconds() + "s (auto-rollback window).");
        }
        restartInFlight = false;
        try {
            launchProcess();
        } catch (IOException e) {
            Log.error("Failed to relaunch Velocity after restart", e);
        }
    }

    /** Gracefully stops Velocity because SwagProxy itself is exiting (e.g. Ctrl+C). */
    public synchronized void shutdownForExit() {
        shuttingDownForExit = true;
        Process p = process;
        if (p == null || !p.isAlive()) {
            return;
        }
        sendToVelocity("shutdown SwagProxy is shutting down.");
        try {
            if (!p.waitFor(GRACEFUL_SHUTDOWN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        scheduler.shutdownNow();
    }

    private void launchProcess() throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.addAll(config.supervisor().jvmArgs());
        command.add("-jar");
        command.add(layout.velocityJar.getFileName().toString());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(layout.proxyDir.toFile());
        builder.redirectErrorStream(false);

        Log.info("Launching Velocity: " + String.join(" ", command));
        Process p = builder.start();
        this.process = p;
        this.stdin = new PrintWriter(new java.io.OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8), false);

        startPassthroughThread(p.getInputStream(), false);
        startPassthroughThread(p.getErrorStream(), true);
        scheduleConfirmationCheck(p);

        Thread waiter = new Thread(() -> {
            int exitCode;
            try {
                exitCode = p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            handleExit(exitCode);
        }, "swagproxy-velocity-waiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    /**
     * Schedules a check, {@code rollback-window-seconds} from now, that
     * confirms the current build combination as "known good" (Patch 2, Fix
     * 6) — but only if this exact process is still the one running then
     * (i.e. nothing crashed/restarted in the meantime) and at least one
     * component still lacks a confirmed baseline. A no-op once everything
     * is already confirmed, so this costs nothing on a mature install.
     */
    private void scheduleConfirmationCheck(Process launchedProcess) {
        if (!updateManager.needsProbing()) {
            return;
        }
        long windowSeconds = Math.max(1, config.supervisor().rollbackWindowSeconds());
        scheduler.schedule(() -> {
            if (this.process == launchedProcess && launchedProcess.isAlive()) {
                updateManager.confirmCurrentBuildsGood();
            }
        }, windowSeconds, TimeUnit.SECONDS);
    }

    private static String javaExecutable() {
        String home = System.getProperty("java.home");
        String exeName = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return home == null ? "java" : home + java.io.File.separator + "bin" + java.io.File.separator + exeName;
    }

    private void startPassthroughThread(java.io.InputStream in, boolean isErr) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.raw(line);
                }
            } catch (IOException ignored) {
                // stream closed on process exit
            }
        }, isErr ? "swagproxy-velocity-stderr" : "swagproxy-velocity-stdout");
        t.setDaemon(true);
        t.start();
    }

    private void handleExit(int exitCode) {
        if (shuttingDownForExit) {
            return;
        }
        if (restartInFlight) {
            // Handled synchronously by restartNow()/applyUpdatesAndRelaunch(); nothing to do here.
            return;
        }

        Log.error("Velocity exited unexpectedly with code " + exitCode + ".");
        recordCrash();

        if (rollbackIfWithinWindow()) {
            Log.warn("Rolled back the update responsible for this crash. Relaunching with the previous jars.");
        }

        if (updateManager.needsProbing()) {
            ProbeOutcome outcome = updateManager.probeNextCandidate();
            if (outcome.isAdvanced()) {
                try {
                    launchProcess();
                } catch (IOException e) {
                    Log.error("Failed to relaunch Velocity after installing a probe candidate", e);
                }
                return;
            }
            if (outcome.isExhausted()) {
                stoppedAfterCrashLoop = true;
                Log.error("=====================================================================");
                Log.error(" SwagProxy: exhausted every candidate combination without a successful boot.");
                Log.error(" Combinations tried:");
                for (String combo : outcome.triedCombos()) {
                    Log.error("   - " + combo);
                }
                Log.error(" Check logs/latest.log for the cause. Consider setting updates.velocity.pin");
                Log.error(" / updates.geyser.pin / updates.floodgate.pin in swagproxy.yml to a known-good");
                Log.error(" combination, then run 'restart now' to try again.");
                Log.error("=====================================================================");
                return;
            }
            // NOT_APPLICABLE (nothing needs probing, or probing itself failed) — fall through below.
        }

        int crashesInWindow = crashCountInWindow();
        int threshold = config.supervisor().crashLoopThreshold();
        if (crashesInWindow >= threshold) {
            stoppedAfterCrashLoop = true;
            Log.error("=====================================================================");
            Log.error(" SwagProxy: Velocity has crashed " + crashesInWindow + " times in the last "
                    + CRASH_WINDOW.toMinutes() + " minutes (threshold: " + threshold + ").");
            Log.error(" Automatic relaunching has been stopped to avoid a crash loop.");
            Log.error(" Check logs/latest.log for the cause, then run 'restart now' to try again.");
            Log.error("=====================================================================");
            return;
        }

        if (!config.supervisor().restartOnCrash()) {
            Log.warn("restart-on-crash is disabled in swagproxy.yml — not relaunching.");
            return;
        }

        long backoffSeconds = Math.min(1L << Math.max(0, crashesInWindow - 1), MAX_BACKOFF_SECONDS);
        Log.warn("Relaunching Velocity in " + backoffSeconds + "s (crash " + crashesInWindow + "/" + threshold
                + " within the last " + CRASH_WINDOW.toMinutes() + " minutes)...");
        scheduler.schedule(() -> {
            try {
                launchProcess();
            } catch (IOException e) {
                Log.error("Failed to relaunch Velocity after crash", e);
            }
        }, backoffSeconds, TimeUnit.SECONDS);
    }

    private boolean rollbackIfWithinWindow() {
        List<AppliedSwap> swaps = pendingSwaps;
        Instant appliedAt = pendingSwapsAt;
        if (swaps.isEmpty() || appliedAt == null) {
            return false;
        }
        long windowSeconds = config.supervisor().rollbackWindowSeconds();
        if (Duration.between(appliedAt, Instant.now()).getSeconds() > windowSeconds) {
            pendingSwaps = new ArrayList<>();
            pendingSwapsAt = null;
            return false;
        }
        updateManager.rollback(swaps);
        pendingSwaps = new ArrayList<>();
        pendingSwapsAt = null;
        return true;
    }

    private void recordCrash() {
        synchronized (crashTimestamps) {
            Instant now = Instant.now();
            crashTimestamps.addLast(now);
            pruneOldCrashes(now);
        }
    }

    private int crashCountInWindow() {
        synchronized (crashTimestamps) {
            pruneOldCrashes(Instant.now());
            return crashTimestamps.size();
        }
    }

    private void pruneOldCrashes(Instant now) {
        while (!crashTimestamps.isEmpty() && Duration.between(crashTimestamps.peekFirst(), now).compareTo(CRASH_WINDOW) > 0) {
            crashTimestamps.pollFirst();
        }
    }
}
