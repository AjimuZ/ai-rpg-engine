package org.rpgengine.knowledge;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.rpgengine.persistence.CanonicalValues;
import tools.jackson.databind.json.JsonMapper;

/**
 * One trusted, typed knowledge effect of a resolved canonical operation (P09.1). Values are data,
 * never callbacks or generic patches: a claim, a truth determination, a belief, a reported or
 * observed sighting, a commitment or a commitment resolution. Each names the event of its own
 * operation that is its provenance by 0-based index into that operation's event list; the commit
 * turns the index into the event's campaign sequence, so a change can never cite an event of
 * another operation. Construction checks shape and bounds only; current-state premises (row
 * versions, existence, open status) are rechecked by {@link KnowledgeLedger} under the commit's
 * campaign lock, and the database repeats the structural rules.
 */
public sealed interface KnowledgeChange {
    /** Operation events a change may cite; equal to the resolved-commit event bound. */
    int MAX_EVENTS = 128;

    /** @return 0-based index of the citing operation's event that is this change's provenance */
    int sourceEvent();

    /**
     * Actors whose own records this change writes or who act in it (holder, observer, informant,
     * debtor, creditor). A registered player command may only touch its participants; a mere
     * subject of a claim or sighting is not included, because being talked about is not an effect.
     */
    Set<UUID> actors();

    /** Typed proposition shape; actor locations are deliberately absent (see {@link Sighting}). */
    enum Predicate { EXISTS, PROPERTY, RELATION, STATEMENT }

    /** Who may see a claim's proposition without holding a belief about it. */
    enum Disclosure { COMMON, HOLDERS }

    /** Privileged canonical truth of a claim during its validity interval. */
    enum Truth { TRUE, FALSE, UNDETERMINED }

    /** A holder's epistemic stance; independent of truth except that KNOWS needs a TRUE claim. */
    enum Stance { KNOWS, BELIEVES, RUMORED, SUSPECTS, DOUBTS }

    /** How a holder acquired a belief. */
    enum Acquisition { OBSERVED, TOLD, READ, INFERRED }

    /** Kind of commitment between two actors. */
    enum CommitmentKind { PROMISE, OBLIGATION, AGREEMENT }

    /** Commitment lifecycle; OPEN is the only unresolved status and the others are terminal. */
    enum CommitmentStatus { OPEN, FULFILLED, BROKEN, RELEASED }

    /** Who may see a commitment: its two parties only, or every actor. */
    enum CommitmentDisclosure { PARTIES, COMMON }

    /**
     * Replace an existing record read at {@code expectedRowVersion}. The ledger closes the
     * predecessor at the committing revision, then writes the successor that names it.
     */
    record Supersession(UUID predecessorId, long expectedRowVersion) {
        /** Require an identity and a non-negative storage version. */
        public Supersession {
            Objects.requireNonNull(predecessorId, "predecessorId");
            CanonicalValues.requireNonNegative(expectedRowVersion, "expectedRowVersion");
        }
    }

    /**
     * Register a typed claim. The statement is the proposition itself for {@code STATEMENT} and an
     * optional source-language gloss otherwise; its language is mandatory whenever it is present.
     *
     * @param claimId engine-assigned identity
     * @param subjectRef typed reference ({@link KnowledgeRefs})
     * @param predicate proposition shape
     * @param objectRef second typed reference, required for and only for {@code RELATION}
     * @param qualifier relation or property key, required for {@code RELATION} and {@code PROPERTY}
     * @param valueJson JSON string, number or boolean, required for and only for {@code PROPERTY}
     * @param statement optional proposition text (required for {@code STATEMENT})
     * @param statementLocale {@code en} or {@code it} when a statement is present
     * @param disclosure audience scope of the proposition
     * @param truth initial truth; a later {@link Determination} may resolve UNDETERMINED once
     * @param supersedes optional earlier claim about the same subject that this one replaces
     * @param sourceEvent provenance event index
     */
    record Claim(UUID claimId, String subjectRef, Predicate predicate, String objectRef, String qualifier,
            String valueJson, String statement, String statementLocale, Disclosure disclosure, Truth truth,
            Supersession supersedes, int sourceEvent) implements KnowledgeChange {
        private static final Pattern QUALIFIER = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
        private static final JsonMapper JSON = JsonMapper.builder().build();

        /** Reject a malformed shape for the predicate, bad references or out-of-range bounds. */
        public Claim {
            Objects.requireNonNull(claimId, "claimId");
            KnowledgeRefs.require(subjectRef, "subjectRef");
            Objects.requireNonNull(predicate, "predicate");
            Objects.requireNonNull(disclosure, "disclosure");
            Objects.requireNonNull(truth, "truth");
            if (objectRef != null) {
                KnowledgeRefs.require(objectRef, "objectRef");
            }
            if (qualifier != null && !QUALIFIER.matcher(qualifier).matches()) {
                throw new IllegalArgumentException("invalid qualifier");
            }
            if (valueJson != null) {
                // Only a scalar may describe a property; objects and arrays would reopen generic patches.
                tools.jackson.databind.JsonNode value;
                try {
                    value = JSON.readTree(valueJson);
                } catch (RuntimeException malformed) {
                    throw new IllegalArgumentException("property value is not JSON", malformed);
                }
                if (value == null || !(value.isString() || value.isNumber() || value.isBoolean())
                        || valueJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024) {
                    throw new IllegalArgumentException("property value must be a bounded JSON scalar");
                }
            }
            statement = CanonicalValues.optionalText(statement, "statement", 2000);
            requireLocale(statement, statementLocale);
            boolean shaped = switch (predicate) {
                case EXISTS -> objectRef == null && qualifier == null && valueJson == null;
                case PROPERTY -> objectRef == null && qualifier != null && valueJson != null;
                case RELATION -> objectRef != null && qualifier != null && valueJson == null;
                case STATEMENT -> objectRef == null && qualifier == null && valueJson == null && statement != null;
            };
            if (!shaped) {
                throw new IllegalArgumentException("claim shape does not match predicate " + predicate);
            }
            requireEvent(sourceEvent);
        }

        @Override
        public Set<UUID> actors() {
            return Set.of();
        }
    }

    /**
     * Resolve an UNDETERMINED, still current claim to TRUE or FALSE, once.
     *
     * @param claimId claim of this campaign
     * @param expectedRowVersion storage version the resolver read
     * @param truth TRUE or FALSE
     * @param sourceEvent provenance event index
     */
    record Determination(UUID claimId, long expectedRowVersion, Truth truth, int sourceEvent)
            implements KnowledgeChange {
        /** Reject a missing identity or an UNDETERMINED target truth. */
        public Determination {
            Objects.requireNonNull(claimId, "claimId");
            CanonicalValues.requireNonNegative(expectedRowVersion, "expectedRowVersion");
            if (truth == null || truth == Truth.UNDETERMINED) {
                throw new IllegalArgumentException("a determination is TRUE or FALSE");
            }
            requireEvent(sourceEvent);
        }

        @Override
        public Set<UUID> actors() {
            return Set.of();
        }
    }

    /**
     * Record a holder's stance on a claim. TOLD requires the informant, READ the source document
     * (an item instance or an authored definition); other acquisitions take neither.
     *
     * @param beliefId engine-assigned identity
     * @param holderId actor who holds the belief
     * @param claimId claim of this campaign, possibly written earlier in the same change list
     * @param stance epistemic stance
     * @param acquisition how the holder learned it
     * @param informantId teller for TOLD, otherwise null
     * @param sourceRef {@code item:} or {@code definition:} reference for READ, otherwise null
     * @param supersedes optional earlier belief of the same holder that this one replaces
     * @param sourceEvent provenance event index
     */
    record Belief(UUID beliefId, UUID holderId, UUID claimId, Stance stance, Acquisition acquisition,
            UUID informantId, String sourceRef, Supersession supersedes, int sourceEvent) implements KnowledgeChange {
        /** Reject missing identities and acquisition evidence that does not match its kind. */
        public Belief {
            Objects.requireNonNull(beliefId, "beliefId");
            Objects.requireNonNull(holderId, "holderId");
            Objects.requireNonNull(claimId, "claimId");
            Objects.requireNonNull(stance, "stance");
            Objects.requireNonNull(acquisition, "acquisition");
            if ((acquisition == Acquisition.TOLD) != (informantId != null) || holderId.equals(informantId)) {
                throw new IllegalArgumentException("TOLD requires exactly one informant other than the holder");
            }
            if ((acquisition == Acquisition.READ) != (sourceRef != null)) {
                throw new IllegalArgumentException("READ requires exactly one source document");
            }
            if (sourceRef != null && !KnowledgeRefs.document(sourceRef)) {
                throw new IllegalArgumentException("a read source is an item or an authored definition");
            }
            requireEvent(sourceEvent);
        }

        @Override
        public Set<UUID> actors() {
            return informantId == null ? Set.of(holderId) : Set.of(holderId, informantId);
        }
    }

    /**
     * Update an observer's last known location of another actor. Without an informant the sighting
     * is OBSERVED and must match the subject's real location at commit; with one it is TOLD and
     * records what the observer was told, which may be wrong.
     *
     * @param observerId actor whose knowledge changes
     * @param subjectId actor who was seen or reported
     * @param locationId pinned location content id
     * @param informantId teller of a reported sighting, otherwise null
     * @param sourceEvent provenance event index
     */
    record Sighting(UUID observerId, UUID subjectId, String locationId, UUID informantId, int sourceEvent)
            implements KnowledgeChange {
        /** Reject self-sightings, a self-informant and malformed location ids. */
        public Sighting {
            Objects.requireNonNull(observerId, "observerId");
            Objects.requireNonNull(subjectId, "subjectId");
            if (observerId.equals(subjectId) || observerId.equals(informantId)) {
                throw new IllegalArgumentException("an observer neither sights nor informs itself");
            }
            KnowledgeRefs.require("location:" + locationId, "locationId");
            requireEvent(sourceEvent);
        }

        @Override
        public Set<UUID> actors() {
            return informantId == null ? Set.of(observerId) : Set.of(observerId, informantId);
        }
    }

    /**
     * Register an OPEN commitment from debtor to creditor.
     *
     * @param commitmentId engine-assigned identity
     * @param kind promise, obligation or agreement
     * @param debtorId actor who owes
     * @param creditorId actor owed, distinct from the debtor
     * @param subjectRef optional typed subject of the commitment
     * @param terms the terms in the language they were made
     * @param termsLocale {@code en} or {@code it}
     * @param quoted true when the exact wording matters for continuity
     * @param disclosure parties only or common knowledge
     * @param dueWorldTime optional in-world deadline in campaign seconds
     * @param sourceEvent provenance event index
     */
    record Commitment(UUID commitmentId, CommitmentKind kind, UUID debtorId, UUID creditorId, String subjectRef,
            String terms, String termsLocale, boolean quoted, CommitmentDisclosure disclosure, Long dueWorldTime,
            int sourceEvent) implements KnowledgeChange {
        /** Reject missing parties, identical parties, unbounded terms and negative deadlines. */
        public Commitment {
            Objects.requireNonNull(commitmentId, "commitmentId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(debtorId, "debtorId");
            Objects.requireNonNull(creditorId, "creditorId");
            Objects.requireNonNull(disclosure, "disclosure");
            if (debtorId.equals(creditorId)) {
                throw new IllegalArgumentException("a commitment binds two different actors");
            }
            if (subjectRef != null) {
                KnowledgeRefs.require(subjectRef, "subjectRef");
            }
            terms = CanonicalValues.requireText(terms, "terms", 2000);
            requireLocale(terms, termsLocale);
            if (dueWorldTime != null) {
                CanonicalValues.requireNonNegative(dueWorldTime, "dueWorldTime");
            }
            requireEvent(sourceEvent);
        }

        @Override
        public Set<UUID> actors() {
            return Set.of(debtorId, creditorId);
        }
    }

    /**
     * Resolve an OPEN commitment, once.
     *
     * @param commitmentId commitment of this campaign
     * @param expectedRowVersion storage version the resolver read
     * @param status FULFILLED, BROKEN or RELEASED
     * @param sourceEvent provenance event index
     */
    record Resolution(UUID commitmentId, long expectedRowVersion, CommitmentStatus status, int sourceEvent)
            implements KnowledgeChange {
        /** Reject a missing identity or OPEN as a resolution. */
        public Resolution {
            Objects.requireNonNull(commitmentId, "commitmentId");
            CanonicalValues.requireNonNegative(expectedRowVersion, "expectedRowVersion");
            if (status == null || status == CommitmentStatus.OPEN) {
                throw new IllegalArgumentException("a resolution is FULFILLED, BROKEN or RELEASED");
            }
            requireEvent(sourceEvent);
        }

        @Override
        public Set<UUID> actors() {
            return Set.of();
        }
    }

    /** Text and its language travel together: both present (en/it) or both absent. */
    private static void requireLocale(String text, String locale) {
        if ((text == null) != (locale == null) || (locale != null && !locale.equals("en") && !locale.equals("it"))) {
            throw new IllegalArgumentException("text requires its language (en or it) and only then");
        }
    }

    /** Bound the provenance index; the resolved commit additionally checks it against its own events. */
    private static void requireEvent(int sourceEvent) {
        if (sourceEvent < 0 || sourceEvent >= MAX_EVENTS) {
            throw new IllegalArgumentException("sourceEvent out of range");
        }
    }
}
