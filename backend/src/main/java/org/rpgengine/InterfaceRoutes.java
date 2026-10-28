package org.rpgengine;

import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serve compiled indexes for the finite interface route grammar (local previews and the
 * saved-adventure view addressed by a canonical lowercase UUID), only when locale output is
 * packaged. APIs, unknown routes and missing files keep ordinary 404s. Neither identities
 * nor query values nor a stored preference ever reach a resource path: every handler
 * returns one of two fixed classpath indexes, so a reload of a saved playthrough can switch
 * language without a server template.
 */
@RestController
@ConditionalOnResource(resources = {"classpath:static/en/index.html", "classpath:static/it/index.html"})
public class InterfaceRoutes {
    /** Canonical lowercase UUID, the only identity spelling the server emits for saved scopes. */
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    /** Construct the stateless route adapter; there are no inputs or failure paths. */
    public InterfaceRoutes() {
    }

    /**
     * Return the root selector for a known unprefixed GET route. The browser
     * chooses the saved language; resource I/O failures propagate as HTTP errors.
     */
    @GetMapping({"/library", "/settings", "/interface-kit", "/saved/{id:" + UUID + "}",
            "/adventures/{id:[a-zA-Z0-9_-]+}",
            "/adventures/{id:[a-zA-Z0-9_-]+}/{screen:story|create|sheet|inventory|journal|guide|creative}"})
    public ResponseEntity<Resource> legacyEntry() {
        return index("index.html");
    }

    /**
     * Return the requested allowlisted locale index for an explicit preview GET.
     * Unsupported locales and resource-like paths never match this handler.
     */
    @GetMapping({"/{locale:en|it}", "/{locale:en|it}/", "/{locale:en|it}/library",
            "/{locale:en|it}/settings", "/{locale:en|it}/interface-kit", "/{locale:en|it}/saved/{id:" + UUID + "}",
            "/{locale:en|it}/adventures/{id:[a-zA-Z0-9_-]+}",
            "/{locale:en|it}/adventures/{id:[a-zA-Z0-9_-]+}/{screen:story|create|sheet|inventory|journal|guide|creative}"})
    public ResponseEntity<Resource> localizedEntry(@PathVariable String locale) {
        return index(locale + "/index.html");
    }

    /** Return an HTML resource without index caching; missing bytes fail at HTTP write time. */
    private ResponseEntity<Resource> index(String path) {
        // Indexes must revalidate on reload so language/catalog deployments cannot
        // strand a browser on removed bundle hashes. Hashed bundles stay static.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_HTML).body(new ClassPathResource("static/" + path));
    }
}
