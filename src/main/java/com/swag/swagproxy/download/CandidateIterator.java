package com.swag.swagproxy.download;

import java.util.List;

/**
 * Tracks progress through the ranked candidate lists for Velocity and Geyser
 * during fresh-install/no-baseline crash probing (Patch 2, Fix 6). Pure and
 * HTTP-free so the ordering/exhaustion/ambiguity rule can be unit tested
 * directly against hand-built candidate lists.
 *
 * <p>Floodgate is intentionally not varied — it's a thin auth relay, not a
 * plausible source of a Velocity boot crash, so probing keeps it fixed at
 * its own best candidate rather than trying to search a three-dimensional
 * combination space.
 *
 * <p><b>Ambiguity rule:</b> when a combination fails to boot, advance the
 * Velocity candidate first (Velocity is the platform; on a fresh install
 * the newest Geyser/Floodgate is almost always what's intended). Only once
 * Velocity's candidates are exhausted do we start advancing Geyser —
 * resetting Velocity back to its own best (index 0) candidate, since at
 * that point Geyser is the more likely remaining culprit.
 */
public final class CandidateIterator {

    private final List<ResolvedBuild> velocityCandidates;
    private final List<ResolvedBuild> geyserCandidates;
    private int velocityIndex = 0;
    private int geyserIndex = 0;
    private boolean exhausted = false;

    public CandidateIterator(List<ResolvedBuild> velocityCandidates, List<ResolvedBuild> geyserCandidates) {
        if (velocityCandidates.isEmpty() || geyserCandidates.isEmpty()) {
            throw new IllegalArgumentException("Candidate lists must not be empty.");
        }
        this.velocityCandidates = velocityCandidates;
        this.geyserCandidates = geyserCandidates;
    }

    public ResolvedBuild currentVelocity() {
        return velocityCandidates.get(velocityIndex);
    }

    public ResolvedBuild currentGeyser() {
        return geyserCandidates.get(geyserIndex);
    }

    public boolean isExhausted() {
        return exhausted;
    }

    /**
     * Advances to the next candidate per the ambiguity rule described above.
     * Returns which component changed ("velocity" or "geyser"), or null if
     * every candidate for both components has already been tried.
     */
    public String advance() {
        if (exhausted) {
            return null;
        }
        if (velocityIndex + 1 < velocityCandidates.size()) {
            velocityIndex++;
            return "velocity";
        }
        if (geyserIndex + 1 < geyserCandidates.size()) {
            velocityIndex = 0;
            geyserIndex++;
            return "geyser";
        }
        exhausted = true;
        return null;
    }

    public int velocityAttempt() {
        return velocityIndex + 1;
    }

    public int velocityTotal() {
        return velocityCandidates.size();
    }

    public int geyserAttempt() {
        return geyserIndex + 1;
    }

    public int geyserTotal() {
        return geyserCandidates.size();
    }

    /** A short "velocity-buildid / geyser-buildid" description of the current combination, for logging. */
    public String describeCurrentCombo() {
        return "velocity " + currentVelocity().buildId() + " + geyser " + currentGeyser().buildId();
    }
}
