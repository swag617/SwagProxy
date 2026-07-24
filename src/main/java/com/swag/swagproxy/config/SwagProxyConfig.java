package com.swag.swagproxy.config;

import java.util.List;
import java.util.Map;

/** Fully parsed, validated in-memory representation of swagproxy.yml. */
public final class SwagProxyConfig {

    private final int javaPort;
    private final int bedrockPort;
    private final String bind;
    private final String motd;
    private final int maxPlayers;
    private final boolean onlineMode;
    private final Map<String, String> servers;
    private final List<String> tryOrder;
    private final String forwarding;
    private final UpdatesConfig updates;
    private final List<RestartEntry> restartSchedule;
    private final List<Integer> warnings;
    private final String warningMessage;
    private final String restartMessage;
    private final String warningCommandTemplate;
    private final SupervisorConfig supervisor;

    public SwagProxyConfig(int javaPort, int bedrockPort, String bind, String motd, int maxPlayers,
                            boolean onlineMode, Map<String, String> servers, List<String> tryOrder,
                            String forwarding, UpdatesConfig updates, List<RestartEntry> restartSchedule,
                            List<Integer> warnings, String warningMessage, String restartMessage,
                            String warningCommandTemplate, SupervisorConfig supervisor) {
        this.javaPort = javaPort;
        this.bedrockPort = bedrockPort;
        this.bind = bind;
        this.motd = motd;
        this.maxPlayers = maxPlayers;
        this.onlineMode = onlineMode;
        this.servers = servers;
        this.tryOrder = tryOrder;
        this.forwarding = forwarding;
        this.updates = updates;
        this.restartSchedule = restartSchedule;
        this.warnings = warnings;
        this.warningMessage = warningMessage;
        this.restartMessage = restartMessage;
        this.warningCommandTemplate = warningCommandTemplate;
        this.supervisor = supervisor;
    }

    public int javaPort() {
        return javaPort;
    }

    public int bedrockPort() {
        return bedrockPort;
    }

    public String bind() {
        return bind;
    }

    public String motd() {
        return motd;
    }

    public int maxPlayers() {
        return maxPlayers;
    }

    public boolean onlineMode() {
        return onlineMode;
    }

    public Map<String, String> servers() {
        return servers;
    }

    public List<String> tryOrder() {
        return tryOrder;
    }

    public String forwarding() {
        return forwarding;
    }

    public UpdatesConfig updates() {
        return updates;
    }

    public List<RestartEntry> restartSchedule() {
        return restartSchedule;
    }

    public List<Integer> warnings() {
        return warnings;
    }

    public String warningMessage() {
        return warningMessage;
    }

    public String restartMessage() {
        return restartMessage;
    }

    public String warningCommandTemplate() {
        return warningCommandTemplate;
    }

    public SupervisorConfig supervisor() {
        return supervisor;
    }
}
