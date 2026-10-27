package org.rpgengine.campaign;

import tools.jackson.databind.JsonNode;

/**
 * Public starting-decision metadata, with a private versioned storage marker. The engine
 * waits for free player input without supplying a plot, forced choice or executable action.
 * Unknown stored shapes are deliberately unsupported and disclose no private fields.
 */
public record StartingDecision(String type, String inputMode) {
    static final String STORED_JSON = "{\"type\":\"STARTING_SITUATION\",\"version\":1}";

    /** Construct detached public metadata; trusted callers supply protocol constants, with no I/O. */
    public StartingDecision { }

    /** Return whether stored JSON is exactly the supported marker; null/unknown shapes return false. */
    public static boolean supports(JsonNode value) {
        // An equal type with extra fields or a newer version is not permission to expose that payload.
        return value != null && value.isObject() && value.size() == 2
                && value.path("type").isString() && value.path("type").asText().equals("STARTING_SITUATION")
                && value.path("version").isIntegralNumber() && value.path("version").asText().equals("1");
    }
}
