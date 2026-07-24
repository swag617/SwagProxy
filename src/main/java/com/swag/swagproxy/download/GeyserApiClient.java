package com.swag.swagproxy.download;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client for the GeyserMC Downloads API (v2), used for both Geyser and
 * Floodgate — they share the exact same JSON shape. Verified live against
 * https://download.geysermc.org/v2/projects/{geyser|floodgate} on 2026-07-22
 * (see DECISIONS.md).
 *
 * <p>Base URL is overridable via the {@code swagproxy.geysermc-api-base}
 * system property so an operator/mirror can repoint it if GeyserMC ever
 * migrates their API, without needing a code change.
 */
public final class GeyserApiClient {

    private static final String DEFAULT_BASE = "https://download.geysermc.org/v2";
    private static final Pattern VERSION_BUILD_PIN = Pattern.compile("^(.+)-(\\d+)$");

    private final String base;
    private final String projectId;

    public GeyserApiClient(String projectId) {
        this.projectId = projectId;
        this.base = System.getProperty("swagproxy.geysermc-api-base", DEFAULT_BASE);
    }

    /** Convenience wrapper: the single best (newest) build (candidate #1). */
    public ResolvedBuild fetchLatestVelocityBuild() throws DownloadException {
        return fetchCandidates(1).get(0);
    }

    /**
     * Returns up to {@code maxCandidates} builds, newest version first — one
     * candidate per version, using that version's latest build. This API has
     * no channel concept, so "candidate" here simply means "the newest build
     * of the Nth-newest version." Used both for normal "latest" resolution
     * and for fresh-install crash probing (Patch 2, Fix 6).
     */
    public List<ResolvedBuild> fetchCandidates(int maxCandidates) throws DownloadException {
        List<String> versions = fetchAllVersionsDescending();
        List<ResolvedBuild> results = new ArrayList<>();
        for (String version : versions) {
            results.add(fetchLatestBuildOfVersion(version));
            if (results.size() >= maxCandidates) {
                break;
            }
        }
        if (results.isEmpty()) {
            throw new DownloadException("GeyserMC API returned no usable " + projectId + " builds.");
        }
        return results;
    }

    /**
     * Resolves an operator-supplied pin (§Fix 5): either an exact version
     * string (uses that version's latest build) or a "version-buildid"
     * composite (uses that exact build).
     */
    public ResolvedBuild resolvePin(String pin) throws DownloadException {
        List<String> versions = fetchAllVersionsFlat();

        if (versions.contains(pin)) {
            return fetchLatestBuildOfVersion(pin);
        }

        Matcher m = VERSION_BUILD_PIN.matcher(pin);
        if (m.matches() && versions.contains(m.group(1))) {
            return fetchExactBuild(m.group(1), m.group(2));
        }

        throw new DownloadException("updates." + projectId + ".pin \"" + pin
                + "\" does not match any known " + projectId
                + " version. Expected a version like \"2.11.0\" or a \"version-buildid\" like \"2.11.0-1201\" — "
                + "check " + base + "/projects/" + projectId + " for available versions.");
    }

    private List<String> fetchAllVersionsFlat() throws DownloadException {
        String url = base + "/projects/" + projectId;
        JsonElement root = HttpUtil.getJson(url);
        if (!root.isJsonObject() || !root.getAsJsonObject().has("versions")) {
            throw new DownloadException("Unexpected response shape from " + url);
        }
        JsonArray versionsArray = root.getAsJsonObject().getAsJsonArray("versions");
        List<String> versions = new ArrayList<>();
        for (JsonElement el : versionsArray) {
            versions.add(el.getAsString());
        }
        return versions;
    }

    private List<String> fetchAllVersionsDescending() throws DownloadException {
        List<String> versions = fetchAllVersionsFlat();
        versions.sort((a, b) -> VelocityApiClient.compareVersions(b, a));
        return versions;
    }

    private ResolvedBuild fetchLatestBuildOfVersion(String version) throws DownloadException {
        String metaUrl = base + "/projects/" + projectId + "/versions/" + version + "/builds/latest";
        return parseBuildMetadata(metaUrl, version, null);
    }

    private ResolvedBuild fetchExactBuild(String version, String buildId) throws DownloadException {
        String metaUrl = base + "/projects/" + projectId + "/versions/" + version + "/builds/" + buildId;
        return parseBuildMetadata(metaUrl, version, buildId);
    }

    private ResolvedBuild parseBuildMetadata(String metaUrl, String expectedVersion, String expectedBuild)
            throws DownloadException {
        JsonElement root = HttpUtil.getJson(metaUrl);
        if (!root.isJsonObject()) {
            throw new DownloadException("Unexpected response shape from " + metaUrl);
        }
        JsonObject obj = root.getAsJsonObject();
        if (!obj.has("downloads") || !obj.has("version") || !obj.has("build")) {
            throw new DownloadException("GeyserMC API response for " + projectId
                    + " is missing expected fields (version/build/downloads): " + obj);
        }
        String version = obj.get("version").getAsString();
        String build = obj.get("build").getAsString();
        if (expectedBuild != null && !expectedBuild.equals(build)) {
            throw new DownloadException("updates." + projectId + ".pin resolved to version \"" + expectedVersion
                    + "\" but build #" + expectedBuild + " does not exist (API returned build #" + build + ").");
        }
        JsonObject downloads = obj.getAsJsonObject("downloads");
        if (!downloads.has("velocity")) {
            throw new DownloadException("GeyserMC API response for " + projectId
                    + " has no \"velocity\" platform download available.");
        }
        JsonObject velocity = downloads.getAsJsonObject("velocity");
        String fileName = velocity.has("name") ? velocity.get("name").getAsString() : projectId + "-velocity.jar";
        String sha256 = velocity.has("sha256") ? velocity.get("sha256").getAsString() : null;

        String downloadUrl = base + "/projects/" + projectId + "/versions/" + version + "/builds/" + build
                + "/downloads/velocity";
        String buildId = version + "-" + build;
        return new ResolvedBuild(buildId, fileName, downloadUrl, sha256);
    }
}
