package org.rpgengine;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verify compiled browser resources through the real application HTTP server.
 * The packaged-ui build supplies Angular output and enables this fixture; ordinary
 * backend builds need no frontend toolchain. Missing assets, a broken database,
 * external asset URLs or unexpected HTTP responses fail rather than skip checks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "engine.packaged-ui", matches = "true")
class PackagedInterfaceTest {
    @LocalServerPort
    private int port;

    /** Construct the injected fixture; framework setup failures fail the suite. */
    PackagedInterfaceTest() {
    }

    /**
     * Fetch the Angular index and its bundles from one origin; HTTP, content-type,
     * missing-resource or origin assertion failures fail the packaging contract.
     */
    @Test
    void servesCompiledInterfaceAndBundles() throws Exception {
        URI origin = URI.create("http://127.0.0.1:" + port + "/");
        try (HttpClient client = HttpClient.newHttpClient()) {
            var index = client.send(HttpRequest.newBuilder(origin).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, index.statusCode());
            assertTrue(index.headers().firstValue("content-type").orElse("").contains("text/html"));
            assertTrue(index.body().contains("<title>Adventure engine</title>"));
            assertTrue(index.body().contains("<base href=\"/\">"));
            assertTrue(index.body().contains("<app-root>"));

            // Check the built filenames rather than hard-coding content hashes;
            // the production compiler may split bundles differently over time.
            var assets = Pattern.compile("(?:src|href)=\"([^\"]+\\.(?:js|css))\"")
                    .matcher(index.body());
            int scripts = 0;
            int styles = 0;
            while (assets.find()) {
                URI asset = origin.resolve(assets.group(1));
                assertEquals(origin.getScheme(), asset.getScheme());
                assertEquals(origin.getAuthority(), asset.getAuthority());
                var response = client.send(HttpRequest.newBuilder(asset).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), asset.toString());
                assertFalse(response.body().isBlank(), asset.toString());
                String type = response.headers().firstValue("content-type").orElse("");
                if (asset.getPath().endsWith(".js")) {
                    assertTrue(type.contains("javascript"), type);
                    scripts++;
                } else {
                    assertTrue(type.contains("text/css"), type);
                    styles++;
                }
            }
            assertTrue(scripts > 0, "The production index must load JavaScript");
            assertTrue(styles > 0, "The production index must load styles");
        }
    }

    /**
     * Fetch the production compiler's extracted license notices; missing Angular
     * attribution or an HTTP failure rejects an incomplete distributable jar.
     */
    @Test
    void servesBundledThirdPartyNotices() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/3rdpartylicenses.txt")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("Package: @angular/core"));
            assertTrue(response.body().contains("The MIT License"));
        }
    }

    /**
     * Probe absent API, bundle, source and configuration URLs; every input must
     * produce 404, with connection failures or an HTML fallback failing the test.
     */
    @Test
    void keepsMissingResourcesAndApiRoutesAsNotFound() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            // A broad SPA fallback would hide API errors and missing bundles.
            // Add explicit client-route handling only when actual routes exist.
            for (String path : new String[]{"/api/does-not-exist", "/missing-bundle.js",
                    "/src/app/app.ts", "/.env", "/actuator/env"}) {
                var response = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                assertEquals(404, response.statusCode(), path);
            }
        }
    }
}
