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
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        for (String locale : new String[]{"en", "it"}) {
            URI origin = URI.create("http://127.0.0.1:" + port + "/" + locale + "/");
            try (HttpClient client = HttpClient.newHttpClient()) {
                var index = client.send(HttpRequest.newBuilder(origin).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, index.statusCode());
                assertTrue(index.headers().firstValue("content-type").orElse("").contains("text/html"));
                assertTrue(index.body().contains("<title>Adventure engine</title>"));
                assertTrue(index.body().contains("<base href=\"/" + locale + "/\">"));
                assertTrue(index.body().contains("lang=\"" + locale + "\""));
                assertTrue(index.body().contains("<app-root>"));
                assertEquals("no-store", index.headers().firstValue("cache-control").orElse(""));
                // Deep links return exactly the same compiled index, never a rebuilt
                // or translated server template; query values cannot choose resources.
                for (String route : new String[]{"library", "settings", "interface-kit",
                        "adventures/mosslight/story", "adventures/relay/create",
                        "adventures/mosslight/sheet", "adventures/relay/inventory",
                        "adventures/relay/journal", "adventures/relay/guide", "adventures/relay/creative",
                        "saved/00000000-0000-4000-8000-00000000000a"}) {
                    // The saved playthrough query is opaque too: the SPA, not the server, interprets it on reload.
                    var deep = client.send(HttpRequest.newBuilder(origin.resolve(route + "?opaque=it&playthrough=x#frag")).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, deep.statusCode(), route);
                    assertEquals(index.body(), deep.body(), route);
                }

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
                    assertArrayEquals(new ClassPathResource("static" + asset.getPath()).getContentAsByteArray(),
                            response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8), asset.toString());
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
    }

    /** Verify the root selector is local and explicit; POST/API requests never redirect to a locale. */
    @Test
    void servesRootSelectorWithoutRedirectingCommands() throws Exception {
        URI origin = URI.create("http://127.0.0.1:" + port + "/");
        try (HttpClient client = HttpClient.newHttpClient()) {
            var root = client.send(HttpRequest.newBuilder(origin).header("Accept-Language", "it").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, root.statusCode());
            assertTrue(root.body().contains("href=\"/en/\""));
            assertTrue(root.body().contains("href=\"/it/\""));
            var script = client.send(HttpRequest.newBuilder(origin.resolve("locale-entry.js")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, script.statusCode());
            assertArrayEquals(new ClassPathResource("static/locale-entry.js").getContentAsByteArray(), script.body());
            for (String path : new String[]{"", "settings", "it/settings", "api/v1/missing"}) {
                var post = client.send(HttpRequest.newBuilder(origin.resolve(path))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
                assertTrue(post.statusCode() >= 400, path);
                assertFalse(post.headers().firstValue("location").isPresent(), path);
            }
        }
    }

    /**
     * Fetch the production compiler's extracted license notices; missing Angular
     * attribution or an HTTP failure rejects an incomplete distributable jar.
     */
    @Test
    void servesBundledThirdPartyNotices() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (String prefix : new String[]{"/", "/en/", "/it/"}) {
                var response = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + prefix + "3rdpartylicenses.txt")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertTrue(response.body().contains("Package: @angular/core"));
                assertTrue(response.body().contains("The MIT License"));
                for (String asset : new String[]{"ui/LICENSE.txt", "ui/README.md", "scenes/woodland.svg", "portraits/keeper.svg"}) {
                    var notice = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + prefix + asset))
                            .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                    assertEquals(200, notice.statusCode(), prefix + asset);
                    assertArrayEquals(new ClassPathResource("static/" + asset).getContentAsByteArray(), notice.body());
                }
            }
        }
    }

    /**
     * Probe absent API, bundle, source and configuration URLs; every input must
     * produce 404, with connection failures or an HTML fallback failing the test.
     */
    @Test
    void keepsMissingResourcesAndApiRoutesAsNotFound() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            // The finite route grammar must never hide missing resources or API errors.
            for (String path : new String[]{"/api/does-not-exist", "/missing-bundle.js",
                    "/src/app/app.ts", "/.env", "/actuator/env", "/api/v1/missing", "/fr/settings",
                    "/en/missing-bundle.js", "/it/missing.css", "/it/api/v1/missing", "/en/.env",
                    "/it/unknown", "/it/adventures/missing.js", "/it/adventures/relay/unknown",
                    // Only canonical lowercase UUIDs address saved scopes; nothing deeper exists.
                    "/saved/not-a-uuid", "/it/saved/00000000-0000-4000-8000-00000000000A",
                    "/saved/00000000-0000-4000-8000-00000000000a/extra", "/en/saved/", "/saved/00000000-0000-4000-8000-00000000000a.js"}) {
                var response = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                assertEquals(404, response.statusCode(), path);
            }
        }
    }
}
