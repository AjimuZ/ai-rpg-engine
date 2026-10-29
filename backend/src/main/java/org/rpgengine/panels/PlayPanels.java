package org.rpgengine.panels;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.rpgengine.characters.CharacterRuleViolation;
import org.rpgengine.characters.CharacterService;
import org.rpgengine.knowledge.KnowledgeView;
import org.rpgengine.narration.EventStatements;
import org.rpgengine.presentation.SupportedLocale;
import org.rpgengine.quests.QuestRules;
import org.rpgengine.quests.QuestStatus;
import org.rpgengine.rules.CharacterSheet;
import org.rpgengine.rules.WorldClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The player panels and journal of one campaign (P10.5): clock, character sheet, conditions, resources,
 * abilities, inventory with worn equipment, discovered quests, discoveries and promises, and the history
 * of public events, all at one committed state revision.
 *
 * <p><b>Consistency.</b> Every canonical row is read in one read-only repeatable-read snapshot after the
 * owner, adventure and campaign scope is authorized, so a panel can never mix two revisions. The pinned
 * pack (names, secret flags, rarity, derived-statistic formulas) is loaded before the snapshot from the
 * immutable sealed revision, which is why a campaign's content revision is read once beforehand: it is
 * frozen for the life of the campaign.
 *
 * <p><b>Reads are free.</b> Nothing here writes a row, draws a random number, starts a job or advances
 * the clock; repeating a read is always safe and returns identical bytes until a command commits.
 *
 * <p><b>What a panel may show.</b> Values come from stored rows and the recomputed sheet; text comes only
 * from public pack text. Quests are listed once discovered; a hidden background quest stays out until its
 * ending is committed (the same rule that keeps its events internal). Events come from the {@code PUBLIC}
 * log only and are worded by {@link EventStatements} in the campaign's narrative language, so internal
 * events, hidden definitions and raw payloads never reach a panel. When the pinned content cannot be
 * loaded the panels still show numbers and identifiers but no names or quests, and report
 * {@link Panels#contentAvailable()} as false.
 *
 * <p><b>Bounds.</b> Each list holds at most {@value #MAX_ROWS} rows (more fails the read instead of
 * truncating), the journal returns at most {@value #MAX_EVENT_PAGE} events per page and examines at most
 * {@value #MAX_EVENT_SCAN} stored events per call, and discoveries and promises are capped at
 * {@value #MAX_KNOWLEDGE} newest entries with the full count reported.
 */
@Service
public class PlayPanels {
    /** Rows per canonical list; one more row fails the read. */
    static final int MAX_ROWS = 1000;
    /** Most journal events one page returns. */
    public static final int MAX_EVENT_PAGE = 100;
    /** Page size when the caller names none. */
    public static final int DEFAULT_EVENT_PAGE = 25;
    /** Most stored events one call examines while looking for statable ones. */
    static final int MAX_EVENT_SCAN = 1000;
    /** Most discoveries and most promises listed. */
    static final int MAX_KNOWLEDGE = 100;
    private static final TypeReference<TreeMap<String, Integer>> NUMBERS = new TypeReference<>() { };
    private static final Set<QuestStatus> ENDED = Set.of(QuestStatus.COMPLETED, QuestStatus.FAILED);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate snapshot;
    private final PanelContents contents;
    private final CharacterService characters;
    private final KnowledgeView knowledge;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * @param jdbc storage
     * @param manager transaction manager
     * @param contents pinned content cache
     * @param characters sheet and equipment reader
     * @param knowledge typed audience knowledge reader
     */
    PlayPanels(JdbcTemplate jdbc, PlatformTransactionManager manager, PanelContents contents,
            CharacterService characters, KnowledgeView knowledge) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.contents = Objects.requireNonNull(contents, "contents");
        this.characters = Objects.requireNonNull(characters, "characters");
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
        snapshot = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setTimeout(30);
    }

    /**
     * Read the panels of one owned campaign.
     *
     * @param owner locally admitted owner
     * @param adventure adventure that must own the campaign
     * @param campaign campaign identity
     * @param before exclusive journal cursor: only events with a lower sequence are listed; null starts at the newest
     * @param eventLimit journal page size, 1 to {@value #MAX_EVENT_PAGE}
     * @return the panels at one committed revision
     * @throws ScopeUnavailable for a missing, foreign or mismatched scope
     * @throws IllegalArgumentException for an invalid page size or cursor
     * @throws IllegalStateException inside an ambient transaction, or when a list exceeds {@value #MAX_ROWS} rows
     * @throws org.springframework.dao.DataAccessException for storage failures
     */
    public Panels read(UUID owner, UUID adventure, UUID campaign, Long before, int eventLimit) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(campaign, "campaign");
        if (eventLimit < 1 || eventLimit > MAX_EVENT_PAGE || (before != null && before < 1)) {
            throw new IllegalArgumentException("invalid journal page");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("panels require an independent snapshot");
        }
        var pinned = jdbc.query("select c.content_revision_id from campaign c join adventure_workspace w on w.id = "
                + "c.adventure_id where c.id = ? and c.adventure_id = ? and w.owner_id = ?",
                (row, index) -> row.getObject(1, UUID.class), campaign, adventure, owner);
        if (pinned.isEmpty()) {
            throw new ScopeUnavailable();
        }
        PanelContent content = contents.load(adventure, pinned.getFirst()).orElse(null);
        return Objects.requireNonNull(snapshot.execute(status -> assemble(owner, adventure, campaign, content, before,
                eventLimit)));
    }

    /** Authorize the scope again inside the snapshot and read every part of the panels. */
    private Panels assemble(UUID owner, UUID adventure, UUID campaign, PanelContent content, Long before, int limit) {
        jdbc.execute("set local statement_timeout = '10s'");
        var heads = jdbc.query("select c.content_revision_id, c.state_revision, c.world_time_seconds, "
                + "coalesce(p.narrative_locale, w.source_locale) from campaign c join adventure_workspace w on w.id = "
                + "c.adventure_id left join presentation_campaign_settings p on p.campaign_id = c.id where c.id = ? and "
                + "c.adventure_id = ? and w.owner_id = ?", (row, index) -> new Object[] {row.getObject(1, UUID.class),
                        row.getLong(2), row.getLong(3), row.getString(4)}, campaign, adventure, owner);
        if (heads.isEmpty()) {
            throw new ScopeUnavailable();
        }
        Object[] head = heads.getFirst();
        long revision = (long) head[1];
        SupportedLocale locale = SupportedLocale.fromTag((String) head[3]);
        Scope scope = new Scope(adventure, campaign, (UUID) head[0], revision);
        Clock clock = Clock.of(WorldClock.read((long) head[2]));
        var players = jdbc.query("select a.id, a.display_name, a.location_id, s.attributes::text, s.skills::text, "
                + "s.role from campaign_creation cc join actor a on a.campaign_id = cc.campaign_id and a.id = "
                + "cc.actor_id join character_state s on s.campaign_id = a.campaign_id and s.actor_id = a.id "
                + "where cc.adventure_id = ? and cc.campaign_id = ?", (row, index) -> new Object[] {
                        row.getObject(1, UUID.class), row.getString(2), row.getString(3), row.getString(4),
                        row.getString(5), row.getString(6)}, adventure, campaign);
        Journal journal = journal(campaign, locale, revision, before, limit);
        if (players.isEmpty()) {
            // A legacy campaign has no creation-bound player: only the clock and public history exist.
            return new Panels(scope, locale.tag(), content != null, clock, null, List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), Knowledge.EMPTY, journal);
        }
        Object[] row = players.getFirst();
        UUID player = (UUID) row[0];
        String here = (String) row[2];
        Map<String, Integer> baseAttributes = numbers((String) row[3]);
        Map<String, Integer> baseSkills = numbers((String) row[4]);
        CharacterSheet sheet = null;
        if (content != null) {
            try {
                sheet = characters.sheet(campaign, player, content.characters());
            } catch (CharacterRuleViolation unavailable) {
                // An actor whose profile the pinned rules do not know keeps its stored base values.
                sheet = null;
            }
        }
        var attributes = new ArrayList<Stat>();
        for (var entry : baseAttributes.entrySet()) {
            long effective = sheet == null ? entry.getValue() : sheet.attributes().getOrDefault(entry.getKey(),
                    (long) entry.getValue());
            attributes.add(new Stat(entry.getKey(), name(content, locale, "attributes", entry.getKey()), entry.getValue(),
                    effective));
        }
        var skills = new ArrayList<Skill>();
        for (var entry : baseSkills.entrySet()) {
            skills.add(new Skill(entry.getKey(), definitionName(content, locale, entry.getKey()), entry.getValue()));
        }
        var derived = new ArrayList<Derived>();
        if (sheet != null) {
            sheet.derived().forEach((key, value) -> derived.add(new Derived(key, value)));
        }
        Place place = here == null ? null : new Place(here, definitionName(content, locale, here));
        var character = new Character(player, (String) row[1], (String) row[5], place, attributes, skills, derived);
        var resources = bounded(jdbc.query("select resource_key, minimum_value, current_value, maximum_value from "
                + "actor_resource where campaign_id = ? and actor_id = ? order by resource_key limit ?",
                (rs, n) -> new Resource(rs.getString(1), name(content, locale, "resources", rs.getString(1)),
                        rs.getLong(2), rs.getLong(3), rs.getLong(4)), campaign, player, MAX_ROWS + 1));
        var conditions = bounded(jdbc.query("select condition_id, stacks, remaining_rounds from actor_condition where "
                + "campaign_id = ? and actor_id = ? order by condition_id limit ?", (rs, n) -> new Condition(
                        rs.getString(1), definitionName(content, locale, rs.getString(1)), rs.getInt(2), rs.getInt(3)),
                campaign, player, MAX_ROWS + 1));
        var abilities = bounded(jdbc.query("select definition_id, rank from learned_ability where campaign_id = ? and "
                + "actor_id = ? order by definition_id limit ?", (rs, n) -> new Ability(rs.getString(1),
                        definitionName(content, locale, rs.getString(1)), rs.getInt(2)), campaign, player, MAX_ROWS + 1));
        var worn = characters.equipment(campaign, player);
        Map<UUID, CharacterService.Equipped> wornById = new HashMap<>();
        worn.forEach(item -> wornById.put(item.itemInstanceId(), item));
        var inventory = bounded(jdbc.query("select id, definition_id, quantity from item_instance where campaign_id = ? "
                + "and actor_id = ? order by definition_id, id limit ?", (rs, n) -> {
                    String definition = rs.getString(2);
                    UUID id = rs.getObject(1, UUID.class);
                    String rarity = content == null || content.characters().items().get(definition) == null ? null
                            : content.characters().items().get(definition).rarity();
                    return new Item(id, definition, definitionName(content, locale, definition), rs.getInt(3), rarity,
                            rarity == null ? null : name(content, locale, "tiers", rarity),
                            wornById.containsKey(id) ? wornById.get(id).slot() : null);
                }, campaign, player, MAX_ROWS + 1));
        var equipment = new ArrayList<Worn>();
        for (var item : worn) {
            equipment.add(new Worn(item.itemInstanceId(), item.definitionId(),
                    definitionName(content, locale, item.definitionId()), item.slot(), item.hands()));
        }
        return new Panels(scope, locale.tag(), content != null, clock, character, resources, conditions, abilities,
                inventory, equipment, quests(campaign, content, locale, revision), knowledge(campaign, player, revision),
                journal);
    }

    /**
     * Discovered quests with their objectives and outcome. A stored quest the pinned rules no longer know, an
     * undiscovered quest and a hidden background quest that has not ended are left out; without loadable
     * content no quest can be classified, so none is listed.
     */
    private List<Quest> quests(UUID campaign, PanelContent content, SupportedLocale locale, long revision) {
        if (content == null) {
            return List.of();
        }
        var stored = new HashMap<String, Object[]>();
        bounded(jdbc.query("select quest_id, status, outcome_id, updated_revision from quest_state where "
                + "campaign_id = ? and updated_revision <= ? order by quest_id limit ?",
                (rs, n) -> new Object[] {rs.getString(1), QuestStatus.parse(rs.getString(2)).orElseThrow(),
                        rs.getString(3), rs.getLong(4)}, campaign, revision, MAX_ROWS + 1))
                .forEach(row -> stored.put((String) row[0], row));
        var satisfied = new HashSet<String>();
        jdbc.query("select quest_id, objective_id from quest_objective where campaign_id = ? and satisfied_revision <= ?",
                rs -> {
                    satisfied.add(rs.getString(1) + "\u0000" + rs.getString(2));
                }, campaign, revision);
        var result = new ArrayList<Quest>();
        // Rules are visited in identity order. A quest without a row has not been evaluated yet and stands at its
        // initial status, so one that starts available is discovered from the first revision on.
        for (QuestRules.Quest rule : content.quests().quests().values()) {
            String id = rule.id();
            Object[] state = stored.getOrDefault(id, new Object[] {id, rule.initial(), null, 0L});
            QuestStatus status = (QuestStatus) state[1];
            if (status == QuestStatus.UNDISCOVERED || (rule.secret() && !ENDED.contains(status))) {
                continue;
            }
            var objectives = new ArrayList<Objective>();
            if (status != QuestStatus.AVAILABLE) {
                for (var objective : rule.objectives()) {
                    objectives.add(new Objective(objective.id(),
                            content.text(locale, id, "/objectives/" + objective.id() + "/description").orElse(null),
                            objective.optional(), satisfied.contains(id + "\u0000" + objective.id())));
                }
            }
            Outcome outcome = null;
            if (state[2] != null) {
                String outcomeId = (String) state[2];
                var rules = rule.outcomes().stream().filter(candidate -> candidate.id().equals(outcomeId)).findFirst();
                if (rules.isPresent()) {
                    var rewards = new ArrayList<Reward>();
                    rules.get().rewards().forEach(reward -> rewards.add(new Reward(reward.item(),
                            definitionName(content, locale, reward.item()), reward.quantity())));
                    outcome = new Outcome(outcomeId, rules.get().kind().wire(),
                            content.text(locale, id, "/outcomes/" + outcomeId + "/description").orElse(null), rewards);
                }
            }
            result.add(new Quest(id, content.name(locale, id).orElse(null),
                    content.text(locale, id, "/summary").orElse(null), status.wire(), (long) state[3], objectives, outcome));
        }
        return List.copyOf(result);
    }

    /** What the player knows and has promised, from the typed records only, newest first and capped. */
    private Knowledge knowledge(UUID campaign, UUID player, long revision) {
        var audience = knowledge.forActorWithin(campaign, player);
        var found = new ArrayList<Discovery>();
        for (var belief : audience.beliefs()) {
            if (belief.claim().statement() != null) {
                found.add(new Discovery(belief.claim().claimId(), belief.stance().name(), belief.acquisition().name(),
                        belief.claim().statement(), belief.claim().statementLocale(), belief.learnedRevision(),
                        belief.learnedWorldTime()));
            }
        }
        for (var common : audience.common()) {
            if (common.claim().statement() != null) {
                found.add(new Discovery(common.claim().claimId(), "COMMON", null, common.claim().statement(),
                        common.claim().statementLocale(), common.establishedRevision(), 0));
            }
        }
        found.sort(Comparator.comparingLong(Discovery::learnedRevision).reversed()
                .thenComparing(Discovery::claimId));
        var parties = new LinkedHashSet<UUID>();
        audience.commitments().forEach(commitment -> {
            parties.add(commitment.debtorId());
            parties.add(commitment.creditorId());
        });
        var names = actorNames(campaign, parties);
        var promises = new ArrayList<Promise>();
        for (var commitment : audience.commitments()) {
            String direction = commitment.debtorId().equals(player) ? "YOU_OWE"
                    : commitment.creditorId().equals(player) ? "OWED_TO_YOU" : "BETWEEN_OTHERS";
            promises.add(new Promise(commitment.commitmentId(), commitment.kind().name(), direction,
                    names.get(commitment.debtorId()), names.get(commitment.creditorId()), commitment.terms(),
                    commitment.termsLocale(), commitment.dueWorldTime(), commitment.overdue(), commitment.madeRevision()));
        }
        promises.sort(Comparator.comparingLong(Promise::madeRevision).reversed().thenComparing(Promise::commitmentId));
        return new Knowledge(found.size(), List.copyOf(found.subList(0, Math.min(found.size(), MAX_KNOWLEDGE))),
                promises.size(), List.copyOf(promises.subList(0, Math.min(promises.size(), MAX_KNOWLEDGE))));
    }

    /**
     * One page of the public event history, newest first. Events whose type or payload
     * {@link EventStatements} cannot word faithfully are skipped (a journal line without text would only be
     * noise); the scan is bounded, and {@code nextBefore} names the cursor of the next page or is null at the
     * oldest event.
     */
    private Journal journal(UUID campaign, SupportedLocale locale, long revision, Long before, int limit) {
        var entries = new ArrayList<Entry>();
        long cursor = before == null ? Long.MAX_VALUE : before;
        long examined = 0;
        boolean exhausted = false;
        while (entries.size() < limit && examined < MAX_EVENT_SCAN && !exhausted) {
            int batch = (int) Math.min(100, MAX_EVENT_SCAN - examined);
            var rows = jdbc.query("select sequence, event_type, event_version, state_revision, world_time_seconds, "
                    + "payload::text from campaign_event where campaign_id = ? and visibility = 'PUBLIC' and "
                    + "state_revision <= ? and sequence < ? order by sequence desc limit ?", (rs, n) -> new Object[] {
                            rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getLong(4), rs.getLong(5),
                            json.readTree(rs.getString(6))}, campaign, revision, cursor, batch);
            exhausted = rows.size() < batch;
            var referenced = new LinkedHashSet<UUID>();
            rows.forEach(row -> referenced.addAll(EventStatements.referencedActors((tools.jackson.databind.JsonNode) row[5])));
            var names = actorNames(campaign, referenced);
            for (Object[] row : rows) {
                examined++;
                cursor = (long) row[0];
                Optional<String> text = EventStatements.state(locale, (String) row[1], (int) row[2],
                        (tools.jackson.databind.JsonNode) row[5], names);
                if (text.isPresent()) {
                    entries.add(new Entry((long) row[0], (String) row[1], (long) row[3],
                            Clock.of(WorldClock.read((long) row[4])), text.get()));
                    if (entries.size() == limit) {
                        break;
                    }
                }
            }
        }
        boolean more = entries.size() == limit || !exhausted
                ? Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from campaign_event where campaign_id = ? "
                        + "and visibility = 'PUBLIC' and state_revision <= ? and sequence < ?)", Boolean.class, campaign,
                        revision, cursor))
                : false;
        return new Journal(List.copyOf(entries), more ? cursor : null);
    }

    /** @return display names of the given actors of the campaign; unknown identities are absent */
    private Map<UUID, String> actorNames(UUID campaign, Set<UUID> actors) {
        var names = new HashMap<UUID, String>();
        if (actors.isEmpty()) {
            return names;
        }
        jdbc.query("select id, display_name from actor where campaign_id = ? and id = any(?)", rs -> {
            names.put(rs.getObject(1, UUID.class), rs.getString(2));
        }, campaign, actors.toArray(new UUID[0]));
        return names;
    }

    /** @return the public name of a definition, or null without content or text */
    private static Text definitionName(PanelContent content, SupportedLocale locale, String definition) {
        return content == null ? null : content.name(locale, definition).orElse(null);
    }

    /** @return the public name of a rule entry, or null without content or text */
    private static Text name(PanelContent content, SupportedLocale locale, String group, String id) {
        return content == null ? null : content.nested(locale, group, id).orElse(null);
    }

    /** Decode a stored integer allocation; malformed stored content fails closed. */
    private Map<String, Integer> numbers(String value) {
        return json.readValue(value, NUMBERS);
    }

    /** Fail rather than present a silently incomplete list. */
    private static <T> List<T> bounded(List<T> rows) {
        if (rows.size() > MAX_ROWS) {
            throw new IllegalStateException("panel limit exceeded");
        }
        return List.copyOf(rows);
    }

    /** Public identity and revision tokens; a client discards replies for a scope it no longer shows. */
    public record Scope(UUID adventureId, UUID campaignId, UUID contentRevisionId, long stateRevision) { }

    /**
     * A resolved public text with its actual language.
     *
     * @param text the text
     * @param locale language of the text
     * @param fallback true when the requested language had no overlay and the source language is shown
     */
    public record Text(String text, String locale, boolean fallback) { }

    /**
     * The in-world clock position (docs/10 section 7): total seconds and the calendar reading of
     * {@link WorldClock}. It moves only when a command commits.
     */
    public record Clock(long seconds, long day, int hour, int minute, int second) {
        private static Clock of(WorldClock.Reading reading) {
            return new Clock(reading.seconds(), reading.day(), reading.hour(), reading.minute(), reading.second());
        }
    }

    /** A location by canonical identity with its public name when the pack has one. */
    public record Place(String id, Text name) { }

    /** An attribute with its stored base value and the effective value after active conditions. */
    public record Stat(String key, Text name, long base, long effective) { }

    /** A trained skill rank. */
    public record Skill(String key, Text name, int rank) { }

    /** A statistic derived from attributes and skills by the pinned rules; recomputed, never stored. */
    public record Derived(String key, long value) { }

    /** The creation-bound player character. */
    public record Character(UUID actorId, String name, String role, Place location, List<Stat> attributes,
            List<Skill> skills, List<Derived> derived) { }

    /** Exact owned resource values and inclusive bounds. */
    public record Resource(String key, Text name, long minimum, long current, long maximum) { }

    /** An active condition with its stacks and the rounds it still lasts. */
    public record Condition(String id, Text name, int stacks, int remainingRounds) { }

    /** A learned ability and rank; this does not claim the ability is usable right now. */
    public record Ability(String id, Text name, int rank) { }

    /** An owned item stack; {@code wornIn} is the slot when this instance is equipped, otherwise null. */
    public record Item(UUID itemId, String definitionId, Text name, int quantity, String rarity, Text rarityName,
            String wornIn) { }

    /** One worn item instance with the hands it occupies. */
    public record Worn(UUID itemId, String definitionId, Text name, String slot, int hands) { }

    /** An objective of a started quest with whether the engine has recorded it as satisfied. */
    public record Objective(String id, Text description, boolean optional, boolean satisfied) { }

    /** Item units an ending settled, as the pinned outcome declares them. */
    public record Reward(String itemId, Text name, int quantity) { }

    /** How an ended quest ended: the outcome, its kind (success, failure, alternative) and what it paid. */
    public record Outcome(String id, String kind, Text description, List<Reward> rewards) { }

    /** A discovered quest; objectives are listed once it has been accepted, the outcome once it has ended. */
    public record Quest(String id, Text name, Text summary, String status, long updatedRevision,
            List<Objective> objectives, Outcome outcome) { }

    /** A known statement with the stance the player holds toward it, never whether it is true. */
    public record Discovery(UUID claimId, String stance, String acquisition, String statement, String statementLocale,
            long learnedRevision, long learnedWorldTime) { }

    /** An open promise, obligation or agreement the player is party to or that is common knowledge. */
    public record Promise(UUID commitmentId, String kind, String direction, String debtor, String creditor,
            String terms, String termsLocale, Long dueWorldTime, boolean overdue, long madeRevision) { }

    /** Typed knowledge, newest first; the totals count entries beyond the listed cap too. */
    public record Knowledge(int discoveriesTotal, List<Discovery> discoveries, int promisesTotal, List<Promise> promises) {
        /** No knowledge. */
        static final Knowledge EMPTY = new Knowledge(0, List.of(), 0, List.of());
    }

    /** One worded public event with the in-world time it happened. */
    public record Entry(long sequence, String type, long stateRevision, Clock clock, String statement) { }

    /** One page of the event history, newest first; {@code nextBefore} is the cursor for the next older page. */
    public record Journal(List<Entry> entries, Long nextBefore) { }

    /**
     * Complete detached panels at one committed revision.
     *
     * @param scope identity and revision tokens
     * @param locale narrative language the event statements are worded in
     * @param contentAvailable false when the pinned content could not be loaded (no names, no quests)
     * @param clock current world time
     * @param character the player character, or null for a legacy campaign without one
     */
    public record Panels(Scope scope, String locale, boolean contentAvailable, Clock clock, Character character,
            List<Resource> resources, List<Condition> conditions, List<Ability> abilities,
            List<Item> inventory, List<Worn> equipment, List<Quest> quests, Knowledge knowledge, Journal journal) { }

    /** Uniform missing, foreign or mismatched scope signal; retains no identities or diagnostics. */
    public static final class ScopeUnavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** Construct a fixed failure without scope input. */
        public ScopeUnavailable() {
            super("campaign scope unavailable");
        }
    }
}
