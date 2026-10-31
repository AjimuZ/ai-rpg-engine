package org.rpgengine.inference.image;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validated settings of the {@code a1111-http} adapter. The engine never contacts the owner's server
 * itself: every call goes through the gateway to one destination of the owner's allowlist, which the
 * gateway pins to the three routes of the protocol. Values are owner configuration, never game data;
 * an invalid value fails with a message naming the field and not the value.
 *
 * @param gatewayUrl scheme, host and port of the gateway, without a path
 * @param destination identifier of the gateway destination that holds the {@code a1111-http} protocol
 * @param generateTimeout bound of one generation call; the gateway's own bound for the destination
 *        should be shorter, so that its fixed refusal arrives first
 * @param controlTimeout bound of the connect step and of the progress and interrupt calls
 * @param maxQueued jobs that may wait behind the running one before a submission fails
 */
record A1111Settings(String gatewayUrl, String destination, Duration generateTimeout, Duration controlTimeout,
        int maxQueued) {
    static final Duration DEFAULT_GENERATE_TIMEOUT = Duration.ofSeconds(330);
    static final Duration DEFAULT_CONTROL_TIMEOUT = Duration.ofSeconds(10);
    static final int DEFAULT_MAX_QUEUED = 8;
    private static final Pattern DESTINATION = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    A1111Settings {
        gatewayUrl = origin(gatewayUrl);
        require(destination != null && DESTINATION.matcher(destination).matches(), "destination");
        require(generateTimeout != null && positive(generateTimeout) && generateTimeout.compareTo(Duration.ofMinutes(20)) <= 0,
                "generateTimeout");
        require(controlTimeout != null && positive(controlTimeout) && controlTimeout.compareTo(Duration.ofMinutes(5)) <= 0,
                "controlTimeout");
        require(maxQueued >= 1 && maxQueued <= 64, "maxQueued");
    }

    /**
     * @param gatewayUrl gateway origin
     * @param destination gateway destination identifier
     * @return settings with the documented time bounds and queue size
     */
    static A1111Settings of(String gatewayUrl, String destination) {
        return new A1111Settings(gatewayUrl, destination, DEFAULT_GENERATE_TIMEOUT, DEFAULT_CONTROL_TIMEOUT,
                DEFAULT_MAX_QUEUED);
    }

    /** @return the URI of a protocol route below the destination, such as {@code /sdapi/v1/txt2img} */
    URI uri(String route) {
        return URI.create(gatewayUrl + "/upstream/" + destination + route);
    }

    private static boolean positive(Duration value) {
        return !value.isNegative() && !value.isZero();
    }

    private static String origin(String text) {
        URI uri;
        try {
            uri = new URI(text == null ? "" : text.strip());
        } catch (URISyntaxException malformed) {
            // The exception quotes its input, which may hold a private address.
            throw new IllegalArgumentException("gatewayUrl must be an http or https URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || !(path.isEmpty() || path.equals("/"))) {
            throw new IllegalArgumentException("gatewayUrl must be an http or https URL with a host and no path");
        }
        return scheme + "://" + uri.getRawAuthority();
    }

    private static void require(boolean valid, String field) {
        if (!valid) {
            throw new IllegalArgumentException(field + " is out of range");
        }
    }
}
