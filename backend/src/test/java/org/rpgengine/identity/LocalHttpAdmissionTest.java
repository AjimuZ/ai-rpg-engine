package org.rpgengine.identity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/** Exercise local admission without storage; rejected browser inputs never reach a writer. */
class LocalHttpAdmissionTest {
    private final UUID owner = UUID.randomUUID();

    /** Create independent fixtures; construction has no external effects. */
    LocalHttpAdmissionTest() {
    }

    /** Bootstrap produces restricted cookies; valid mutations receive only configured owner context. */
    @Test
    void bindsOwnerAndRequiresIndependentCsrf() throws Exception {
        var filter = new LocalHttpAdmission("http://127.0.0.1:8080", owner.toString());
        var bootstrap = response(filter, request("GET", "/api/v1/session"));
        assertEquals(200, bootstrap.getStatus());
        assertTrue(bootstrap.getHeader("Set-Cookie").contains("HttpOnly"));
        assertTrue(bootstrap.getHeader("Set-Cookie").contains("SameSite=Strict"));
        assertFalse(bootstrap.getContentAsString().contains(owner.toString()));
        var cookie = bootstrap.getCookie("ENGINE_SESSION");
        assertNotNull(cookie);
        String csrf = JsonMapper.builder().build().readTree(bootstrap.getContentAsString()).get("csrfToken").asText();
        assertNotEquals(cookie.getValue(), csrf);
        var repeat = request("GET", "/api/v1/session");
        repeat.setCookies(cookie);
        assertEquals(bootstrap.getContentAsString(), response(filter, repeat).getContentAsString());
        var mutation = request("POST", "/api/v1/campaigns");
        mutation.setCookies(cookie);
        assertEquals(403, response(filter, mutation).getStatus());
        mutation.addHeader("X-CSRF-Token", csrf);
        mutation.removeHeader("Origin");
        assertEquals(403, response(filter, mutation).getStatus());
        mutation.addHeader("Origin", "http://127.0.0.1:8080");
        var admitted = new MockHttpServletResponse();
        filter.doFilter(mutation, admitted, (incoming, outgoing) ->
                assertEquals(owner, incoming.getAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE)));
        assertEquals(200, admitted.getStatus());
        mutation.addHeader("X-CSRF-Token", csrf);
        assertEquals(403, response(filter, mutation).getStatus());
        mutation.removeHeader("X-CSRF-Token");
        mutation.addHeader("X-CSRF-Token", csrf);
        mutation.addHeader("Content-Encoding", "gzip");
        assertEquals(415, response(filter, mutation).getStatus());
        mutation.removeHeader("Content-Encoding");
        mutation.setCookies(cookie, cookie);
        assertEquals(403, response(filter, mutation).getStatus());
    }

    /** Exact Host/Origin, fetch metadata and unique headers prevent cross-site admission and rebinding. */
    @Test
    void rejectsUntrustedAndAmbiguousHeaders() throws Exception {
        var filter = new LocalHttpAdmission("http://127.0.0.1:8080", owner.toString());
        for (String host : new String[] {"attacker.test:8080", "127.0.0.1:8081", "localhost:8080"}) {
            var request = request("GET", "/api/v1/session");
            request.removeHeader("Host");
            request.addHeader("Host", host);
            assertEquals(403, response(filter, request).getStatus());
        }
        for (String origin : new String[] {"null", "https://evil.test", "http://127.0.0.1:8081"}) {
            var request = request("GET", "/api/v1/session");
            request.addHeader("Origin", origin);
            assertEquals(403, response(filter, request).getStatus());
        }
        var request = request("GET", "/api/v1/session");
        request.addHeader("Sec-Fetch-Site", "cross-site");
        assertEquals(403, response(filter, request).getStatus());
        request = request("GET", "/api/v1/session");
        request.addHeader("Host", "127.0.0.1:8080");
        assertEquals(403, response(filter, request).getStatus());
        assertThrows(IllegalArgumentException.class, () -> new LocalHttpAdmission("http://evil.test:8080", owner.toString()));
        assertEquals(503, response(new LocalHttpAdmission("http://127.0.0.1:8080", ""),
                request("GET", "/api/v1/session")).getStatus());
    }

    /** Finite capacity fails closed; expired sessions free capacity and cannot authorize writes. */
    @Test
    void boundsAndExpiresSessions() throws Exception {
        Instant now = Instant.parse("2026-10-24T12:00:00Z");
        var clock = new MutableClock(now);
        var filter = new LocalHttpAdmission("http://127.0.0.1:8080", owner.toString(), clock);
        var first = response(filter, request("GET", "/api/v1/session"));
        for (int index = 1; index < 32; index++) {
            assertEquals(200, response(filter, request("GET", "/api/v1/session")).getStatus());
        }
        assertEquals(429, response(filter, request("GET", "/api/v1/session")).getStatus());
        clock.now = now.plusSeconds(1800);
        var request = request("POST", "/api/v1/campaigns");
        request.setCookies(first.getCookie("ENGINE_SESSION"));
        request.addHeader("X-CSRF-Token", JsonMapper.builder().build().readTree(first.getContentAsString()).get("csrfToken").asText());
        assertEquals(403, response(filter, request).getStatus());
        assertEquals(200, response(filter, request("GET", "/api/v1/session")).getStatus());
    }

    /** Concurrent writer capacity is finite and released even when a downstream writer throws. */
    @Test
    void boundsConcurrentMutationsAndReleasesFailedWriters() throws Exception {
        var filter = new LocalHttpAdmission("http://127.0.0.1:8080", owner.toString());
        var bootstrap = response(filter, request("GET", "/api/v1/session"));
        var cookie = bootstrap.getCookie("ENGINE_SESSION");
        String csrf = JsonMapper.builder().build().readTree(bootstrap.getContentAsString()).get("csrfToken").asText();
        var entered = new java.util.concurrent.CountDownLatch(4);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var writers = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            try {
                for (int index = 0; index < 4; index++) {
                    writers.add(pool.submit(() -> {
                        var request = request("POST", "/api/v1/campaigns");
                        request.setCookies(cookie);
                        request.addHeader("X-CSRF-Token", csrf);
                        assertThrows(jakarta.servlet.ServletException.class, () -> filter.doFilter(request,
                                new MockHttpServletResponse(), (incoming, outgoing) -> {
                                    entered.countDown();
                                    try {
                                        if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                                            throw new jakarta.servlet.ServletException("test release timed out");
                                        }
                                    } catch (InterruptedException failure) {
                                        Thread.currentThread().interrupt();
                                        throw new jakarta.servlet.ServletException(failure);
                                    }
                                    throw new jakarta.servlet.ServletException("test writer failure");
                                }));
                    }));
                }
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
                var excess = request("POST", "/api/v1/campaigns");
                excess.setCookies(cookie);
                excess.addHeader("X-CSRF-Token", csrf);
                assertEquals(429, response(filter, excess).getStatus());
            } finally {
                release.countDown();
            }
            for (var writer : writers) { writer.get(10, java.util.concurrent.TimeUnit.SECONDS); }
        }
        var request = request("POST", "/api/v1/campaigns");
        request.setCookies(cookie);
        request.addHeader("X-CSRF-Token", csrf);
        filter.doFilter(request, new MockHttpServletResponse(), (incoming, outgoing) ->
                assertEquals(owner, incoming.getAttribute(LocalHttpAdmission.OWNER_ATTRIBUTE)));
    }

    /** Build a same-origin request; callers deliberately replace headers to test rejection. */
    private static MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.addHeader("Host", "127.0.0.1:8080");
        if (method.equals("POST")) {
            request.addHeader("Origin", "http://127.0.0.1:8080");
            request.setContentType("application/json");
        }
        return request;
    }

    /** Run the actual filter and fail if an expected rejection or bootstrap escapes to a writer. */
    private static MockHttpServletResponse response(LocalHttpAdmission filter, MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> fail("unexpected writer admission"));
        return response;
    }

    /** Controllable UTC clock permits exact expiry tests without sleeping or changing production time. */
    private static final class MutableClock extends Clock {
        private Instant now;

        /** Store the test instant; no wall clock or external state is consulted. */
        private MutableClock(Instant now) { this.now = now; }

        /** Return UTC for session deadlines; never fails. */
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }

        /** Keep the UTC test clock; zone changes do not affect instant-based admission. */
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }

        /** Return the controlled instant; the test can advance it explicitly. */
        @Override public Instant instant() { return now; }
    }
}
