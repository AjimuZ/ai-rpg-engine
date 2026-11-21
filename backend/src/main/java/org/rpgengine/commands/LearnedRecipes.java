package org.rpgengine.commands;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.rpgengine.combat.CombatAbility;
import org.rpgengine.combat.CombatEffect;
import org.rpgengine.creative.AbilityDraft;
import org.rpgengine.creative.CreativeProfile;
import org.rpgengine.creative.DraftPreview;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Learned creative recipes (P15.4-B, docs/10 section 9, ADR 0053): a draft the player saved with the explicit
 * {@code LEARN_DRAFT} command, stored as consecutive immutable versions in {@code learned_recipe} (V35).
 *
 * <p>Contract: learning previews the draft again under the campaign lock against the creative profile of the player's own
 * vocabulary pack, which is the pack of every fight the player can enter. It spends nothing, so Focus never blocks it; the
 * profile's prerequisites do. A stored row keeps the draft as written and the costs the engine derived, with the profile, the
 * pinned content revision and the announcing event as provenance. An encounter entry previews the latest version of each
 * recipe again against the fight pack's profile and freezes every one that still fits as a frozen ability under
 * {@link #abilityId}; a recipe that no longer fits, or whose prerequisites are missing, is simply not frozen. In a fight a
 * frozen recipe is an ordinary frozen ability: {@code COMBAT_USE_ABILITY} uses it and its own cooldown applies.
 *
 * <p>Bounds: at most {@link #MAX_RECIPES} recipe IDs per player and {@link #MAX_VERSIONS} versions of one, also enforced by
 * a V35 insert trigger. A recipe ID can never equal a canonical definition ID, because frozen recipe abilities start with
 * {@link #ABILITY_PREFIX}.
 */
final class LearnedRecipes {
    /** Most distinct recipes one player holds; each one is a frozen ability with its own cooldown in every fight. */
    static final int MAX_RECIPES = 8;
    /** Most versions of one recipe. */
    static final int MAX_VERSIONS = 99;
    /** Prefix of a frozen recipe's ability ID; {@code #} starts no canonical definition ID. */
    static final String ABILITY_PREFIX = "#recipe:";
    /** A recipe ID: lowercase, at most 32 characters, as V35 checks. */
    static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Static helper only. */
    private LearnedRecipes() {
    }

    /**
     * The latest stored version of one recipe.
     *
     * @param recipeId the player's recipe ID
     * @param version its latest version, 1..{@link #MAX_VERSIONS}
     * @param draft the draft that version saved
     */
    record Stored(String recipeId, int version, AbilityDraft draft) {
        /** Require the identity and the draft. */
        Stored {
            Objects.requireNonNull(recipeId, "recipeId");
            Objects.requireNonNull(draft, "draft");
        }
    }

    /**
     * What one {@code LEARN_DRAFT} commit writes: one new version, with the costs the engine derived.
     *
     * @param actorId the learning player
     * @param recipeId the recipe ID
     * @param version the new version, one above the latest stored one
     * @param draft the draft as written
     * @param profilePack pack that declares the profile it was previewed against
     * @param profileId canonical ID of that profile's rule
     * @param complexity the derived complexity
     * @param actionPoints the derived action points
     * @param focus the derived Focus cost
     * @param cooldownRounds the derived cooldown
     * @param eventIndex index of the plan's announcing {@code RECIPE_LEARNED} event
     */
    record Learning(UUID actorId, String recipeId, int version, AbilityDraft draft, String profilePack, String profileId,
            int complexity, int actionPoints, int focus, int cooldownRounds, int eventIndex) {
        /** Check what V35 checks again, so a resolver defect fails before the commit writes anything. */
        Learning {
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(draft, "draft");
            Objects.requireNonNull(profilePack, "profilePack");
            Objects.requireNonNull(profileId, "profileId");
            if (recipeId == null || !ID.matcher(recipeId).matches() || version < 1 || version > MAX_VERSIONS
                    || eventIndex < 0) {
                throw new IllegalArgumentException("a learned recipe has a valid ID, version and event");
            }
        }
    }

    /** @return the frozen ability ID of a recipe */
    static String abilityId(String recipeId) {
        return ABILITY_PREFIX + recipeId;
    }

    /**
     * Preview a draft for learning: the rule gate's and the resolver's check, at the same locked state.
     *
     * @param profile the creative profile of the player's vocabulary pack, or null when it declares none
     * @param draft the draft to learn
     * @param effects the content's effect definitions by canonical ID
     * @param learned canonical abilities the player has learned
     * @return the ready preview, whose candidate holds the derived costs
     * @throws OperationAdmissionFailure {@code ABILITY_UNAVAILABLE} without a profile, {@code EFFECT_UNSUPPORTED} for a
     *         draft outside its bounds, {@code PREREQUISITE_UNMET} for a missing profile prerequisite
     */
    static DraftPreview.Ready learnable(CreativeProfile profile, AbilityDraft draft, Map<String, CombatEffect> effects,
            Set<String> learned) {
        if (profile == null) {
            throw new OperationAdmissionFailure(OperationAdmissionFailure.Kind.ABILITY_UNAVAILABLE);
        }
        // Learning spends nothing, so the most Focus any candidate can cost stands in for the player's pool.
        var result = DraftPreview.preview(profile, draft, effects, new DraftPreview.Caster(CombatAbility.FOCUS_MAX, learned));
        if (!(result instanceof DraftPreview.Ready ready)) {
            throw new OperationAdmissionFailure(OperationAdmissionFailure.Kind.EFFECT_UNSUPPORTED);
        }
        if (!ready.unblocked()) {
            throw new OperationAdmissionFailure(OperationAdmissionFailure.Kind.PREREQUISITE_UNMET);
        }
        return ready;
    }

    /**
     * The version a learning of {@code recipeId} adds.
     *
     * @param latest the player's latest stored versions
     * @param recipeId the recipe ID to learn
     * @return one above its latest version, or 1 for a new recipe
     * @throws OperationAdmissionFailure {@code ABILITY_UNAVAILABLE} when a new recipe would exceed {@link #MAX_RECIPES} or
     *         the recipe already has {@link #MAX_VERSIONS} versions
     */
    static int nextVersion(List<Stored> latest, String recipeId) {
        var existing = latest.stream().filter(stored -> stored.recipeId().equals(recipeId)).findFirst();
        if (existing.isPresent() ? existing.get().version() >= MAX_VERSIONS : latest.size() >= MAX_RECIPES) {
            throw new OperationAdmissionFailure(OperationAdmissionFailure.Kind.ABILITY_UNAVAILABLE);
        }
        return existing.map(stored -> stored.version() + 1).orElse(1);
    }

    /**
     * The recipe abilities an encounter entry freezes, sorted by recipe ID: each latest version previewed again against the
     * fight pack's profile, kept only when it fits and nothing but Focus could block it.
     *
     * @param profile the fight pack's creative profile, or null when it declares none
     * @param recipes the player's latest versions
     * @param effects the content's effect definitions by canonical ID
     * @param learned canonical abilities the player has learned
     * @return the frozen abilities under {@link #abilityId}; empty without a profile or recipes
     */
    static List<CombatAbility> frozen(CreativeProfile profile, List<Stored> recipes, Map<String, CombatEffect> effects,
            Set<String> learned) {
        if (profile == null) {
            return List.of();
        }
        var abilities = new ArrayList<CombatAbility>();
        for (Stored recipe : recipes.stream().sorted(Comparator.comparing(Stored::recipeId)).toList()) {
            var result = DraftPreview.preview(profile, recipe.draft(), effects,
                    new DraftPreview.Caster(CombatAbility.FOCUS_MAX, learned));
            if (result instanceof DraftPreview.Ready ready && ready.unblocked()) {
                var candidate = ready.candidate();
                abilities.add(new CombatAbility(abilityId(recipe.recipeId()), candidate.cost(), candidate.limit(),
                        candidate.cooldownRounds(), candidate.focus(), candidate.targets(), candidate.maxTargets(),
                        candidate.range(), candidate.operations()));
            }
        }
        return List.copyOf(abilities);
    }

    /**
     * Read the latest version of each recipe of one player, under the caller's lock.
     *
     * @return the latest versions, sorted by recipe ID
     */
    static List<Stored> latest(JdbcTemplate jdbc, UUID campaign, UUID actor) {
        return jdbc.query("select distinct on (recipe_id) recipe_id, version, draft::text from learned_recipe "
                + "where campaign_id = ? and actor_id = ? order by recipe_id, version desc",
                (row, index) -> new Stored(row.getString(1), row.getInt(2), read(row.getString(3))), campaign, actor);
    }

    /**
     * Write one learned version, citing its announcing event. V35 refuses a skipped version, a ninth recipe, an event of
     * another revision and any later change.
     *
     * @param jdbc JDBC access joined to the commit's transaction
     * @param campaign the campaign
     * @param contentRevision the campaign's pinned content revision
     * @param revision the committed state revision
     * @param eventSequence sequence of the announcing event
     * @param worldTime the committed world time
     * @param learning what to write
     */
    static void insertWithin(JdbcTemplate jdbc, UUID campaign, UUID contentRevision, long revision, long eventSequence,
            long worldTime, Learning learning) {
        jdbc.update("insert into learned_recipe (campaign_id, actor_id, recipe_id, version, label, draft, profile_pack_id, "
                + "profile_id, content_revision_id, complexity, action_points, focus, cooldown_rounds, learned_revision, "
                + "learned_event_sequence, learned_world_time) values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                campaign, learning.actorId(), learning.recipeId(), learning.version(), learning.draft().label(),
                JSON.writeValueAsString(wire(learning.draft())), learning.profilePack(), learning.profileId(),
                contentRevision, learning.complexity(), learning.actionPoints(), learning.focus(), learning.cooldownRounds(),
                revision, eventSequence, worldTime);
    }

    /**
     * The draft's wire form, the {@code draft} object of {@code COMBAT_USE_DRAFT} and {@code LEARN_DRAFT}: an absent attribute
     * or effect is omitted, never null.
     */
    static Map<String, Object> wire(AbilityDraft draft) {
        var steps = draft.steps().stream().map(step -> {
            var form = new LinkedHashMap<String, Object>();
            form.put("kind", step.kind().wire());
            form.put("diceCount", step.diceCount());
            form.put("diceSides", step.diceSides());
            form.put("flat", step.flat());
            form.put("penetration", step.penetration());
            form.put("toSelf", step.toSelf());
            if (step.attribute() != null) {
                form.put("attribute", step.attribute());
            }
            if (step.effectId() != null) {
                form.put("effectId", step.effectId());
            }
            return (Object) java.util.Collections.unmodifiableMap(form);
        }).toList();
        return Map.of("label", draft.label(), "targets", draft.targets().wire(), "maxTargets", draft.maxTargets(),
                "range", draft.range(), "steps", steps);
    }

    /**
     * Read a stored wire form back.
     *
     * @throws IllegalArgumentException or {@link ClassCastException} for a malformed stored draft
     */
    static AbilityDraft read(String stored) {
        Map<?, ?> form = JSON.readValue(stored, Map.class);
        var steps = new ArrayList<AbilityDraft.Step>();
        for (Object value : (List<?>) form.get("steps")) {
            var step = (Map<?, ?>) value;
            steps.add(new AbilityDraft.Step(CombatAbility.Kind.valueOf(((String) step.get("kind")).toUpperCase(Locale.ROOT)),
                    ((Number) step.get("diceCount")).intValue(), ((Number) step.get("diceSides")).intValue(),
                    (String) step.get("attribute"), ((Number) step.get("flat")).intValue(),
                    ((Number) step.get("penetration")).intValue(), (String) step.get("effectId"),
                    Boolean.TRUE.equals(step.get("toSelf"))));
        }
        return new AbilityDraft((String) form.get("label"),
                CombatAbility.Targets.valueOf(((String) form.get("targets")).toUpperCase(Locale.ROOT)),
                ((Number) form.get("maxTargets")).intValue(), ((Number) form.get("range")).intValue(), steps);
    }
}
