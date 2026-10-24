package org.rpgengine.preparation;

import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Per-campaign query-scope epochs (docs/28 section 7). A canonical writer bumps each
 * scope it can affect inside its own transaction, so a plan that recorded the epoch
 * it read detects later writes, including phantoms that make an empty query non-empty.
 * Epochs never decrease (V6 trigger) and are not world state: bumping one changes no
 * revision, turn, time or event counter. A scope without a row has epoch 0.
 */
public final class QueryEpochs {
    /** Static helpers only; callers supply the transaction-bound JdbcTemplate. */
    private QueryEpochs() {
    }

    /**
     * Increment one scope within the caller's transaction and return the new epoch.
     * Concurrent bumps of the same scope serialize on the row; a rollback undoes the
     * bump together with the world change that caused it. Malformed scopes and unknown
     * campaigns fail (pattern check, then foreign key).
     */
    public static long bump(JdbcTemplate jdbc, UUID campaign, String scope) {
        Objects.requireNonNull(campaign, "campaign");
        PreparationSupport.require(PreparationSupport.SCOPE, scope, "scope");
        PreparationSupport.requireTransaction();
        // Every epoch writer serializes with admission, even outside a world commit.
        jdbc.queryForObject("select id from campaign where id = ? for no key update", UUID.class, campaign);
        return Objects.requireNonNull(jdbc.queryForObject("insert into query_epoch (campaign_id, scope_key, epoch) "
                + "values (?, ?, 1) on conflict (campaign_id, scope_key) do update "
                + "set epoch = query_epoch.epoch + 1, row_version = query_epoch.row_version + 1 returning epoch",
                Long.class, campaign, scope));
    }

    /** Return the committed (or own-transaction) epoch of one scope; 0 when never bumped. */
    public static long current(JdbcTemplate jdbc, UUID campaign, String scope) {
        Objects.requireNonNull(campaign, "campaign");
        PreparationSupport.require(PreparationSupport.SCOPE, scope, "scope");
        var values = jdbc.queryForList("select epoch from query_epoch where campaign_id = ? and scope_key = ?",
                Long.class, campaign, scope);
        return values.isEmpty() ? 0 : values.getFirst();
    }
}
