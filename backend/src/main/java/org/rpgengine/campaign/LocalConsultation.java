package org.rpgengine.campaign;

import java.util.List;
import java.util.Locale;

/**
 * Pure exact-command routing and detached presentation delivery for local consultations.
 * Input is bounded player text and an authorized committed projection; output has no
 * canonical receipt, operation identity or writer. Unsupported text fails closed without
 * invoking interpretation. Optional transcript persistence belongs to a separate boundary.
 */
public final class LocalConsultation {
    /** Prevent construction of this stateless parser/presentation adapter. */
    private LocalConsultation() { }

    /** The only exact read intents; these never represent attempted world actions. */
    public enum Kind { STATUS, INVENTORY }

    /**
     * Parse a whole English/Italian alias after outer whitespace and locale-neutral case
     * normalization. Null, over 64 UTF-16 units, prose and unsupported commands throw
     * IllegalArgumentException; no fuzzy matching, punctuation removal or fallback occurs.
     */
    public static Kind parse(String command) {
        if (command == null || command.length() > 64) {
            throw new IllegalArgumentException("invalid consultation command");
        }
        return switch (command.strip().toLowerCase(Locale.ROOT)) {
            case "status", "stato" -> Kind.STATUS;
            case "inventory", "inventario" -> Kind.INVENTORY;
            default -> throw new IllegalArgumentException("unsupported consultation command");
        };
    }

    /**
     * Project one authorized snapshot into a typed transcript-ready passage. The source
     * scope is a freshness token, never a new delivery/world revision. Inputs must be
     * non-null; failures propagate without side effects. Repeating delivery is read-only.
     */
    public static Delivery deliver(Kind kind, CampaignProjectionService.State state) {
        Object data = switch (kind) {
            case STATUS -> new Status(state.player(), state.resources());
            case INVENTORY -> state.inventory();
        };
        // Keep the passage separate from canonical state: displaying/retrying it cannot commit a turn.
        return new Delivery("DETERMINISTIC_CONSULTATION", state.scope(), new Passage(kind, data));
    }

    /** Presentation envelope with source scope; no submitted action, event or delivery counter is fabricated. */
    public record Delivery(String type, CampaignProjectionService.Scope scope, Passage passage) { }

    /** Typed exact data for rendering in a panel or transient transcript, without invented narrative prose. */
    public record Passage(Kind kind, Object data) { }

    /** Own character and exact resources only; legacy saves retain a null player rather than inventing one. */
    public record Status(CampaignProjectionService.Player player, List<CampaignProjectionService.Resource> resources) { }
}
