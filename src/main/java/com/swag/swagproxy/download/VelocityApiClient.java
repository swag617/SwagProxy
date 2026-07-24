package com.swag.swagproxy.download;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.swag.swagproxy.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client for PaperMC's download API for the "velocity" project.
 *
 * <p><b>Implementer note (see DECISIONS.md #1):</b> the v1 API base URL
 * documented historically ({@code https://api.papermc.io/v2/...}) returns
 * HTTP 410 Gone as of 2026-07-22 — PaperMC has migrated to the "Fill" v3 API
 * at {@code https://fill.papermc.io/v3}, which was verified live and has a
 * different JSON shape. Base URL is overridable via the
 * {@code swagproxy.papermc-api-base} system property.
 *
 * <p><b>Build selection (see DECISIONS.md #8, #10):</b> Fill v3 has a
 * first-class per-build {@code channel} field ({@code ALPHA}/{@code BETA}/
 * {@code STABLE}/{@code RECOMMENDED}). Selection walks versions
 * newest-to-oldest and, for EACH version, prefers that version's own
 * {@code STABLE} build over its {@code RECOMMENDED} build (Patch 2, Fix 4 —
 * this supersedes Patch 1's "RECOMMENDED beats any newer STABLE" rule,
 * since staying current matters more for a Geyser-first proxy than
 * PaperMC's deliberately-conservative recommendation flag). Channel is
 * never inferred from a "-SNAPSHOT" name, and is not guaranteed to advance
 * monotonically with build number within a version.
 */
public final class VelocityApiClient {

    private static final String DEFAULT_BASE = "https://fill.papermc.io/v3";
    private static final String PROJECT = "velocity";

    /** Per-version tier preference order, newest version wins as long as it has a build in an allowed tier. */
    private static final List<String> STABLE_TIERS_PER_VERSION = List.of("STABLE", "RECOMMENDED");
    private static final List<String> EXPERIMENTAL_TIERS_PER_VERSION = List.of("STABLE", "RECOMMENDED", "BETA", "ALPHA");

    private static final Pattern VERSION_BUILD_PIN = Pattern.compile("^(.+)-(\\d+)$");

    private final String base;

    public VelocityApiClient() {
        this.base = System.getProperty("swagproxy.papermc-api-base", DEFAULT_BASE);
    }

    /** Convenience wrapper: the single best build (candidate #1). */
    public ResolvedBuild fetchLatestBuild(boolean allowExperimental) throws DownloadException {
        return fetchCandidates(allowExperimental, 1).get(0);
    }

    /**
     * Returns up to {@code maxCandidates} ranked builds (best first), by
     * walking versions newest-to-oldest and, per version, trying each tier
     * in priority order — each tier hit becomes one candidate. Used both for
     * normal "latest" resolution (candidate #1) and for fresh-install crash
     * probing (Patch 2, Fix 6), which needs the full ranked list.
     */
    public List<ResolvedBuild> fetchCandidates(boolean allowExperimental, int maxCandidates) throws DownloadException {
        List<String> tiers = allowExperimental ? EXPERIMENTAL_TIERS_PER_VERSION : STABLE_TIERS_PER_VERSION;
        List<String> allVersions = fetchAllVersionsDescending();

        List<VersionedBuild> results = new ArrayList<>();
        for (String version : allVersions) {
            List<VersionedBuild> builds = fetchBuildsForVersion(version);
            for (String tier : tiers) {
                VersionedBuild best = bestInTier(builds, tier);
                if (best != null) {
                    results.add(best);
                    if (results.size() >= maxCandidates) {
                        return toResolvedBuilds(results, allowExperimental);
                    }
                }
            }
        }
        if (results.isEmpty()) {
            throw new DownloadException("PaperMC API returned no usable Velocity builds on the "
                    + (allowExperimental ? "experimental channels (STABLE/RECOMMENDED/BETA/ALPHA)."
                    : "stable channels (STABLE/RECOMMENDED) — try channel: experimental?"));
        }
        return toResolvedBuilds(results, allowExperimental);
    }

    /**
     * Resolves an operator-supplied pin (§Fix 5): either an exact version
     * string (uses that version's newest build, any channel) or a
     * "version-buildid" composite (uses that exact build). Throws a clear,
     * human-readable error — never a stack trace — if the pin can't be
     * resolved against the live API.
     */
    public ResolvedBuild resolvePin(String pin) throws DownloadException {
        List<String> allVersions = fetchAllVersionsFlat();

        if (allVersions.contains(pin)) {
            List<VersionedBuild> builds = fetchBuildsForVersion(pin);
            VersionedBuild best = null;
            for (VersionedBuild b : builds) {
                if (best == null || b.buildId() > best.buildId()) {
                    best = b;
                }
            }
            if (best == null) {
                throw new DownloadException("updates.velocity.pin \"" + pin
                        + "\" is a known version but has no downloadable builds.");
            }
            return toResolvedBuild(best);
        }

        Matcher m = VERSION_BUILD_PIN.matcher(pin);
        if (m.matches() && allVersions.contains(m.group(1))) {
            String version = m.group(1);
            int buildId = Integer.parseInt(m.group(2));
            for (VersionedBuild b : fetchBuildsForVersion(version)) {
                if (b.buildId() == buildId) {
                    return toResolvedBuild(b);
                }
            }
            throw new DownloadException("updates.velocity.pin \"" + pin + "\" — version \"" + version
                    + "\" exists but has no build #" + buildId + ".");
        }

        throw new DownloadException("updates.velocity.pin \"" + pin
                + "\" does not match any known Velocity version. Expected a version like \"4.0.0\" or a "
                + "\"version-buildid\" like \"4.0.0-6\" — check https://fill.papermc.io/v3/projects/velocity "
                + "for available versions.");
    }

    private List<String> fetchAllVersionsFlat() throws DownloadException {
        String projectUrl = base + "/projects/" + PROJECT;
        JsonElement root = HttpUtil.getJson(projectUrl);
        if (!root.isJsonObject() || !root.getAsJsonObject().has("versions")) {
            throw new DownloadException("Unexpected response shape from " + projectUrl);
        }
        JsonObject versionsByGroup = root.getAsJsonObject().getAsJsonObject("versions");
        List<String> allVersions = new ArrayList<>();
        for (String groupKey : versionsByGroup.keySet()) {
            for (JsonElement versionEl : versionsByGroup.getAsJsonArray(groupKey)) {
                allVersions.add(versionEl.getAsString());
            }
        }
        return allVersions;
    }

    private List<String> fetchAllVersionsDescending() throws DownloadException {
        List<String> allVersions = fetchAllVersionsFlat();
        allVersions.sort((a, b) -> compareVersions(b, a));
        return allVersions;
    }

    private List<VersionedBuild> fetchBuildsForVersion(String version) throws DownloadException {
        String url = base + "/projects/" + PROJECT + "/versions/" + version + "/builds";
        JsonElement root = HttpUtil.getJson(url);
        if (!root.isJsonArray()) {
            throw new DownloadException("Unexpected response shape from " + url);
        }
        List<VersionedBuild> result = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray()) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject build = el.getAsJsonObject();
            if (!build.has("id") || !build.has("channel") || !build.has("downloads")) {
                continue;
            }
            JsonObject downloads = build.getAsJsonObject("downloads");
            if (!downloads.has("server:default")) {
                continue;
            }
            JsonObject download = downloads.getAsJsonObject("server:default");
            int buildId = build.get("id").getAsInt();
            String channel = build.get("channel").getAsString();
            String fileName = download.has("name") ? download.get("name").getAsString()
                    : "velocity-" + version + "-" + buildId + ".jar";
            String downloadUrl = download.get("url").getAsString();
            String sha256 = null;
            if (download.has("checksums") && download.getAsJsonObject("checksums").has("sha256")) {
                sha256 = download.getAsJsonObject("checksums").get("sha256").getAsString();
            }
            result.add(new VersionedBuild(version, buildId, channel, fileName, downloadUrl, sha256));
        }
        return result;
    }

    private static VersionedBuild bestInTier(List<VersionedBuild> builds, String tier) {
        VersionedBuild best = null;
        for (VersionedBuild b : builds) {
            if (!tier.equalsIgnoreCase(b.channel())) {
                continue;
            }
            if (best == null || b.buildId() > best.buildId()) {
                best = b;
            }
        }
        return best;
    }

    private static ResolvedBuild toResolvedBuild(VersionedBuild b) {
        return new ResolvedBuild(b.version() + "-" + b.buildId(), b.fileName(), b.url(), b.sha256());
    }

    private static List<ResolvedBuild> toResolvedBuilds(List<VersionedBuild> builds, boolean allowExperimental) {
        List<ResolvedBuild> resolved = new ArrayList<>();
        for (VersionedBuild b : builds) {
            resolved.add(toResolvedBuild(b));
        }
        Log.info("Velocity candidate ranking (mode: " + (allowExperimental ? "experimental" : "stable") + "): "
                + resolved.stream().map(ResolvedBuild::buildId).reduce((a, b) -> a + " > " + b).orElse("(none)"));
        return resolved;
    }

    /**
     * Pure selection over a pre-fetched version/build map — no HTTP. Package-private
     * so it can be unit tested directly without hitting the network, mirroring the
     * exact per-version-walk logic {@link #fetchCandidates} uses internally.
     */
    static List<VersionedBuild> walkCandidates(List<String> versionsDescending,
                                                Map<String, List<VersionedBuild>> buildsByVersion,
                                                boolean allowExperimental, int maxCandidates) {
        List<String> tiers = allowExperimental ? EXPERIMENTAL_TIERS_PER_VERSION : STABLE_TIERS_PER_VERSION;
        List<VersionedBuild> results = new ArrayList<>();
        for (String version : versionsDescending) {
            List<VersionedBuild> builds = buildsByVersion.getOrDefault(version, List.of());
            for (String tier : tiers) {
                VersionedBuild best = bestInTier(builds, tier);
                if (best != null) {
                    results.add(best);
                    if (results.size() >= maxCandidates) {
                        return results;
                    }
                }
            }
        }
        return results;
    }

    /** Compares two dotted version strings numerically (e.g. "3.5.1" vs "3.10.0"), ignoring any "-SNAPSHOT" suffix. */
    static int compareVersions(String a, String b) {
        List<Integer> pa = numericParts(a);
        List<Integer> pb = numericParts(b);
        int len = Math.max(pa.size(), pb.size());
        for (int i = 0; i < len; i++) {
            int va = i < pa.size() ? pa.get(i) : 0;
            int vb = i < pb.size() ? pb.get(i) : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        // Equal numeric parts: a non-SNAPSHOT release outranks a SNAPSHOT of the same version.
        boolean aSnap = a.toUpperCase().contains("SNAPSHOT");
        boolean bSnap = b.toUpperCase().contains("SNAPSHOT");
        return Boolean.compare(bSnap, aSnap);
    }

    private static List<Integer> numericParts(String version) {
        String core = version.split("-", 2)[0];
        List<Integer> parts = new ArrayList<>();
        for (String s : core.split("\\.")) {
            try {
                parts.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException e) {
                parts.add(0);
            }
        }
        return parts;
    }

    /** One build of one version, as needed for channel-tier selection. Package-private for test access. */
    record VersionedBuild(String version, int buildId, String channel, String fileName, String url, String sha256) {
    }
}
