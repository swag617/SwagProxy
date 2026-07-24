package com.swag.swagproxy.config;

/** The "updates:" block of swagproxy.yml. */
public final class UpdatesConfig {

    private final ComponentUpdateConfig velocity;
    private final ComponentUpdateConfig geyser;
    private final ComponentUpdateConfig floodgate;
    private final int checkIntervalMinutes;
    private final String apply;
    private final String contact;

    public UpdatesConfig(ComponentUpdateConfig velocity, ComponentUpdateConfig geyser,
                          ComponentUpdateConfig floodgate, int checkIntervalMinutes, String apply, String contact) {
        this.velocity = velocity;
        this.geyser = geyser;
        this.floodgate = floodgate;
        this.checkIntervalMinutes = checkIntervalMinutes;
        this.apply = apply;
        this.contact = contact;
    }

    public ComponentUpdateConfig velocity() {
        return velocity;
    }

    public ComponentUpdateConfig geyser() {
        return geyser;
    }

    public ComponentUpdateConfig floodgate() {
        return floodgate;
    }

    public int checkIntervalMinutes() {
        return checkIntervalMinutes;
    }

    public String apply() {
        return apply;
    }

    /** Contact URL/email embedded in the outbound User-Agent for every API/download request (see README). */
    public String contact() {
        return contact;
    }
}
