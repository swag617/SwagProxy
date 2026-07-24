package com.swag.swagproxy.schedule;

import com.swag.swagproxy.config.RestartEntry;
import com.swag.swagproxy.config.SwagProxyConfig;
import com.swag.swagproxy.download.UpdateManager;
import com.swag.swagproxy.process.ProxySupervisor;
import com.swag.swagproxy.util.Log;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Timezone-aware restart scheduling (§4.4): computes each entry's next fire
 * time using its own {@link java.time.ZoneId} (never assumes this machine's
 * local timezone), schedules warning broadcasts at each configured offset,
 * and triggers the supervisor's graceful restart-and-apply-updates cycle at
 * the fire time. Also backs the manual "restart in|now" console commands.
 */
public final class RestartScheduler {

    private final SwagProxyConfig config;
    private final ProxySupervisor supervisor;
    private final UpdateManager updateManager;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "swagproxy-scheduler");
        t.setDaemon(true);
        return t;
    });

    public RestartScheduler(SwagProxyConfig config, ProxySupervisor supervisor, UpdateManager updateManager) {
        this.config = config;
        this.supervisor = supervisor;
        this.updateManager = updateManager;
    }

    public void start() {
        for (RestartEntry entry : config.restartSchedule()) {
            scheduleNextOccurrence(entry);
        }
        int intervalMinutes = Math.max(1, config.updates().checkIntervalMinutes());
        executor.scheduleAtFixedRate(updateManager::checkAndStageAll, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
        Log.info("Restart scheduler started: " + config.restartSchedule().size() + " schedule entry/entries, "
                + "update checks every " + intervalMinutes + " minute(s).");
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    /** Manual "restart in <duration>" console command. One-shot — does not repeat. */
    public void restartIn(Duration delay) {
        ZonedDateTime fireTime = ZonedDateTime.now().plus(delay);
        Log.info("Manual restart scheduled for " + fireTime + " (in " + humanize(delay.getSeconds()) + ").");
        scheduleCycle(fireTime, () -> {
        });
    }

    /** Manual "restart now" console command. */
    public void restartNow() {
        supervisor.restartNow(config.restartMessage());
    }

    private void scheduleNextOccurrence(RestartEntry entry) {
        ZonedDateTime next = computeNextFireTime(entry, ZonedDateTime.now(entry.timezone()));
        Log.info("Next scheduled restart (" + entry.timezone() + "): " + next);
        scheduleCycle(next, () -> scheduleNextOccurrence(entry));
    }

    private void scheduleCycle(ZonedDateTime fireTime, Runnable afterFired) {
        Instant fireInstant = fireTime.toInstant();
        for (int warnSeconds : config.warnings()) {
            Instant warnInstant = fireInstant.minusSeconds(warnSeconds);
            long delay = Duration.between(Instant.now(), warnInstant).getSeconds();
            if (delay > 0) {
                executor.schedule(() -> broadcastWarning(warnSeconds), delay, TimeUnit.SECONDS);
            }
        }
        long restartDelay = Math.max(0, Duration.between(Instant.now(), fireInstant).getSeconds());
        executor.schedule(() -> {
            try {
                supervisor.restartNow(config.restartMessage());
            } finally {
                afterFired.run();
            }
        }, restartDelay, TimeUnit.SECONDS);
    }

    private void broadcastWarning(int secondsBefore) {
        String humanTime = humanize(secondsBefore);
        String message = config.warningMessage().replace("{time}", humanTime);
        Log.info("Restart warning: " + humanTime + " remaining.");
        String command = config.warningCommandTemplate().replace("{message}", message);
        supervisor.sendToVelocity(command);
    }

    /** Computes the next ZonedDateTime, in the entry's own timezone, matching its time-of-day and allowed days. */
    static ZonedDateTime computeNextFireTime(RestartEntry entry, ZonedDateTime now) {
        ZonedDateTime candidate = now.with(entry.time());
        if (!candidate.isAfter(now)) {
            candidate = candidate.plusDays(1);
        }
        for (int i = 0; i < 8 && !entry.days().contains(candidate.getDayOfWeek()); i++) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    static String humanize(long totalSeconds) {
        if (totalSeconds >= 3600) {
            long hours = totalSeconds / 3600;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        if (totalSeconds >= 60) {
            long minutes = totalSeconds / 60;
            return minutes + (minutes == 1 ? " minute" : " minutes");
        }
        return totalSeconds + (totalSeconds == 1 ? " second" : " seconds");
    }
}
