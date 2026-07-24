package com.swag.swagproxy.config;

import java.util.List;

/** The "supervisor:" block of swagproxy.yml. */
public final class SupervisorConfig {

    private final boolean restartOnCrash;
    private final int crashLoopThreshold;
    private final int rollbackWindowSeconds;
    private final List<String> jvmArgs;

    public SupervisorConfig(boolean restartOnCrash, int crashLoopThreshold,
                             int rollbackWindowSeconds, List<String> jvmArgs) {
        this.restartOnCrash = restartOnCrash;
        this.crashLoopThreshold = crashLoopThreshold;
        this.rollbackWindowSeconds = rollbackWindowSeconds;
        this.jvmArgs = jvmArgs;
    }

    public boolean restartOnCrash() {
        return restartOnCrash;
    }

    public int crashLoopThreshold() {
        return crashLoopThreshold;
    }

    public int rollbackWindowSeconds() {
        return rollbackWindowSeconds;
    }

    public List<String> jvmArgs() {
        return jvmArgs;
    }
}
