package com.swag.swagproxy.download;

import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpUtilTest {

    @Test
    void userAgentIsPresentAndCorrectlyFormattedOnConstructedRequests() {
        HttpUtil.configure("test@example.com");

        HttpRequest request = HttpUtil.newRequestBuilder("https://example.com/foo").GET().build();

        Optional<String> userAgent = request.headers().firstValue("User-Agent");
        assertTrue(userAgent.isPresent(), "User-Agent header must be set on every constructed request");
        assertTrue(userAgent.get().matches("^SwagProxy/\\S+ \\(.+\\)$"),
                "User-Agent must match \"SwagProxy/<version> (<contact>)\", was: " + userAgent.get());
        assertEquals("SwagProxy/dev (test@example.com)", userAgent.get(),
                "in an unpackaged test JVM there is no manifest, so version should fall back to \"dev\"");
    }

    @Test
    void placeholderContactIsUsedIfNeverConfigured() {
        // configure() with a blank value should fall back to the documented placeholder,
        // never send a blank/generic User-Agent.
        HttpUtil.configure("");

        HttpRequest request = HttpUtil.newRequestBuilder("https://example.com/bar").GET().build();

        String userAgent = request.headers().firstValue("User-Agent").orElseThrow();
        assertTrue(userAgent.contains(HttpUtil.DEFAULT_CONTACT_PLACEHOLDER),
                "blank contact should fall back to the default placeholder, was: " + userAgent);
    }
}
