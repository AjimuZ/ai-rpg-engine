package org.rpgengine.commands;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Current-state rule gate for registered intentions (P07.3), shared by admission and
 * canonical commit. It runs after {@link CommandSemantics} in the same transaction, while
 * the caller holds the campaign row lock that every canonical writer takes first, so the
 * facts read here cannot change before the caller's journal insert or effects commit.
 *
 * <p>Checked rules, each against committed state rather than a copied validation flag:
 * <ul>
 *   <li><b>Reachability and knowledge</b> — {@code MOVE} needs a current location, a different
 *       disclosed pinned destination and an accessible authored link from here.</li>
 *   <li><b>Recipient knowledge and range</b> — {@code SPEAK} and ability targets must be other
 *       campaign actors that share the player's current location and are not concealed.</li>
 *   <li><b>Ownership and prerequisites</b> — {@code USE_ABILITY} needs a pinned ability the player
 *       has learned, its prerequisite abilities and skill ranks.</li>
 *   <li><b>Target policy and resources</b> — target count must fit the ability's policy and every
 *       declared nonzero cost must be affordable above the minimum of the acting player's own
 *       resource from the same pack vocabulary as the ability.</li>
 *   <li><b>Executable effects</b> — the ability's effect program must be one the local resolver
 *       executes, read actor values only in the player's own vocabulary and fit the per-operation
 *       durable draw budget (P07.4).</li>
 *   <li><b>Causal attribution</b> — at commit, resolved effects may only touch the participants
 *       this intention lawfully involves ({@link #requireAttributed}).</li>
 * </ul>
 * {@code WAIT} has no additional rule here; its legal duration belongs to the world clock
 * (P10.4). {@link LocalActionResolver} calculates the effects of an intention that passed here.
 * Every rule rejection is a sanitized {@link OperationAdmissionFailure}; an unattributable
 * resolved effect is a trusted-resolver defect and raises {@link IllegalStateException}.
 * Both escape the caller's transaction and therefore roll back everything it wrote.
 */
final class CommandRules {
    /** Static transaction participant with no lifecycle or external clients. */
    private CommandRules() { }

    /**
     * Validate one decoded intention for the already eligible acting player.
     *
     * @param jdbc JDBC access joined to the caller's locked transaction
     * @param campaign campaign whose row the caller has locked
     * @param player acting player returned by {@link CommandSemantics#requireEligible}
     * @param command decoded intention from the frozen canonical bytes
     * @param content catalog preloaded outside the lock; required only by rules that need definitions
     * @return actors this intention involves: the player plus a recipient or explicit targets
     * @throws OperationAdmissionFailure with the first violated rule's category
     */
    static Set<UUID> require(JdbcTemplate jdbc, UUID campaign, UUID player, TypedCommand command,
            CommandRuleCatalogs.Content content) {
        var check = new Check(jdbc, campaign, player, content);
        var participants = new LinkedHashSet<UUID>();
        participants.add(player);
        switch (command) {
            case TypedCommand.Move move -> check.reachable(move.destinationId());
            case TypedCommand.Wait ignored -> { }
            case TypedCommand.Speak speech -> {
                if (speech.recipientActorId().equals(player)) {
                    throw fail(OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE);
                }
                check.perceivable(speech.recipientActorId());
                participants.add(speech.recipientActorId());
            }
            case TypedCommand.UseAbility use -> participants.addAll(check.ability(use));
        }
        return Set.copyOf(participants);
    }

    /**
     * Reject resolved effects that the admitted intention cannot have caused: every adjusted
     * resource and every placed actor must belong to a participant, and every event actor must
     * be absent or a participant. A resolver bug or a forged plan therefore cannot charge or credit an
     * uninvolved actor. Runs under the commit's campaign lock before any entity changes.
     *
     * @throws IllegalStateException for an unattributable or out-of-scope effect
     */
    static void requireAttributed(JdbcTemplate jdbc, UUID campaign, Set<UUID> participants, ResolvedCommit plan) {
        for (var change : plan.resources()) {
            List<UUID> owner = jdbc.query("select actor_id from actor_resource where id = ? and campaign_id = ?",
                    (row, index) -> row.getObject(1, UUID.class), change.resourceId(), campaign);
            if (owner.isEmpty() || !participants.contains(owner.getFirst())) {
                throw new IllegalStateException("resolved effect is not attributable to the admitted intention");
            }
        }
        for (var placement : plan.placements()) {
            if (!participants.contains(placement.actorId())) {
                throw new IllegalStateException("resolved effect is not attributable to the admitted intention");
            }
        }
        for (var event : plan.events()) {
            if (event.actorId() != null && !participants.contains(event.actorId())) {
                throw new IllegalStateException("resolved effect is not attributable to the admitted intention");
            }
        }
    }

    /** Build a sanitized category for {@code throw}; the caller's transaction rolls back every write. */
    private static OperationAdmissionFailure fail(OperationAdmissionFailure.Kind kind) {
        return new OperationAdmissionFailure(kind);
    }

    /**
     * Call-local rule reader. The pinned catalog is resolved lazily so that rules needing
     * no definitions (for example speech to an actor without an authored origin) never fail
     * merely because content is unavailable.
     */
    private static final class Check {
        private final JdbcTemplate jdbc;
        private final UUID campaign;
        private final UUID player;
        private final CommandRuleCatalogs.Content content;
        private CommandRuleCatalog catalog;
        private String here;
        private String origin;
        private boolean located;

        /** Bind the locked campaign, acting player and preloaded content. */
        Check(JdbcTemplate jdbc, UUID campaign, UUID player, CommandRuleCatalogs.Content content) {
            this.jdbc = jdbc;
            this.campaign = campaign;
            this.player = player;
            this.content = content;
        }

        /** Return the catalog for the campaign's frozen revision, or fail closed. */
        private CommandRuleCatalog catalog() {
            if (catalog == null) {
                catalog = content.require(jdbc.queryForObject("select content_revision_id from campaign where id = ?",
                        UUID.class, campaign));
            }
            return catalog;
        }

        /** Current committed location of the player; null when the save records none. */
        private String here() {
            if (!located) {
                // Location is mutable world state; the creation-profile origin is frozen by a trigger.
                var row = jdbc.queryForObject("select location_id, definition_id from actor "
                        + "where campaign_id = ? and id = ?", (result, index) -> new String[] {result.getString(1),
                        result.getString(2)}, campaign, player);
                here = row[0];
                origin = row[1];
                located = true;
            }
            return here;
        }

        /** Canonical creation-profile ID the player was created from; null for legacy rows. */
        private String origin() {
            here();
            return origin;
        }

        /**
         * Movement uses one authored link. Unknown, concealed, current, unlinked and blocked
         * destinations share one category so a secret location or route is never confirmed.
         */
        void reachable(String destination) {
            String current = here();
            if (current == null || current.equals(destination)) {
                throw fail(OperationAdmissionFailure.Kind.DESTINATION_UNAVAILABLE);
            }
            var rules = catalog();
            var origin = rules.locations().get(current);
            var target = rules.locations().get(destination);
            if (origin == null || target == null || !target.disclosed()
                    || !origin.accessibleLinks().contains(destination)) {
                throw fail(OperationAdmissionFailure.Kind.DESTINATION_UNAVAILABLE);
            }
        }

        /**
         * Perception basis for addressing an actor: same campaign, a shared non-null current
         * location and no concealed authored origin. An origin that does not resolve in the
         * pinned content is treated as concealed, because disclosure cannot be established.
         * Actors without an authored origin are perceived through co-presence alone.
         */
        void perceivable(UUID actor) {
            String current = here();
            var rows = jdbc.query("select location_id, definition_id from actor where campaign_id = ? and id = ?",
                    (row, index) -> new String[] {row.getString(1), row.getString(2)}, campaign, actor);
            if (rows.isEmpty() || current == null || !current.equals(rows.getFirst()[0])) {
                throw fail(OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE);
            }
            String origin = rows.getFirst()[1];
            if (origin != null && !Boolean.TRUE.equals(catalog().actorDefinitions().get(origin))) {
                throw fail(OperationAdmissionFailure.Kind.ACTOR_UNAVAILABLE);
            }
        }

        /**
         * Ability rules in a fixed order: ownership, prerequisites, target policy, perceived
         * targets, affordable costs and finally an executable effect program. The first failure
         * wins deterministically.
         *
         * @return explicit targets that become commit participants
         */
        List<UUID> ability(TypedCommand.UseAbility use) {
            var ability = catalog().abilities().get(use.abilityId());
            if (ability == null || !learned(use.abilityId())) {
                throw fail(OperationAdmissionFailure.Kind.ABILITY_UNAVAILABLE);
            }
            for (String required : ability.abilities()) {
                if (!learned(required)) {
                    throw fail(OperationAdmissionFailure.Kind.PREREQUISITE_UNMET);
                }
            }
            for (var need : ability.skills()) {
                // CASE keeps the cast behind the type test; a missing row or skill is rank zero.
                if (!Boolean.TRUE.equals(jdbc.query("select case when jsonb_typeof(skills -> ?) = 'number' "
                                + "then (skills ->> ?)::numeric >= ? else false end from character_state "
                                + "where campaign_id = ? and actor_id = ?", (row, index) -> row.getBoolean(1),
                        need.skill(), need.skill(), need.rank(), campaign, player).stream().findFirst().orElse(false))) {
                    throw fail(OperationAdmissionFailure.Kind.PREREQUISITE_UNMET);
                }
            }
            int count = use.targetActorIds().size();
            boolean legal = switch (ability.targetPolicy()) {
                case "self" -> count == 0;
                case "single" -> count == 1;
                case "multiple" -> count >= 1 && count <= ability.maxTargets();
                // Area targeting needs encounter zones (P11); unknown future policies fail closed.
                default -> false;
            };
            if (!legal) {
                throw fail(OperationAdmissionFailure.Kind.TARGET_ILLEGAL);
            }
            for (UUID target : use.targetActorIds()) {
                perceivable(target);
            }
            // Sorted keys give a deterministic first failure when several costs are unaffordable.
            for (var cost : new TreeMap<>(ability.costs()).entrySet()) {
                if (cost.getValue() == 0) {
                    continue;
                }
                // Resource keys are unqualified: a cost declared by another pack's vocabulary may
                // share a key with the player's resource while meaning something else, so the
                // player cannot pay it. Cost-free dependency abilities remain usable. A player
                // without a resolvable creation origin has no provable vocabulary and cannot pay.
                if (!ability.pack().equals(vocabulary())) {
                    throw fail(OperationAdmissionFailure.Kind.RESOURCE_INSUFFICIENT);
                }
                List<Long> headroom = jdbc.query("select current_value - minimum_value from actor_resource "
                                + "where campaign_id = ? and actor_id = ? and resource_key = ?",
                        (row, index) -> row.getLong(1), campaign, player, cost.getKey());
                if (headroom.isEmpty() || headroom.getFirst() < cost.getValue()) {
                    throw fail(OperationAdmissionFailure.Kind.RESOURCE_INSUFFICIENT);
                }
            }
            // Effects the resolver cannot execute are refused now, before a journal row, permit or
            // draw exists. Attribute/resource atoms and resource changes use unqualified keys, so
            // reading them is meaningful only in the player's own vocabulary. Writing a foreign
            // vocabulary key is allowed: the resolver records that actor as explicitly unaffected.
            var effects = ability.effects();
            if (!EffectProgram.supported(effects)
                    || EffectProgram.maxDraws(effects) > org.rpgengine.rules.DeterministicDraw.MAX_DRAWS
                    || (EffectProgram.readsActorValues(effects) && !ability.pack().equals(vocabulary()))) {
                throw fail(OperationAdmissionFailure.Kind.EFFECT_UNSUPPORTED);
            }
            return use.targetActorIds();
        }

        /**
         * Declaring pack of the player's creation profile, which supplied its resource and
         * attribute keys; null for a player without a resolvable creation origin.
         */
        private String vocabulary() {
            String origin = origin();
            return origin == null ? null : catalog().resourceVocabularies().get(origin);
        }

        /** Whether the acting player currently holds a learned row for the canonical ability ID. */
        private boolean learned(String ability) {
            return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from learned_ability "
                    + "where campaign_id = ? and actor_id = ? and definition_id = ?)", Boolean.class,
                    campaign, player, ability));
        }
    }
}
