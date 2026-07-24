package com.swag.swagproxy.download;

/** Human-readable failure fetching or verifying a component download. */
public class DownloadException extends Exception {

    public DownloadException(String message) {
        super(message);
    }

    public DownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
