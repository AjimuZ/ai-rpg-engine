package org.rpgengine.inference.local;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;

/**
 * Validated adapter settings, read once from {@code engine.inference.local.*}. Values are owner
 * configuration, never task data; invalid values fail startup with a message that names the setting
 * but not its value.
 *
 * @param baseUrl scheme, host and port of the model server, without a trailing slash
 * @param queueCapacity bounded admission queue size (1–256)
 * @param requestTimeout connect and catalog deadline; a chat request is bounded by its task's budget
 * @param reconnectInitial first retry delay after a failed catalog read; doubles up to {@code reconnectMax}
 * @param reconnectMax longest retry delay
 * @param catalogInterval how often an idle adapter reads the catalog again, which keeps its status current
 * @param maxSessions conversations kept; the least recently used is forgotten beyond it
 * @param maxConversationBytes UTF-8 bytes of history plus the next task above which a conversation starts over
 * @param temperature sampling temperature sent on every request (0–2)
 * @param topP top-p sent on every request (above 0, at most 1)
 * @param topK top-k sent on every request (0–1000)
 */
record LocalSettings(String baseUrl, int queueCapacity, Duration requestTimeout, Duration reconnectInitial,
        Duration reconnectMax, Duration catalogInterval, int maxSessions, long maxConversationBytes,
        double temperature, double topP, int topK) {
    static final String PREFIX = "engine.inference.local.";

    /**
     * @param environment Spring environment
     * @return validated settings
     * @throws IllegalStateException naming the first invalid setting
     */
    static LocalSettings from(Environment environment) {
        String url = baseUrl(environment.getProperty(PREFIX + "url", ""));
        int capacity = number(environment, "queue-capacity", Integer.class, 16);
        int sessions = number(environment, "max-sessions", Integer.class, 256);
        if (capacity < 1 || capacity > 256 || sessions < 1 || sessions > 4096) {
            throw new IllegalStateException(PREFIX + "queue-capacity or max-sessions out of range");
        }
        long conversation = number(environment, "max-conversation-bytes", Long.class, 1024L * 1024);
        if (conversation < 64 * 1024 || conversation > 16 * 1024 * 1024) {
            throw new IllegalStateException(PREFIX + "max-conversation-bytes out of range");
        }
        // The documented starting point for the recommended model (docs/34 section 7).
        double temperature = number(environment, "temperature", Double.class, 1.0);
        double topP = number(environment, "top-p", Double.class, 0.95);
        int topK = number(environment, "top-k", Integer.class, 64);
        if (!(temperature >= 0 && temperature <= 2) || !(topP > 0 && topP <= 1) || topK < 0 || topK > 1000) {
            throw new IllegalStateException(PREFIX + "temperature, top-p or top-k out of range");
        }
        return new LocalSettings(url, capacity, duration(environment, "request-timeout", Duration.ofSeconds(10)),
                duration(environment, "reconnect-initial", Duration.ofSeconds(1)),
                duration(environment, "reconnect-max", Duration.ofSeconds(30)),
                duration(environment, "catalog-interval", Duration.ofSeconds(5)), sessions, conversation, temperature,
                topP, topK);
    }

    /** @return the URI of a server route, which starts with a slash */
    URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private static String baseUrl(String text) {
        URI uri;
        try {
            uri = new URI(text.strip());
        } catch (URISyntaxException malformed) {
            // The exception quotes its input, which may hold a private address.
            throw new IllegalStateException(PREFIX + "url must be an http or https URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || !(path.isEmpty() || path.equals("/"))) {
            throw new IllegalStateException(PREFIX + "url must be an http or https URL with a host and no path");
        }
        return scheme + "://" + uri.getRawAuthority();
    }

    private static <T> T number(Environment environment, String key, Class<T> type, T fallback) {
        try {
            return environment.getProperty(PREFIX + key, type, fallback);
        } catch (RuntimeException malformed) {
            // Conversion failures can quote the value.
            throw new IllegalStateException(PREFIX + key + " is not a number");
        }
    }

    private static Duration duration(Environment environment, String key, Duration fallback) {
        String text = environment.getProperty(PREFIX + key);
        Duration value;
        try {
            value = text == null || text.isBlank() ? fallback : DurationStyle.detectAndParse(text.strip());
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException(PREFIX + key + " is not a duration");
        }
        if (value.isNegative() || value.isZero() || value.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalStateException(PREFIX + key + " must be between 0 and 5 minutes");
        }
        return value;
    }
}
