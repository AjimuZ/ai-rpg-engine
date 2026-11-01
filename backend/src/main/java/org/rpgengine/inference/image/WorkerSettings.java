package org.rpgengine.inference.image;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import org.springframework.core.env.Environment;

/**
 * Validated settings of the packaged image worker's client. Only the address is an owner setting, read from
 * {@value #URL}; the time bounds and the queue are placeholders fixed here and have not been measured. An invalid
 * address fails startup with a message that names the setting but not its value.
 *
 * @param baseUrl scheme, host and port of the worker, without a trailing slash
 * @param loadTimeout bound of one load call, which the worker answers only when the checkpoint is resident
 * @param generateTimeout bound of one generation call
 * @param controlTimeout bound of the connect step and of the model list, unload and cancel calls
 * @param maxQueued jobs that may wait behind the running one before a submission fails
 */
record WorkerSettings(String baseUrl, Duration loadTimeout, Duration generateTimeout, Duration controlTimeout,
        int maxQueued) {
    static final String URL = "engine.inference.image-worker.url";
    /** The executor's four minutes of status checks, which the lease's five minutes outlast: a lease cannot renew during a load. */
    private static final Duration DEFAULT_LOAD_TIMEOUT = Duration.ofMinutes(4);
    private static final Duration DEFAULT_GENERATE_TIMEOUT = Duration.ofSeconds(330);
    private static final Duration DEFAULT_CONTROL_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_MAX_QUEUED = 8;

    /**
     * @param environment source of {@value #URL}, which must be set
     * @return settings with the fixed time bounds and queue
     * @throws IllegalStateException when the address is not an http or https origin
     */
    static WorkerSettings from(Environment environment) {
        return new WorkerSettings(origin(environment.getProperty(URL, "")), DEFAULT_LOAD_TIMEOUT, DEFAULT_GENERATE_TIMEOUT,
                DEFAULT_CONTROL_TIMEOUT, DEFAULT_MAX_QUEUED);
    }

    /** @return the URI of a worker route, which starts with a slash */
    URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private static String origin(String text) {
        URI uri;
        try {
            uri = new URI(text.strip());
        } catch (URISyntaxException malformed) {
            // The exception quotes its input, which may hold a private address.
            throw new IllegalStateException(URL + " must be an http or https URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || !(path.isEmpty() || path.equals("/"))) {
            throw new IllegalStateException(URL + " must be an http or https URL with a host and no path");
        }
        return scheme + "://" + uri.getRawAuthority();
    }
}
