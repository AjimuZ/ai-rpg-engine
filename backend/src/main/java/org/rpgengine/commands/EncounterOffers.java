package org.rpgengine.commands;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.rpgengine.combat.EncounterTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Read side of encounter entry (P13.2-B, ADR 0042): the authored encounters the player of one campaign could start now with a
 * plain {@code ENGAGE}. It applies the command's own gates — the exploration mode, decision and player binding of
 * {@link CommandSemantics} and the {@code ENGAGE} rule of {@link CommandRules} — rather than a copy of them, so the list names
 * exactly the templates the same committed state would admit and never one a submission would refuse with
 * {@code ENCOUNTER_UNAVAILABLE}. A template at another location, of another vocabulary, for a defeated player, during a fight or
 * under a paused campaign is therefore never confirmed to exist.
 *
 * <p>The result is a full template for the caller to project; what a player may see of it is the caller's decision (the
 * combat projection exposes only the facts docs/11 section 3 lets entry reveal). Nothing here writes, locks or draws.
 */
public final class EncounterOffers {
    /** Static read helper with no state. */
    private EncounterOffers() { }

    /**
     * List the encounters the campaign's player may start now.
     *
     * @param jdbc JDBC access joined to the caller's transaction, which has already authorized the campaign's scope
     * @param campaign campaign identity
     * @param content the campaign's pinned catalog, loaded before the caller's transaction by
     *        {@link CommandRuleCatalogs#pinned}
     * @return admissible templates in template ID order; empty when nothing may be started
     * @throws org.springframework.dao.DataAccessException for storage failures
     */
    public static List<EncounterTemplate> offered(JdbcTemplate jdbc, UUID campaign, CommandRuleCatalogs.Content content) {
        Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(content, "content");
        UUID player = CommandSemantics.explorer(jdbc, campaign);
        return player == null ? List.of() : CommandRules.engageable(jdbc, campaign, player, content);
    }
}
