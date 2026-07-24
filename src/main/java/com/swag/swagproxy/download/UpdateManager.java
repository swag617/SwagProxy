package com.swag.swagproxy.download;

import com.swag.swagproxy.Layout;
import com.swag.swagproxy.config.SwagProxyConfig;
import com.swag.swagproxy.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves and stages updates for Velocity, Geyser, and Floodgate, and
 * applies/rolls back staged jars during the restart cycle (§4.2, §4.5).
 * Never touches a live jar outside of {@link #applyStaged()} — new downloads
 * always land as "&lt;name&gt;.staged" first (§2, §4.3).
 *
 * <p><b>Patch 2 additions:</b> per-component version/build pins (Fix 5,
 * DECISIONS.md #11) that bypass channel selection entirely, and a
 * candidate-probing mechanism (Fix 6, DECISIONS.md #12) that lets the
 * supervisor recover from a fresh install (or any install with no
 * known-good baseline yet) whose first-choice combination of Velocity and
 * Geyser fails to boot, by trying the next-best combination automatically
 * instead of crash-looping forever.
 */
public final class UpdateManager {

    public static final String COMPONENT_VELOCITY = "velocity";
    public static final String COMPONENT_GEYSER = "geyser";
    public static final String COMPONENT_FLOODGATE = "floodgate";

    /** Cap on how many ranked candidates we'll ever consider per component during probing. */
    private static final int PROBE_CANDIDATE_CAP = 5;

    private final Layout layout;
    private final SwagProxyConfig config;
    private final BuildTracker tracker;
    private final GeyserApiClient geyserApi = new GeyserApiClient("geyser");
    private final GeyserApiClient floodgateApi = new GeyserApiClient("floodgate");
    private final VelocityApiClient velocityApi = new VelocityApiClient();

    private final Set<String> pinNoticeLogged = new HashSet<>();
    private CandidateIterator activeProbe;
    private final List<String> probeLog = new ArrayList<>();

    public UpdateManager(Layout layout, SwagProxyConfig config, BuildTracker tracker) {
        this.layout = layout;
        this.config = config;
        this.tracker = tracker;
    }

    public Path livePathFor(String component) {
        return switch (component) {
            case COMPONENT_VELOCITY -> layout.velocityJar;
            case COMPONENT_GEYSER -> layout.geyserJar;
            case COMPONENT_FLOODGATE -> layout.floodgateJar;
            default -> throw new IllegalArgumentException("Unknown component: " + component);
        };
    }

    private String pinFor(String component) {
        return switch (component) {
            case COMPONENT_VELOCITY -> config.updates().velocity().pin();
            case COMPONENT_GEYSER -> config.updates().geyser().pin();
            case COMPONENT_FLOODGATE -> config.updates().floodgate().pin();
            default -> throw new IllegalArgumentException("Unknown component: " + component);
        };
    }

    private ResolvedBuild resolvePinFor(String component, String pin) throws DownloadException {
        return switch (component) {
            case COMPONENT_VELOCITY -> velocityApi.resolvePin(pin);
            case COMPONENT_GEYSER -> geyserApi.resolvePin(pin);
            case COMPONENT_FLOODGATE -> floodgateApi.resolvePin(pin);
            default -> throw new IllegalArgumentException("Unknown component: " + component);
        };
    }

    /**
     * Ranked candidates (best first) for a component, respecting an
     * {@code updates.<component>.pin} override — a pinned component always
     * returns a single-element list (its pin), disabling both normal
     * advancement and probing for it.
     */
    private List<ResolvedBuild> fetchCandidatesRespectingPin(String component, int maxCandidates) throws DownloadException {
        String pin = pinFor(component);
        if (pin != null) {
            if (pinNoticeLogged.add(component)) {
                Log.info(component + " is pinned to \"" + pin + "\" (updates." + component
                        + ".pin) — automatic version advancement is disabled for this component.");
            }
            return List.of(resolvePinFor(component, pin));
        }
        return switch (component) {
            case COMPONENT_VELOCITY -> velocityApi.fetchCandidates(
                    "experimental".equalsIgnoreCase(config.updates().velocity().channel()), maxCandidates);
            case COMPONENT_GEYSER -> geyserApi.fetchCandidates(maxCandidates);
            case COMPONENT_FLOODGATE -> floodgateApi.fetchCandidates(maxCandidates);
            default -> throw new IllegalArgumentException("Unknown component: " + component);
        };
    }

    private ResolvedBuild resolveLatest(String component) throws DownloadException {
        return fetchCandidatesRespectingPin(component, 1).get(0);
    }

    /**
     * Guarantees the live jar for {@code component} exists, downloading the
     * latest build if it's missing. Used at first-boot (§6.2 "zero manual
     * downloads") — failure here is fatal since the proxy cannot start.
     */
    public void ensureInstalled(String component) throws DownloadException {
        Path live = livePathFor(component);
        if (Files.exists(live)) {
            return;
        }
        Log.info(component + " jar not found, downloading latest build...");
        ResolvedBuild build = resolveLatest(component);
        HttpUtil.downloadTo(build.downloadUrl(), live, build.sha256());
        tracker.of(component).setLiveBuild(build.buildId());
        tracker.save();
        Log.info("Installed " + component + " build " + build.buildId() + " (" + build.fileName() + ")");
    }

    /**
     * Checks for a newer build than what's currently live/staged and, if
     * {@code auto} is enabled and the build isn't on the skip list, stages
     * it. Non-fatal on failure — logs and returns false so a flaky API
     * doesn't affect an already-running proxy.
     */
    public boolean checkAndStage(String component, boolean autoEnabled) {
        if (!autoEnabled) {
            return false;
        }
        ComponentBuildState state = tracker.of(component);
        try {
            ResolvedBuild latest = resolveLatest(component);
            if (latest.buildId().equals(state.liveBuild())) {
                return false;
            }
            Path staged = Layout.stagedPathFor(livePathFor(component));
            if (latest.buildId().equals(stagedBuildIdOrNull(component))) {
                return false;
            }
            if (state.isSkipped(latest.buildId())) {
                Log.warn(component + " build " + latest.buildId()
                        + " was previously rolled back and is on the skip list — not re-staging it.");
                return false;
            }
            Log.info("Staging " + component + " update: " + state.liveBuild() + " -> " + latest.buildId());
            HttpUtil.downloadTo(latest.downloadUrl(), staged, latest.sha256());
            setStagedBuildId(component, latest.buildId());
            tracker.save();
            Log.info(component + " build " + latest.buildId() + " staged; will be applied at the next restart.");
            return true;
        } catch (DownloadException e) {
            Log.warn("Update check for " + component + " failed (will retry next cycle): " + e.getMessage());
            return false;
        }
    }

    public void checkAndStageAll() {
        checkAndStage(COMPONENT_VELOCITY, config.updates().velocity().auto());
        checkAndStage(COMPONENT_GEYSER, config.updates().geyser().auto());
        checkAndStage(COMPONENT_FLOODGATE, config.updates().floodgate().auto());
    }

    // We track the staged build id in a small sidecar marker file next to the
    // staged jar itself, since a manually-dropped ".staged" file (README's
    // drag-and-drop workflow) has no build id of its own.
    private Path stagedMarker(String component) {
        return Layout.stagedPathFor(livePathFor(component)).resolveSibling(
                livePathFor(component).getFileName() + ".staged.buildid");
    }

    private String stagedBuildIdOrNull(String component) {
        try {
            Path marker = stagedMarker(component);
            return Files.exists(marker) ? Files.readString(marker).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void setStagedBuildId(String component, String buildId) {
        try {
            Files.writeString(stagedMarker(component), buildId);
        } catch (IOException e) {
            Log.warn("Could not write staged-build marker for " + component + ": " + e.getMessage());
        }
    }

    /**
     * Applies any staged jars (including manually drag-and-dropped ones) by
     * backing up the current live jar and swapping the staged one in. Called
     * during the restart sequence, after Velocity has cleanly shut down.
     * Returns the list of swaps performed, for rollback tracking.
     */
    public List<AppliedSwap> applyStaged() {
        List<AppliedSwap> swaps = new ArrayList<>();
        for (String component : List.of(COMPONENT_VELOCITY, COMPONENT_GEYSER, COMPONENT_FLOODGATE)) {
            Path live = livePathFor(component);
            Path staged = Layout.stagedPathFor(live);
            if (!Files.exists(staged)) {
                continue;
            }
            ComponentBuildState state = tracker.of(component);
            String oldBuildId = state.liveBuild();
            String newBuildId = stagedBuildIdOrNull(component);
            if (newBuildId == null) {
                // Manually dropped .staged file with no known version — synthesize an id.
                newBuildId = "manual-" + Instant.now().getEpochSecond();
            }

            try {
                Files.createDirectories(layout.backupsDir);
                String backupName = component + "-" + (oldBuildId != null ? oldBuildId : "unknown") + ".jar";
                Path backup = layout.backupsDir.resolve(backupName);
                if (Files.exists(live)) {
                    Files.move(live, backup, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    backup = null;
                }
                Files.move(staged, live, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                Files.deleteIfExists(stagedMarker(component));

                state.setLiveBuild(newBuildId);
                tracker.save();
                Log.info("Applied staged " + component + " update: " + oldBuildId + " -> " + newBuildId);

                if (backup != null) {
                    swaps.add(new AppliedSwap(component, oldBuildId, newBuildId, live, backup));
                }
            } catch (IOException e) {
                Log.error("Failed to apply staged update for " + component + " — leaving existing jar in place", e);
            }
        }
        return swaps;
    }

    /**
     * Restores the pre-swap jar for each applied swap, marks the bad build
     * as skipped so it's never re-staged, and persists the rollback (§4.5).
     * Immediately re-confirms the restored build as good — it was the
     * previously-working baseline, so it doesn't need to re-earn
     * confirmation through another full rollback-window wait.
     */
    public void rollback(List<AppliedSwap> swaps) {
        for (AppliedSwap swap : swaps) {
            try {
                Files.move(swap.backupPath(), swap.livePath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                ComponentBuildState state = tracker.of(swap.component());
                state.setLiveBuild(swap.oldBuildId());
                state.confirmGood();
                state.markSkipped(swap.newBuildId());
                Log.warn("Rolled back " + swap.component() + " to " + swap.oldBuildId()
                        + " — build " + swap.newBuildId() + " marked bad and will not be re-applied.");
            } catch (IOException e) {
                Log.error("CRITICAL: rollback of " + swap.component() + " failed — manual intervention required. "
                        + "Backup is at " + swap.backupPath(), e);
            }
        }
        tracker.save();
    }

    // --- Patch 2, Fix 6: fresh-install / no-baseline candidate probing ---

    /**
     * True if Velocity or Geyser has never survived a rollback/probation
     * window (no confirmed-good baseline yet). Floodgate is intentionally
     * excluded — it's not varied during probing (see {@link CandidateIterator}).
     * The supervisor should only attempt candidate probing on a crash when
     * this is true; otherwise an unrelated crash on a long-stable install
     * would start churning through jars for no reason.
     */
    public boolean needsProbing() {
        return !tracker.of(COMPONENT_VELOCITY).confirmedGood() || !tracker.of(COMPONENT_GEYSER).confirmedGood();
    }

    /** Called once a combination has survived its rollback/probation window without crashing. */
    public void confirmCurrentBuildsGood() {
        boolean wasProbing = activeProbe != null;
        tracker.of(COMPONENT_VELOCITY).confirmGood();
        tracker.of(COMPONENT_GEYSER).confirmGood();
        tracker.of(COMPONENT_FLOODGATE).confirmGood();
        activeProbe = null;
        probeLog.clear();
        tracker.save();
        if (wasProbing) {
            Log.info("Candidate probing succeeded — this combination is now the known-good baseline.");
        }
    }

    /**
     * Advances to the next candidate combination following a crash with no
     * rollback target (fresh install, or an already-exhausted rollback).
     * Downloads and directly installs the next candidate for whichever
     * component the ambiguity rule says to vary next, marking the build we
     * just moved away from as skipped. Returns EXHAUSTED once every
     * candidate for both Velocity and Geyser has been tried.
     */
    public ProbeOutcome probeNextCandidate() {
        if (!needsProbing()) {
            return ProbeOutcome.notApplicable();
        }
        try {
            if (activeProbe == null) {
                List<ResolvedBuild> velocityCandidates = fetchCandidatesRespectingPin(COMPONENT_VELOCITY, PROBE_CANDIDATE_CAP);
                List<ResolvedBuild> geyserCandidates = fetchCandidatesRespectingPin(COMPONENT_GEYSER, PROBE_CANDIDATE_CAP);
                activeProbe = new CandidateIterator(velocityCandidates, geyserCandidates);
                probeLog.clear();
                probeLog.add(activeProbe.describeCurrentCombo());
            }

            ResolvedBuild failingVelocity = activeProbe.currentVelocity();
            ResolvedBuild failingGeyser = activeProbe.currentGeyser();

            String changed = activeProbe.advance();
            if (changed == null) {
                List<String> tried = List.copyOf(probeLog);
                activeProbe = null;
                probeLog.clear();
                return ProbeOutcome.exhausted(tried);
            }

            String message;
            if (changed.equals(COMPONENT_VELOCITY)) {
                tracker.of(COMPONENT_VELOCITY).markSkipped(failingVelocity.buildId());
                installProbeCandidate(COMPONENT_VELOCITY, activeProbe.currentVelocity());
                message = "Boot failed with Velocity " + failingVelocity.buildId() + " — trying next candidate "
                        + activeProbe.currentVelocity().buildId() + " (" + activeProbe.velocityAttempt() + "/"
                        + activeProbe.velocityTotal() + ")";
            } else {
                tracker.of(COMPONENT_GEYSER).markSkipped(failingGeyser.buildId());
                installProbeCandidate(COMPONENT_GEYSER, activeProbe.currentGeyser());
                installProbeCandidate(COMPONENT_VELOCITY, activeProbe.currentVelocity());
                message = "Boot failed with Geyser " + failingGeyser.buildId() + " — trying next candidate "
                        + activeProbe.currentGeyser().buildId() + " (" + activeProbe.geyserAttempt() + "/"
                        + activeProbe.geyserTotal() + "), Velocity reset to its best candidate "
                        + activeProbe.currentVelocity().buildId();
            }
            tracker.save();
            probeLog.add(activeProbe.describeCurrentCombo());
            Log.warn(message);
            return ProbeOutcome.advanced(message);
        } catch (DownloadException e) {
            Log.error("Candidate probing could not fetch/install a candidate (will fall back to normal "
                    + "crash-loop handling): " + e.getMessage());
            return ProbeOutcome.notApplicable();
        }
    }

    private void installProbeCandidate(String component, ResolvedBuild build) throws DownloadException {
        Path live = livePathFor(component);
        Path temp = Layout.stagedPathFor(live);
        HttpUtil.downloadTo(build.downloadUrl(), temp, build.sha256());
        try {
            Files.createDirectories(layout.backupsDir);
            if (Files.exists(live)) {
                Path backup = layout.backupsDir.resolve(component + "-probe-" + System.currentTimeMillis() + ".jar");
                Files.move(live, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temp, live, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new DownloadException("Could not install probe candidate for " + component + ": " + e.getMessage(), e);
        }
        tracker.of(component).setLiveBuild(build.buildId());
        Log.info("Installed probe candidate for " + component + ": " + build.buildId());
    }
}
