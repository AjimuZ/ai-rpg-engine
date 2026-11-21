package org.rpgengine.assets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.rpgengine.content.CampaignPackSnapshot;
import org.rpgengine.content.Definition;
import org.rpgengine.content.PackRegistry;
import org.rpgengine.presentation.PresentationEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The game flow that asks {@link RuntimeImageJobs} for runtime scene, portrait and item images (P17.6-C, docs/23
 * section 4): it lists the subjects the player can see right now with the state of their art, and turns the player's
 * explicit request for one of them into a job whose appearance text comes only from public pack fields.
 *
 * <p>Always holds:
 * <ul>
 *   <li>a subject is eligible only when the player sees it now: the scene of the player's current location, the
 *       portrait of an actor the player saw at that location and has not since found absent (the rule of the resume
 *       card), and the item of a definition the player carries. The definition must belong to the pinned root pack, and
 *       a location or NPC whose {@code knowledge} is {@code secret} is never eligible, even after a reveal, so its
 *       description never reaches a prompt;</li>
 *   <li>the appearance text is built here, never taken from the client: a location's {@code description}, an NPC's
 *       {@code role} and {@code description}, an item's {@code appearance.summary}. Item descriptions, facts, goals,
 *       names, stats and campaign rows never enter it. Every appearance is revision 1, the one the sealed pack revision
 *       states, so authored pack art for the same subject is reused and never replaced;</li>
 *   <li>an ineligible, unknown, hidden or foreign subject and a foreign scope answer the same {@link Optional#empty()},
 *       so a refusal does not tell a hidden subject from a missing one;</li>
 *   <li>reading the listing never queues, charges or writes anything; only {@link #request} may queue a job.</li>
 * </ul>
 *
 * <p>Job states are kept in memory, at most {@value #MAX_TRACKED} subjects over all campaigns, oldest dropped first: a
 * restart or an eviction forgets a pending or failed state, and the subject then reads as missing again while its job,
 * if any, still runs (asking again coalesces into it).
 */
@Service
public class RuntimeImageRequests {
    /** Most subjects whose job state is remembered. */
    public static final int MAX_TRACKED = 2048;
    /** Most portrait subjects listed: actors seen at the player's location. */
    public static final int MAX_PORTRAITS = 32;
    /** Most item subjects listed: distinct carried definitions. */
    public static final int MAX_ITEMS = 64;
    /** Failure code shown when a result was refused as stale (newer appearance, scene, pack revision or a restore). */
    public static final String STALE = "image-job-stale";
    /** Failure code shown in place of a backend code that is not a plain lowercase word list. */
    public static final String FAILED = "image-job-failed";
    /** Appearance revision of every subject: the one the pinned sealed pack revision states. */
    static final long APPEARANCE_REVISION = 1;
    private static final int CACHE_ENTRIES = 16;
    private static final Pattern CODE = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\s]+");

    private final JdbcTemplate jdbc;
    private final PackRegistry packs;
    private final ManagedAssets assets;
    private final RuntimeImageJobs jobs;
    private final TransactionTemplate snapshot;
    private final TransactionTemplate loads;
    /** Public appearance text per sealed content revision; a revision is immutable, so it never needs invalidation. */
    private final Map<UUID, Appearances> cache = new LinkedHashMap<>(CACHE_ENTRIES, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Appearances> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };
    /** Last job state by campaign, category and subject; guarded by itself. */
    private final Map<String, Tracked> tracked = new LinkedHashMap<>(64, 0.75f, false) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Tracked> eldest) {
            return size() > MAX_TRACKED;
        }
    };

    /**
     * Wire storage, the pinned pack registry, the art registry and the job service; a missing bean fails startup.
     */
    public RuntimeImageRequests(JdbcTemplate jdbc, PlatformTransactionManager manager, PackRegistry packs,
            ManagedAssets assets, RuntimeImageJobs jobs) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.packs = Objects.requireNonNull(packs, "packs");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        snapshot = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setTimeout(30);
        loads = new TransactionTemplate(manager);
        loads.setReadOnly(true);
        loads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        loads.setTimeout(60);
    }

    /** Display state of one subject's art. */
    public enum State {
        /** Nothing is shown and no job is known; the screen shows its neutral fallback and may offer a request. */
        MISSING,
        /** A job for the subject is queued or running. */
        PENDING,
        /** Art is in force for the subject (accepted, generated or authored by the pack). */
        READY,
        /** The last known job ended without an image; see the code. Asking again is allowed. */
        FAILED;

        /** @return the lowercase wire name */
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * One subject the player can see now.
     *
     * @param category scene, portrait or item
     * @param subject stable public reference the artwork listing uses: location ID, actor ID or item definition ID
     * @param label public name in the pack's source language (an actor's display name for a portrait)
     * @param state display state of its art
     * @param errorCode fixed lowercase code when {@link State#FAILED}, else null
     */
    public record Subject(AssetCategory category, String subject, String label, State state, String errorCode) {
    }

    /**
     * What the screen shows before the player asks.
     *
     * @param capability whether a request can queue anything
     * @param budget the campaign's use of its generation budget
     * @param subjects scene first, then portraits by label, then items by definition ID
     */
    public record Listing(RuntimeImageJobs.Capability capability, RuntimeImageJobs.Budget budget, List<Subject> subjects) {
    }

    /**
     * Answer of {@link #request}.
     *
     * @param admission the job service's immediate verdict
     * @param subject the subject with its state after the verdict
     */
    public record Requested(RuntimeImageJobs.Admission admission, Subject subject) {
    }

    /**
     * List the subjects the player can see now with the state of their art. Changes nothing.
     *
     * @return the listing, or empty when the owner, adventure and campaign do not line up
     * @throws org.springframework.dao.DataAccessException when storage fails
     */
    public Optional<Listing> list(UUID owner, UUID adventure, UUID campaign) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(campaign, "campaign");
        Optional<List<Eligible>> eligible = eligible(owner, adventure, campaign);
        if (eligible.isEmpty()) {
            return Optional.empty();
        }
        Map<String, ManagedAssets.Metadata> shown = shown(owner, adventure, campaign);
        var subjects = new ArrayList<Subject>();
        for (Eligible item : eligible.get()) {
            subjects.add(describe(campaign, item, shown));
        }
        return Optional.of(new Listing(jobs.capability(), jobs.budget(campaign), List.copyOf(subjects)));
    }

    /**
     * Ask for the image of one subject the player can see now. The appearance text is built from the pinned pack's
     * public fields; the client names only the category and subject.
     *
     * @return the verdict, or empty when the scope does not line up or the subject is not eligible now
     * @throws IllegalArgumentException when the category is not scene, portrait or item, or the subject is not a plain
     *         reference
     * @throws org.springframework.dao.DataAccessException when storage fails; nothing was queued
     */
    public Optional<Requested> request(UUID owner, UUID adventure, UUID campaign, AssetCategory category,
            String subject) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(adventure, "adventure");
        Objects.requireNonNull(campaign, "campaign");
        if (category == null || !RuntimeImageJobs.CATEGORIES.contains(category) || subject == null
                || !PresentationEvent.REFERENCE.matcher(subject).matches()) {
            throw new IllegalArgumentException("invalid image subject");
        }
        Optional<Eligible> found = eligible(owner, adventure, campaign).orElse(List.of()).stream()
                .filter(item -> item.category() == category && item.subject().equals(subject)).findFirst();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Eligible item = found.get();
        var ticket = jobs.request(new RuntimeImageJobs.Request(adventure, campaign, category, subject,
                APPEARANCE_REVISION, item.appearance()));
        String key = key(campaign, category, subject);
        if (ticket.job() != null && ticket.settled() != null) {
            UUID job = ticket.job();
            synchronized (tracked) {
                tracked.remove(key);
                tracked.put(key, new Tracked(job, State.PENDING, null));
            }
            // Registered after the pending state, so a job that already ended cannot be overwritten by it.
            ticket.settled().thenAccept(settled -> settle(key, job, settled));
        } else if (ticket.admission() == RuntimeImageJobs.Admission.REUSED) {
            synchronized (tracked) {
                tracked.remove(key);
            }
        }
        Subject now = ticket.admission() == RuntimeImageJobs.Admission.REUSED
                ? new Subject(category, subject, item.label(), State.READY, null)
                : describe(campaign, item, ticket.current() == null ? Map.of()
                        : Map.of(shownKey(category, subject), ticket.current()));
        return Optional.of(new Requested(ticket.admission(), now));
    }

    /** Record how a tracked job ended, unless a newer job for the subject replaced it meanwhile. */
    private void settle(String key, UUID job, RuntimeImageJobs.Settled settled) {
        synchronized (tracked) {
            Tracked known = tracked.get(key);
            if (known == null || !known.job().equals(job)) {
                return;
            }
            switch (settled.outcome()) {
                // The registry now shows art, or the job never produced anything to report.
                case ACCEPTED, REUSED, CANCELLED, SUPERSEDED -> tracked.remove(key);
                case STALE_APPEARANCE, STALE_SOURCE, STALE_CONTENT, STALE_GENERATION ->
                        tracked.put(key, new Tracked(job, State.FAILED, STALE));
                case FAILED -> tracked.put(key, new Tracked(job, State.FAILED, settled.errorCode() != null
                        && CODE.matcher(settled.errorCode()).matches() ? settled.errorCode() : FAILED));
            }
        }
    }

    /** The subject's display state: art in force wins, then a known job, else missing. */
    private Subject describe(UUID campaign, Eligible item, Map<String, ManagedAssets.Metadata> shown) {
        if (shown.containsKey(shownKey(item.category(), item.subject()))) {
            return new Subject(item.category(), item.subject(), item.label(), State.READY, null);
        }
        Tracked known;
        synchronized (tracked) {
            known = tracked.get(key(campaign, item.category(), item.subject()));
        }
        if (known == null) {
            return new Subject(item.category(), item.subject(), item.label(), State.MISSING, null);
        }
        return new Subject(item.category(), item.subject(), item.label(), known.state(), known.errorCode());
    }

    /** Art in force for the campaign by category and subject, exactly as the artwork listing shows it. */
    private Map<String, ManagedAssets.Metadata> shown(UUID owner, UUID adventure, UUID campaign) {
        var shown = new HashMap<String, ManagedAssets.Metadata>();
        assets.artwork(owner, adventure, campaign).orElse(List.of())
                .forEach(item -> shown.put(shownKey(item.category(), item.subject()), item));
        return shown;
    }

    /**
     * The subjects the player can see now, with their public appearance text.
     *
     * @return the subjects, or empty when the owner, adventure and campaign do not line up
     */
    private Optional<List<Eligible>> eligible(UUID owner, UUID adventure, UUID campaign) {
        UUID revision = jdbc.query("select c.content_revision_id from campaign c join adventure_workspace w "
                + "on w.id = c.adventure_id where c.id = ? and c.adventure_id = ? and w.owner_id = ?",
                (rs, n) -> rs.getObject(1, UUID.class), campaign, adventure, owner).stream().findFirst().orElse(null);
        if (revision == null) {
            return Optional.empty();
        }
        Appearances appearances = appearances(adventure, revision);
        return snapshot.execute(status -> {
            // The scope is authorized again inside the snapshot, so a list never mixes two owners or revisions.
            if (jdbc.queryForObject("select count(*) from campaign c join adventure_workspace w on w.id = c.adventure_id "
                    + "where c.id = ? and c.adventure_id = ? and w.owner_id = ? and c.content_revision_id = ?",
                    Integer.class, campaign, adventure, owner, revision) != 1) {
                return Optional.<List<Eligible>>empty();
            }
            var players = jdbc.query("select a.id, a.location_id from campaign_creation cc join actor a on "
                    + "a.campaign_id = cc.campaign_id and a.id = cc.actor_id where cc.adventure_id = ? and cc.campaign_id = ?",
                    (rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2)}, adventure, campaign);
            var found = new ArrayList<Eligible>();
            if (players.isEmpty()) {
                // A legacy campaign without a creation-bound player sees nothing it could ask an image for.
                return Optional.<List<Eligible>>of(List.of());
            }
            UUID player = (UUID) players.getFirst()[0];
            String here = (String) players.getFirst()[1];
            Text place = here == null ? null : appearances.locations().get(here);
            if (place != null && PresentationEvent.REFERENCE.matcher(here).matches()) {
                found.add(new Eligible(AssetCategory.SCENE, here, place.label(), place.appearance()));
            }
            if (here != null) {
                // Seen here and not since found absent: present as far as the player knows; real positions are not read.
                jdbc.query("select s.subject_actor_id, a.display_name, a.definition_id from actor_sighting s join actor a "
                        + "on a.campaign_id = s.campaign_id and a.id = s.subject_actor_id where s.campaign_id = ? and "
                        + "s.observer_actor_id = ? and s.location_id = ? and s.absence_revision is null "
                        + "order by a.display_name, s.subject_actor_id limit ?", rs -> {
                            Text npc = rs.getString(3) == null ? null : appearances.npcs().get(rs.getString(3));
                            if (npc != null) {
                                found.add(new Eligible(AssetCategory.PORTRAIT, rs.getObject(1, UUID.class).toString(),
                                        rs.getString(2), npc.appearance()));
                            }
                        }, campaign, player, here, MAX_PORTRAITS);
            }
            jdbc.query("select distinct definition_id from item_instance where campaign_id = ? and actor_id = ? "
                    + "order by definition_id limit ?", rs -> {
                        Text item = appearances.items().get(rs.getString(1));
                        if (item != null) {
                            found.add(new Eligible(AssetCategory.ITEM, rs.getString(1), item.label(), item.appearance()));
                        }
                    }, campaign, player, MAX_ITEMS);
            return Optional.<List<Eligible>>of(List.copyOf(found));
        });
    }

    /** The public appearance text of the revision's root definitions, cached; a pack that cannot be loaded has none. */
    private Appearances appearances(UUID adventure, UUID revision) {
        synchronized (cache) {
            Appearances cached = cache.get(revision);
            if (cached != null) {
                return cached;
            }
        }
        Appearances loaded = loads.execute(status -> {
            try {
                return Appearances.from(packs.loadForCampaign(adventure, revision));
            } catch (IllegalArgumentException | IllegalStateException | ClassCastException unavailable) {
                // IllegalArgumentException includes PackException: the sealed revision no longer compiles here.
                status.setRollbackOnly();
                return null;
            }
        });
        if (loaded == null) {
            // Not cached: a pack that becomes loadable again (after a restore) is read on the next call.
            return Appearances.NONE;
        }
        synchronized (cache) {
            cache.put(revision, loaded);
        }
        return loaded;
    }

    private static String key(UUID campaign, AssetCategory category, String subject) {
        return campaign + "\u0000" + category.wire() + '\u0000' + subject;
    }

    private static String shownKey(AssetCategory category, String subject) {
        return category.wire() + '\u0000' + subject;
    }

    /** A subject the player sees now and the public text a job may describe it with. */
    private record Eligible(AssetCategory category, String subject, String label, String appearance) {
    }

    /** Last known job of a subject. */
    private record Tracked(UUID job, State state, String errorCode) {
    }

    /** A public label and the appearance text built from public fields only. */
    private record Text(String label, String appearance) {
    }

    /**
     * Public appearance text of one sealed revision's root pack, by definition ID.
     *
     * @param locations disclosed locations: their description
     * @param npcs disclosed NPC definitions: role and description
     * @param items items: their appearance summary
     */
    private record Appearances(Map<String, Text> locations, Map<String, Text> npcs, Map<String, Text> items) {
        static final Appearances NONE = new Appearances(Map.of(), Map.of(), Map.of());

        /**
         * Read the root pack only: a dependency's definitions have namespaced IDs, which are not plain subject
         * references. Omitted {@code knowledge} is public, as in the pack compiler and the command rules.
         */
        static Appearances from(CampaignPackSnapshot snapshot) {
            var locations = new HashMap<String, Text>();
            var npcs = new HashMap<String, Text>();
            var items = new HashMap<String, Text>();
            for (Definition definition : snapshot.root().definitions()) {
                Map<String, Object> data = definition.data();
                boolean disclosed = !"secret".equals(data.get("knowledge"));
                String label = data.get("name") instanceof String name ? name : definition.id();
                switch (definition.kind()) {
                    case "location" -> {
                        if (disclosed) {
                            text(data.get("description")).ifPresent(text -> locations.put(definition.id(),
                                    new Text(label, text)));
                        }
                    }
                    case "npc" -> {
                        if (disclosed) {
                            String role = data.get("role") instanceof String value ? value.strip() : "";
                            String description = data.get("description") instanceof String value ? value.strip() : "";
                            text(role.isEmpty() ? description : description.isEmpty() ? role : role + ". " + description)
                                    .ifPresent(text -> npcs.put(definition.id(), new Text(label, text)));
                        }
                    }
                    case "item" -> {
                        if (data.get("appearance") instanceof Map<?, ?> appearance) {
                            text(appearance.get("summary")).ifPresent(text -> items.put(definition.id(),
                                    new Text(label, text)));
                        }
                    }
                    default -> {
                        // Other kinds are never depicted by a runtime job.
                    }
                }
            }
            return new Appearances(Map.copyOf(locations), Map.copyOf(npcs), Map.copyOf(items));
        }

        /** One line of at most {@value RuntimeImageJobs#MAX_APPEARANCE} characters, or empty for blank text. */
        private static Optional<String> text(Object value) {
            if (!(value instanceof String text)) {
                return Optional.empty();
            }
            String line = CONTROL.matcher(text).replaceAll(" ").strip();
            if (line.length() > RuntimeImageJobs.MAX_APPEARANCE) {
                line = line.substring(0, RuntimeImageJobs.MAX_APPEARANCE).strip();
            }
            return line.isEmpty() ? Optional.empty() : Optional.of(line);
        }
    }
}
