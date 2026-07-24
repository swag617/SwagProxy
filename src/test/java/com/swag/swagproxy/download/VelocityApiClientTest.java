package com.swag.swagproxy.download;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityApiClientTest {

    // --- dotted-version comparator (unchanged since Patch 1) ---

    @Test
    void higherMinorVersionWins() {
        assertTrue(VelocityApiClient.compareVersions("3.10.0", "3.5.1") > 0);
    }

    @Test
    void higherMajorVersionWins() {
        assertTrue(VelocityApiClient.compareVersions("4.0.0", "3.5.1") > 0);
    }

    @Test
    void equalVersionsAreEqual() {
        assertTrue(VelocityApiClient.compareVersions("3.5.1", "3.5.1") == 0);
    }

    @Test
    void releaseOutranksSnapshotOfSameVersion() {
        assertTrue(VelocityApiClient.compareVersions("3.5.1", "3.5.1-SNAPSHOT") > 0);
        assertTrue(VelocityApiClient.compareVersions("3.5.1-SNAPSHOT", "3.5.1") < 0);
    }

    @Test
    void missingPatchPartTreatedAsZero() {
        assertTrue(VelocityApiClient.compareVersions("4.1.0", "4.0.0") > 0);
    }

    // --- per-version-walk candidate selection (Patch 2, Fix 4) ---
    // Supersedes Patch 1's "RECOMMENDED preferred globally" tests: a newer
    // version's STABLE build now wins over an older version's RECOMMENDED.

    private static VelocityApiClient.VersionedBuild build(String version, int buildId, String channel) {
        return new VelocityApiClient.VersionedBuild(version, buildId, channel, "v.jar",
                "https://example.com/" + version + "-" + buildId + ".jar", "sha-" + version + "-" + buildId);
    }

    @Test
    void newestVersionStableWinsOverOlderRecommended() {
        // Mirrors the live data that motivated Patch 2: 4.0.0 STABLE vs the
        // older 3.5.1 RECOMMENDED — the newer STABLE must now win.
        var newerStable = build("4.0.0", 6, "STABLE");
        var olderRecommended = build("3.5.1", 615, "RECOMMENDED");
        Map<String, List<VelocityApiClient.VersionedBuild>> byVersion = Map.of(
                "4.0.0", List.of(newerStable),
                "3.5.1", List.of(olderRecommended));

        var candidates = VelocityApiClient.walkCandidates(List.of("4.0.0", "3.5.1"), byVersion, false, 5);

        assertEquals(newerStable, candidates.get(0));
    }

    @Test
    void versionRecommendedUsedWhenNoStableAtThatVersionBeforeWalkingDown() {
        // Newest version has only RECOMMENDED (no STABLE) — should still be
        // preferred over an older version's STABLE, since we exhaust the
        // newest version's own tiers before walking down.
        var newestRecommended = build("4.1.0", 9, "RECOMMENDED");
        var olderStable = build("4.0.0", 6, "STABLE");
        Map<String, List<VelocityApiClient.VersionedBuild>> byVersion = Map.of(
                "4.1.0", List.of(newestRecommended),
                "4.0.0", List.of(olderStable));

        var candidates = VelocityApiClient.walkCandidates(List.of("4.1.0", "4.0.0"), byVersion, false, 5);

        assertEquals(newestRecommended, candidates.get(0));
    }

    @Test
    void walksDownToOlderVersionWhenNewestHasNoEligibleTier() {
        // Newest version only has a BETA build (not eligible in stable mode)
        // — must walk down to the next version rather than returning nothing.
        var newestBeta = build("4.2.0", 1, "BETA");
        var olderStable = build("4.0.0", 6, "STABLE");
        Map<String, List<VelocityApiClient.VersionedBuild>> byVersion = Map.of(
                "4.2.0", List.of(newestBeta),
                "4.0.0", List.of(olderStable));

        var candidates = VelocityApiClient.walkCandidates(List.of("4.2.0", "4.0.0"), byVersion, false, 5);

        assertEquals(olderStable, candidates.get(0));
    }

    @Test
    void stableModeNeverSelectsBetaOrAlpha() {
        var beta = build("4.1.0", 9, "BETA");
        var alpha = build("4.2.0", 1, "ALPHA");
        Map<String, List<VelocityApiClient.VersionedBuild>> byVersion = Map.of(
                "4.2.0", List.of(alpha),
                "4.1.0", List.of(beta));

        var candidates = VelocityApiClient.walkCandidates(List.of("4.2.0", "4.1.0"), byVersion, false, 5);

        assertTrue(candidates.isEmpty(), "no STABLE/RECOMMENDED anywhere — stable mode must select nothing");
    }

    @Test
    void experimentalWidensToBetaThenAlpha() {
        var beta = build("4.1.0", 9, "BETA");
        var newerAlpha = build("4.2.0", 1, "ALPHA");
        Map<String, List<VelocityApiClient.VersionedBuild>> byVersion = Map.of(
                "4.2.0", List.of(newerAlpha),
                "4.1.0", List.of(beta));

        var candidates = VelocityApiClient.walkCandidates(List.of("4.2.0", "4.1.0"), byVersion, true, 5);

        // Newest version (4.2.0) only has ALPHA, so it's taken first even
        // though 4.1.0's BETA is a "better" tier — per-version preference
        // still walks newest-to-oldest, tier is only a per-version tiebreak.
        assertEquals(newerAlpha, candidates.get(0));
        assertEquals(beta, candidates.get(1));
    }

    @Test
    void oneVersionCanYieldTwoCandidatesStableThenRecommended() {
        var stable = build("4.0.0", 6, "STABLE");
        var recommended = build("4.0.0", 5, "RECOMMENDED");
        Map<String, List<VelocityApiClient.VersionedBuild>> byVersion = Map.of(
                "4.0.0", List.of(stable, recommended));

        var candidates = VelocityApiClient.walkCandidates(List.of("4.0.0"), byVersion, false, 5);

        assertEquals(List.of(stable, recommended), candidates);
    }

    @Test
    void candidateListStopsAtCap() {
        var s1 = build("4.0.0", 6, "STABLE");
        var r1 = build("4.0.0", 5, "RECOMMENDED");
        var s2 = build("3.5.1", 615, "STABLE");
        Map<String, List<VelocityApiClient.VersionedBuild>> byVersion = Map.of(
                "4.0.0", List.of(s1, r1),
                "3.5.1", List.of(s2));

        var candidates = VelocityApiClient.walkCandidates(List.of("4.0.0", "3.5.1"), byVersion, false, 2);

        assertEquals(List.of(s1, r1), candidates);
    }

    @Test
    void emptyVersionListYieldsNoCandidates() {
        assertTrue(VelocityApiClient.walkCandidates(List.of(), Map.of(), false, 5).isEmpty());
    }
}
