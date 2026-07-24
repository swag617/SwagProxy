package com.swag.swagproxy.download;

/**
 * A specific, downloadable build of a managed component, as resolved from its
 * upstream API. {@code buildId} is a unique, human-readable identifier used
 * for tracking in builds.json and for rollback "skip list" bookkeeping — e.g.
 * "2.11.0-1201" for Geyser or "4.0.0-6" for Velocity.
 */
public record ResolvedBuild(String buildId, String fileName, String downloadUrl, String sha256) {
}
