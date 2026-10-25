package org.rpgengine.identity;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Admit local API traffic using a configured exact loopback origin and server-side owner.
 * Bootstrap returns independent random session/CSRF secrets; invalid requests get bounded
 * public error codes and never reach a writer. Sessions are ephemeral, fixed-lived and capped.
 * This is local single-owner admission, not authentication for a publicly hosted service.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LocalHttpAdmission extends OncePerRequestFilter {
    public static final String OWNER_ATTRIBUTE = LocalHttpAdmission.class.getName() + ".owner";
    private static final String COOKIE = "ENGINE_SESSION";
    private static final int LIFETIME_SECONDS = 1800;
    private final String origin;
    private final String authority;
    private final UUID owner;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new HashMap<>();
    private final Semaphore mutations = new Semaphore(4);

    /** Configure the local origin and optional owner; invalid explicit configuration fails startup. */
    @Autowired
    public LocalHttpAdmission(@Value("${engine.http.origin:http://127.0.0.1:8080}") String origin,
            @Value("${engine.http.owner-id:}") String ownerId) {
        this(origin, ownerId, Clock.systemUTC());
    }

    /** Inject a clock for expiry tests; accept only canonical HTTP loopback origins and UUID owners. */
    LocalHttpAdmission(String origin, String ownerId, Clock clock) {
        URI uri = URI.create(origin);
        if (!"http".equals(uri.getScheme()) || !Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost())
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !uri.getPath().isEmpty() || uri.getPort() < 1 || uri.getPort() > 65535
                || !origin.equals("http://" + uri.getHost() + ":" + uri.getPort())) {
            throw new IllegalArgumentException("engine.http.origin must be an exact HTTP loopback origin with port");
        }
        this.origin = origin;
        authority = uri.getRawAuthority();
        owner = ownerId.isEmpty() ? null : UUID.fromString(ownerId);
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    /**
     * Validate API authority before bootstrap or routing. Unsafe methods require exact Origin,
     * one live session and independent CSRF proof. Four concurrent mutations bound writer load.
     * Unimplemented routes and static/health requests retain existing routing; servlet failures propagate.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // Guard every method in both scope subtrees, including malformed identities and
        // unsupported sections, so future workspace/campaign routes cannot omit admission.
        if (!Set.of("/api/v1/session", "/api/v1/campaigns", "/api/v1/adventures").contains(path)
                && !path.startsWith("/api/v1/campaigns/") && !path.startsWith("/api/v1/adventures/")) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        String host = singleHeader(request, "Host");
        String suppliedOrigin = singleHeader(request, "Origin");
        String fetchSite = singleHeader(request, "Sec-Fetch-Site");
        if (!authority.equals(host) || (suppliedOrigin != null && !origin.equals(suppliedOrigin))
                || (fetchSite != null && !Set.of("same-origin", "none").contains(fetchSite))) {
            reject(response, 403, "LOCAL_ADMISSION_DENIED");
            return;
        }
        if (owner == null) {
            reject(response, 503, "LOCAL_OWNER_UNCONFIGURED");
            return;
        }
        if (path.equals("/api/v1/session") && request.getMethod().equals("GET")) {
            bootstrap(request, response);
            return;
        }
        boolean safe = Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
        Session session = findSession(request);
        if (session == null || (!safe && (!origin.equals(suppliedOrigin)
                || !sameToken(session.csrf(), singleHeader(request, "X-CSRF-Token"))))) {
            reject(response, 403, "LOCAL_ADMISSION_DENIED");
            return;
        }
        if (!safe && (!jsonContentType(request) || request.getHeader("Content-Encoding") != null)) {
            reject(response, 415, "JSON_REQUIRED");
            return;
        }
        if (!safe && !mutations.tryAcquire()) {
            reject(response, 429, "LOCAL_CAPACITY_EXCEEDED");
            return;
        }
        try {
            // Forwarded headers and client owner fields never influence authority or ownership.
            request.setAttribute(OWNER_ATTRIBUTE, owner);
            chain.doFilter(request, response);
        } finally {
            if (!safe) {
                mutations.release();
            }
        }
    }

    /** Issue or reuse one fixed-lived session under the map lock; capacity fails without eviction. */
    private synchronized void bootstrap(HttpServletRequest request, HttpServletResponse response) throws IOException {
        expire();
        String id = cookie(request);
        Session session = sessions.get(id);
        if (session == null) {
            if (sessions.size() >= 32) {
                reject(response, 429, "LOCAL_CAPACITY_EXCEEDED");
                return;
            }
            id = token();
            session = new Session(token(), clock.instant().plusSeconds(LIFETIME_SECONDS));
            sessions.put(id, session);
        }
        // HttpOnly hides the bearer cookie from scripts; CSRF is returned only in same-origin JSON.
        response.addHeader("Set-Cookie", ResponseCookie.from(COOKIE, id).path("/").httpOnly(true)
                .sameSite("Strict").maxAge(java.time.Duration.between(clock.instant(), session.expires()))
                .build().toString());
        response.setContentType("application/json");
        response.getWriter().write("{\"csrfToken\":\"" + session.csrf() + "\"}");
    }

    /** Find a single bearer cookie after expiry cleanup; malformed/duplicate/unknown cookies fail closed. */
    private synchronized Session findSession(HttpServletRequest request) {
        expire();
        return sessions.get(cookie(request));
    }

    /** Remove expired entries while holding the map lock; fixed expiry bounds stolen-token lifetime. */
    private void expire() {
        Instant now = clock.instant();
        sessions.values().removeIf(session -> !now.isBefore(session.expires()));
    }

    /** Extract exactly one bounded URL-safe bearer value; ambiguity is never resolved by cookie order. */
    private static String cookie(HttpServletRequest request) {
        String found = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE.equals(cookie.getName())) {
                    if (found != null || !cookie.getValue().matches("[A-Za-z0-9_-]{43}")) {
                        return null;
                    }
                    found = cookie.getValue();
                }
            }
        }
        return found;
    }

    /** Return one header or an impossible sentinel for duplicates, preventing first/last-value ambiguity. */
    private static String singleHeader(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        if (!values.hasMoreElements()) {
            return null;
        }
        String value = values.nextElement();
        return values.hasMoreElements() ? "\u0000" : value;
    }

    /** Accept only JSON, optionally UTF-8; malformed, duplicated and misleading media types fail closed. */
    private static boolean jsonContentType(HttpServletRequest request) {
        String value = singleHeader(request, "Content-Type");
        if (value == null) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(value);
            return type.getType().equals("application") && type.getSubtype().equals("json")
                    && type.getParameters().keySet().stream().allMatch("charset"::equals)
                    && (type.getCharset() == null || type.getCharset().equals(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    /** Compare bounded CSRF proofs in constant time; absent or malformed tokens always fail. */
    private static boolean sameToken(String expected, String actual) {
        return actual != null && actual.matches("[A-Za-z0-9_-]{43}")
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }

    /** Return an unguessable 256-bit URL-safe token; SecureRandom failures propagate rather than downgrade. */
    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Write a fixed public error code; never include raw headers, cookies, exceptions or owner identities. */
    private static void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }

    /** Immutable private session proof and absolute expiry; never serialized or persisted. */
    private record Session(String csrf, Instant expires) {
    }
}
