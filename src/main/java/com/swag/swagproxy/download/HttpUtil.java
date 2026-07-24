package com.swag.swagproxy.download;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.swag.swagproxy.util.Log;
import com.swag.swagproxy.util.Sha256;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Shared HTTP client helpers for the download API clients. Centralizes the
 * outbound User-Agent header so no request path can accidentally miss it —
 * PaperMC's Fill API policy requires a non-generic UA with contact info, and
 * a default Java/curl-style UA is exactly what such policies move to block
 * (see DECISIONS.md #7).
 */
public final class HttpUtil {

    /** Sent as-is if the admin never sets updates.contact in swagproxy.yml. */
    public static final String DEFAULT_CONTACT_PLACEHOLDER = "https://github.com/SwagDev";

    public static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static volatile String contact = DEFAULT_CONTACT_PLACEHOLDER;

    private HttpUtil() {
    }

    /** Sets the contact info (URL or email) embedded in every outbound User-Agent. */
    public static void configure(String contactValue) {
        contact = (contactValue == null || contactValue.isBlank()) ? DEFAULT_CONTACT_PLACEHOLDER : contactValue.trim();
        Log.debug("Outbound User-Agent for all API/download requests: " + userAgent());
    }

    /** {@code SwagProxy/<version> (<contact>)} — version from the jar manifest, falling back to "dev". */
    static String userAgent() {
        return "SwagProxy/" + version() + " (" + contact + ")";
    }

    private static String version() {
        String v = HttpUtil.class.getPackage().getImplementationVersion();
        return (v == null || v.isBlank()) ? "dev" : v;
    }

    /** Builds a GET request to {@code url} with the standard User-Agent already set. */
    static HttpRequest.Builder newRequestBuilder(String url) {
        return HttpRequest.newBuilder(URI.create(url)).header("User-Agent", userAgent());
    }

    public static JsonElement getJson(String url) throws DownloadException {
        HttpRequest request = newRequestBuilder(url)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        try {
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new DownloadException("Request to " + url + " failed with HTTP " + response.statusCode());
            }
            return JsonParser.parseString(response.body());
        } catch (IOException e) {
            throw new DownloadException("Could not reach " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadException("Download request to " + url + " was interrupted.", e);
        }
    }

    /**
     * Downloads {@code url} to a temp file, optionally verifies its SHA256 against
     * {@code expectedSha256} (if non-null), then atomically moves it to {@code destination}.
     */
    public static void downloadTo(String url, Path destination, String expectedSha256) throws DownloadException {
        Path temp;
        try {
            Files.createDirectories(destination.toAbsolutePath().getParent());
            temp = Files.createTempFile(destination.toAbsolutePath().getParent(), "swagproxy-dl-", ".tmp");
        } catch (IOException e) {
            throw new DownloadException("Could not create temp file for download: " + e.getMessage(), e);
        }

        HttpRequest request = newRequestBuilder(url)
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        try {
            HttpResponse<Path> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofFile(temp,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE));
            if (response.statusCode() / 100 != 2) {
                Files.deleteIfExists(temp);
                throw new DownloadException("Download of " + url + " failed with HTTP " + response.statusCode());
            }
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new DownloadException("Download of " + url + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            deleteQuietly(temp);
            throw new DownloadException("Download of " + url + " was interrupted.", e);
        }

        if (expectedSha256 != null) {
            String actual;
            try {
                actual = Sha256.of(temp);
            } catch (IOException e) {
                deleteQuietly(temp);
                throw new DownloadException("Could not verify checksum of downloaded file: " + e.getMessage(), e);
            }
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                deleteQuietly(temp);
                throw new DownloadException("Checksum mismatch downloading " + url + " (expected " + expectedSha256
                        + ", got " + actual + "). Download aborted, nothing was replaced.");
            }
        }

        try {
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new DownloadException("Could not move downloaded file into place at " + destination + ": "
                    + e.getMessage(), e);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }
}
