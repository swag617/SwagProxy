package com.swag.swagproxy;

/** Thrown for any fatal, operator-facing bootstrap failure (bad port, download failure, etc). */
public class BootstrapException extends Exception {

    public BootstrapException(String message) {
        super(message);
    }

    public BootstrapException(String message, Throwable cause) {
        super(message, cause);
    }
}
