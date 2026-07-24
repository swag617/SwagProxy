package com.swag.swagproxy.config;

/** Auto-update settings for a single managed component (Velocity, Geyser, or Floodgate). */
public final class ComponentUpdateConfig {

    private final boolean auto;
    private final String channel;
    private final String pin;

    public ComponentUpdateConfig(boolean auto, String channel, String pin) {
        this.auto = auto;
        this.channel = channel;
        this.pin = pin;
    }

    public boolean auto() {
        return auto;
    }

    /** Only meaningful for Velocity ("stable" or "experimental"); null for Geyser/Floodgate. */
    public String channel() {
        return channel;
    }

    /**
     * Emergency override: a specific version (or "version-buildid") to always
     * use, bypassing normal channel/candidate selection entirely. Null/unset
     * means "no pin, select normally" (the default).
     */
    public String pin() {
        return pin;
    }
}
