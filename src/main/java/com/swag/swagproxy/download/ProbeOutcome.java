package com.swag.swagproxy.download;

import java.util.List;

/** Result of {@link UpdateManager#probeNextCandidate()} — see Patch 2, Fix 6. */
public final class ProbeOutcome {

    public enum Type {
        /** No unconfirmed component exists — probing isn't relevant to this crash; fall through to normal handling. */
        NOT_APPLICABLE,
        /** A new candidate was installed for one component; caller should relaunch immediately. */
        ADVANCED,
        /** Every candidate for every probed component has been tried; caller should stop and report. */
        EXHAUSTED
    }

    private final Type type;
    private final String message;
    private final List<String> triedCombos;

    private ProbeOutcome(Type type, String message, List<String> triedCombos) {
        this.type = type;
        this.message = message;
        this.triedCombos = triedCombos;
    }

    public static ProbeOutcome notApplicable() {
        return new ProbeOutcome(Type.NOT_APPLICABLE, null, List.of());
    }

    public static ProbeOutcome advanced(String message) {
        return new ProbeOutcome(Type.ADVANCED, message, List.of());
    }

    public static ProbeOutcome exhausted(List<String> triedCombos) {
        return new ProbeOutcome(Type.EXHAUSTED, null, triedCombos);
    }

    public boolean isAdvanced() {
        return type == Type.ADVANCED;
    }

    public boolean isExhausted() {
        return type == Type.EXHAUSTED;
    }

    /** Only set when {@link #isAdvanced()}. */
    public String message() {
        return message;
    }

    /** Only set when {@link #isExhausted()}. */
    public List<String> triedCombos() {
        return triedCombos;
    }
}
