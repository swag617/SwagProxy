package com.swag.swagproxy.download;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Per-component persisted state in builds.json: what's live, and what's been rolled back. */
public final class ComponentBuildState {

    /**
     * Bounded FIFO cap on skippedBuilds (Patch 2, Fix 6) — skip entries added
     * by candidate probing must age out so a temporarily-bad pairing doesn't
     * blacklist a version forever (see DECISIONS.md #12). Simpler and more
     * robust than trying to version-compare arbitrary buildId strings across
     * components to decide staleness.
     */
    private static final int MAX_SKIPPED = 5;

    private String liveBuild;
    private boolean confirmedGood;
    private List<String> skippedBuilds = new ArrayList<>();

    public String liveBuild() {
        return liveBuild;
    }

    /**
     * Sets the currently-installed build. Resets {@link #confirmedGood()} to
     * false whenever the build actually changes — "known good" always refers
     * to a specific build, not to whatever happens to be live right now.
     */
    public void setLiveBuild(String liveBuild) {
        if (!Objects.equals(this.liveBuild, liveBuild)) {
            this.confirmedGood = false;
        }
        this.liveBuild = liveBuild;
    }

    /**
     * True once the current {@link #liveBuild()} has survived its rollback/
     * probation window at least once — this is the "known-good baseline"
     * future updates roll back to, and its presence is what tells the
     * fresh-install prober (Fix 6) that this component no longer needs
     * probing on an unrelated crash.
     */
    public boolean confirmedGood() {
        return confirmedGood;
    }

    public void confirmGood() {
        this.confirmedGood = true;
    }

    public List<String> skippedBuilds() {
        if (skippedBuilds == null) {
            skippedBuilds = new ArrayList<>();
        }
        return skippedBuilds;
    }

    public boolean isSkipped(String buildId) {
        return skippedBuilds().contains(buildId);
    }

    public void markSkipped(String buildId) {
        if (isSkipped(buildId)) {
            return;
        }
        skippedBuilds().add(buildId);
        while (skippedBuilds().size() > MAX_SKIPPED) {
            skippedBuilds().remove(0);
        }
    }
}
