package com.swag.swagproxy.config;

import java.util.List;

/**
 * Thrown when swagproxy.yml is missing required keys, has the wrong type for
 * a key, or otherwise cannot be understood. Carries a human-readable message
 * (never a raw stack trace) suitable for printing directly to an operator.
 */
public class ConfigException extends Exception {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }

    public static ConfigException fromErrors(List<String> errors) {
        StringBuilder sb = new StringBuilder("swagproxy.yml has ").append(errors.size())
                .append(errors.size() == 1 ? " problem:" : " problems:");
        for (String e : errors) {
            sb.append("\n  - ").append(e);
        }
        return new ConfigException(sb.toString());
    }
}
