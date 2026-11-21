package org.rpgengine.music;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Verify the reviewed music catalog admits only complete, correctly licensed records (P17.2-A). */
class MusicCatalogTest {
    /** Construct an independent corpus; no application context is needed. */
    MusicCatalogTest() {
    }

    /** The bundled catalog loads with exact license versions and no media URL. */
    @Test
    void bundledCatalogIsReviewed() {
        var catalog = MusicCatalog.load();
        assertEquals(2, catalog.recordings().size());
        assertEquals("CC-BY-4.0", catalog.find("wholesome").orElseThrow().licenseId());
        assertEquals("https://creativecommons.org/licenses/by/3.0/",
                catalog.find("darkest-child").orElseThrow().licenseUrl());
        assertTrue(catalog.find("unknown").isEmpty());
    }

    /** A license URL of another version, an unapproved host and a missing field are each refused. */
    @Test
    void refusesUnreviewedRecords() {
        String ok = record("CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/",
                "https://commons.wikimedia.org/wiki/File:A.ogg", "\"creator\":\"C\",");
        assertEquals(1, parse(ok).recordings().size());
        assertThrows(IllegalArgumentException.class, () -> parse(record("CC-BY-4.0",
                "https://creativecommons.org/licenses/by/3.0/", "https://commons.wikimedia.org/wiki/File:A.ogg",
                "\"creator\":\"C\",")));
        assertThrows(IllegalArgumentException.class, () -> parse(record("CC-BY-NC-4.0",
                "https://creativecommons.org/licenses/by-nc/4.0/", "https://commons.wikimedia.org/wiki/File:A.ogg",
                "\"creator\":\"C\",")));
        assertThrows(IllegalArgumentException.class, () -> parse(record("CC-BY-4.0",
                "https://creativecommons.org/licenses/by/4.0/", "https://example.com/a.ogg", "\"creator\":\"C\",")));
        assertThrows(IllegalArgumentException.class, () -> parse(record("CC-BY-4.0",
                "https://creativecommons.org/licenses/by/4.0/", "http://commons.wikimedia.org/wiki/File:A.ogg",
                "\"creator\":\"C\",")));
        assertThrows(IllegalArgumentException.class, () -> parse(record("CC-BY-4.0",
                "https://creativecommons.org/licenses/by/4.0/", "https://commons.wikimedia.org/wiki/File:A.ogg", "")));
    }

    private static MusicCatalog parse(String json) {
        return MusicCatalog.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String record(String license, String licenseUrl, String source, String creator) {
        return "{\"recordings\":[{\"id\":\"a\",\"title\":\"T\"," + creator + "\"mood\":\"tension\","
                + "\"sourcePage\":\"" + source + "\",\"mediaFile\":\"A.ogg\",\"licenseId\":\"" + license
                + "\",\"licenseName\":\"N\",\"licenseUrl\":\"" + licenseUrl + "\",\"notices\":\"n\","
                + "\"modifications\":\"none\",\"verifiedOn\":\"2026-10-23\"}]}";
    }
}
