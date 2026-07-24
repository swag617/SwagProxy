package com.swag.swagproxy.download;

import java.nio.file.Path;

/**
 * Records one component jar swap performed during a restart's "apply staged
 * updates" step, so the supervisor can roll it back if Velocity crashes
 * within the configured rollback window (§4.5).
 */
public record AppliedSwap(String component, String oldBuildId, String newBuildId, Path livePath, Path backupPath) {
}
