-- P09.1: typed knowledge (docs/08 section 4, docs/05 "Knowledge and progression").
-- Four separate records keep canonical truth apart from what any actor believes:
--   knowledge_claim    a typed proposition with its privileged truth status, disclosure scope,
--                      provenance and validity interval (supersession);
--   knowledge_belief   one holder's stance on a claim, how it was acquired and from whom;
--   actor_sighting     an observer's last known location of another actor, separate from the
--                      canonical current location in actor.location_id;
--   actor_commitment   a promise or obligation between two actors and its resolution.
-- Every row is written by the canonical commit in the same transaction as the events it cites,
-- so provenance is a composite foreign key to an event of the same campaign and the database
-- checks that the cited event belongs to the recorded revision. Nothing here is seeded from old
-- saves; the P06.2 free-text campaign_known_fact projection is unchanged.

-- Typed reference grammar shared by the tables below. Campaign rows use "<kind>:<uuid>"
-- (actor, item); pinned content uses "<kind>:<content id>" (location, definition) where a
-- content id is a local id or a dependency-qualified "pack#id" as campaign creation stores it.
create function knowledge_ref_valid(reference text) returns boolean
    language sql immutable as $$
    select reference ~ ('^((actor|item):[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
        || '|(location|definition):[a-z][a-z0-9._:-]{0,127}(#[a-z][a-z0-9._:-]{0,127})?)$')
$$;

-- Provenance guard. TG_ARGV holds pairs (event sequence column, revision column); for each pair
-- with a non-null sequence, the cited campaign_event must exist in this campaign and carry exactly
-- that state revision. A row can therefore never cite an event of another revision as its source,
-- and a source cannot be attached to a later or earlier operation by mistake.
create function require_event_at_revision() returns trigger language plpgsql as $$
declare
    row_data jsonb := to_jsonb(new);
    position integer := 0;
    sequence_value bigint;
    revision_value bigint;
begin
    while position < tg_nargs loop
        if not row_data ? tg_argv[position] or not row_data ? tg_argv[position + 1] then
            raise exception 'require_event_at_revision: unknown column on %', tg_table_name;
        end if;
        sequence_value := (row_data ->> tg_argv[position])::bigint;
        revision_value := (row_data ->> tg_argv[position + 1])::bigint;
        if sequence_value is not null and not exists (select 1 from campaign_event e
                where e.campaign_id = new.campaign_id and e.sequence = sequence_value
                  and e.state_revision = revision_value) then
            raise exception using
                errcode = 'check_violation',
                constraint = tg_table_name || '_provenance',
                message = format('%s_provenance: event %s is not an event of revision %s',
                                 tg_table_name, sequence_value, revision_value);
        end if;
        position := position + 2;
    end loop;
    return new;
end;
$$;

-- 1. Claims. The proposition (subject, predicate, object/qualifier/value or statement) is frozen;
-- only the truth determination and the supersession marker may be written later. Truth is
-- privileged: a player-facing reader must never project it. UNDETERMINED may be resolved once to
-- TRUE or FALSE; a resolved truth never changes, so a later world change is a new superseding
-- claim and the old one keeps its truth for its validity interval
-- [established_revision, superseded_revision). Disclosure decides who may see the proposition
-- without holding a belief: COMMON claims are common knowledge of every actor, HOLDERS claims
-- only reach an audience through that audience's own belief. Neither scope says anything about
-- truth ("private to the GM" is a HOLDERS claim nobody holds, not a true one).
-- Actor locations are deliberately not a predicate: the current location is actor.location_id
-- and what someone last saw is actor_sighting, so no third copy can drift.
create table knowledge_claim (
    id                         uuid    primary key,
    campaign_id                uuid    not null references campaign (id) on delete restrict,
    subject_ref                text    not null constraint knowledge_claim_subject_ck check (knowledge_ref_valid(subject_ref)),
    predicate                  text    not null check (predicate in ('EXISTS', 'PROPERTY', 'RELATION', 'STATEMENT')),
    object_ref                 text    constraint knowledge_claim_object_ck check (object_ref is null or knowledge_ref_valid(object_ref)),
    qualifier                  text    check (qualifier is null or qualifier ~ '^[a-z][a-z0-9_.-]{0,63}$'),
    property_value             jsonb   check (property_value is null or (jsonb_typeof(property_value) in ('string', 'number', 'boolean')
                                                  and octet_length(property_value::text) <= 1024)),
    statement                  text    check (statement is null or char_length(statement) between 1 and 2000),
    statement_locale           text    check (statement_locale is null or statement_locale in ('en', 'it')),
    disclosure                 text    not null check (disclosure in ('COMMON', 'HOLDERS')),
    truth                      text    not null check (truth in ('TRUE', 'FALSE', 'UNDETERMINED')),
    established_revision       bigint  not null check (established_revision > 0),
    established_world_time     bigint  not null check (established_world_time >= 0),
    established_event_sequence bigint  not null,
    truth_revision             bigint  not null,
    truth_event_sequence       bigint  not null,
    supersedes                 uuid,
    superseded_revision        bigint,
    row_version                bigint  not null default 0 check (row_version >= 0),
    constraint knowledge_claim_campaign_uk unique (campaign_id, id),
    constraint knowledge_claim_supersedes_uk unique (supersedes),
    constraint knowledge_claim_established_fk foreign key (campaign_id, established_event_sequence)
        references campaign_event (campaign_id, sequence) on delete restrict,
    constraint knowledge_claim_truth_fk foreign key (campaign_id, truth_event_sequence)
        references campaign_event (campaign_id, sequence) on delete restrict,
    constraint knowledge_claim_supersedes_fk foreign key (campaign_id, supersedes)
        references knowledge_claim (campaign_id, id) on delete restrict,
    -- Each predicate has exactly one shape; the statement is a source-language gloss for typed
    -- predicates and the whole proposition for STATEMENT. Its language is recorded with it.
    constraint knowledge_claim_shape_ck check (case predicate
        when 'EXISTS' then object_ref is null and qualifier is null and property_value is null
        when 'PROPERTY' then object_ref is null and qualifier is not null and property_value is not null
        when 'RELATION' then object_ref is not null and qualifier is not null and property_value is null
        else object_ref is null and qualifier is null and property_value is null and statement is not null
    end),
    constraint knowledge_claim_locale_ck check ((statement is null) = (statement_locale is null)),
    constraint knowledge_claim_truth_order_ck check (truth_revision >= established_revision),
    constraint knowledge_claim_interval_ck check (superseded_revision is null or superseded_revision >= established_revision),
    constraint knowledge_claim_not_self_ck check (supersedes is distinct from id)
);
create index knowledge_claim_subject_idx on knowledge_claim (campaign_id, subject_ref);
create index knowledge_claim_current_idx on knowledge_claim (campaign_id, disclosure) where superseded_revision is null;
create trigger knowledge_claim_frozen before update on knowledge_claim
    for each row execute function reject_frozen_change('knowledge_claim_frozen', 'id', 'campaign_id', 'subject_ref',
        'predicate', 'object_ref', 'qualifier', 'property_value', 'statement', 'statement_locale', 'disclosure',
        'established_revision', 'established_world_time', 'established_event_sequence', 'supersedes');
create trigger knowledge_claim_once before update on knowledge_claim
    for each row execute function reject_rewrite('knowledge_claim_once', 'superseded_revision');
create trigger knowledge_claim_provenance before insert or update on knowledge_claim
    for each row execute function require_event_at_revision('established_event_sequence', 'established_revision',
        'truth_event_sequence', 'truth_revision');
create trigger knowledge_claim_row_version before update on knowledge_claim
    for each row execute function require_row_version_step();
create trigger knowledge_claim_no_delete before delete on knowledge_claim
    for each row execute function reject_mutation();

-- Truth determination and supersession rules that need both row images or other rows.
create function guard_knowledge_claim() returns trigger language plpgsql as $$
declare
    predecessor knowledge_claim;
begin
    if tg_op = 'UPDATE' then
        if new.truth is distinct from old.truth and (old.truth <> 'UNDETERMINED'
                or new.truth_revision <= old.truth_revision or old.superseded_revision is not null) then
            raise exception using errcode = 'check_violation', constraint = 'knowledge_claim_truth_once',
                message = 'knowledge_claim_truth_once: only a current undetermined claim is determined, '
                    || 'once and at a later revision';
        end if;
        if new.truth is not distinct from old.truth and (new.truth_revision, new.truth_event_sequence)
                is distinct from (old.truth_revision, old.truth_event_sequence) then
            raise exception using errcode = 'check_violation', constraint = 'knowledge_claim_truth_once',
                message = 'knowledge_claim_truth_once: truth provenance changes only with a determination';
        end if;
        return new;
    end if;
    -- A new claim starts with its own provenance for both establishment and truth.
    if (new.truth_revision, new.truth_event_sequence)
            is distinct from (new.established_revision, new.established_event_sequence) then
        raise exception using errcode = 'check_violation', constraint = 'knowledge_claim_truth_once',
            message = 'knowledge_claim_truth_once: a new claim records its truth at establishment';
    end if;
    if new.supersedes is not null then
        -- The predecessor is about the same subject and was closed at exactly this revision by
        -- the same transaction (writers close it first, then insert the successor).
        select * into predecessor from knowledge_claim where campaign_id = new.campaign_id and id = new.supersedes;
        if not found or predecessor.subject_ref <> new.subject_ref
                or predecessor.superseded_revision is distinct from new.established_revision then
            raise exception using errcode = 'check_violation', constraint = 'knowledge_claim_supersession',
                message = 'knowledge_claim_supersession: a successor replaces a claim about the same subject '
                    || 'closed at its own revision';
        end if;
    end if;
    return new;
end;
$$;
create trigger knowledge_claim_guard before insert or update on knowledge_claim
    for each row execute function guard_knowledge_claim();

-- 2. Beliefs. One active (unsuperseded) stance per holder and claim. A stance is the holder's
-- epistemic state, not truth: KNOWS requires a TRUE claim when written (direct confirmation),
-- every other stance may concern a false or undetermined claim. A belief is replaced, never
-- edited: the writer closes the predecessor at this revision, then inserts the successor that
-- names it; the successor may concern another claim (believing "at the docks" replaces "at the
-- mill") but always the same holder. Acquisition records how the holder learned it; TOLD names
-- the informant and READ the document or authored source.
create table knowledge_belief (
    id                     uuid   primary key,
    campaign_id            uuid   not null references campaign (id) on delete restrict,
    holder_actor_id        uuid   not null,
    claim_id               uuid   not null,
    stance                 text   not null check (stance in ('KNOWS', 'BELIEVES', 'RUMORED', 'SUSPECTS', 'DOUBTS')),
    acquisition            text   not null check (acquisition in ('OBSERVED', 'TOLD', 'READ', 'INFERRED')),
    informant_actor_id     uuid,
    source_ref             text   constraint knowledge_belief_source_ck check (source_ref is null
                                      or (knowledge_ref_valid(source_ref) and source_ref ~ '^(item|definition):')),
    learned_revision       bigint not null check (learned_revision > 0),
    learned_world_time     bigint not null check (learned_world_time >= 0),
    learned_event_sequence bigint not null,
    supersedes             uuid,
    superseded_revision    bigint,
    row_version            bigint not null default 0 check (row_version >= 0),
    constraint knowledge_belief_campaign_uk unique (campaign_id, id),
    constraint knowledge_belief_supersedes_uk unique (supersedes),
    constraint knowledge_belief_holder_fk foreign key (campaign_id, holder_actor_id)
        references actor (campaign_id, id) on delete restrict,
    constraint knowledge_belief_informant_fk foreign key (campaign_id, informant_actor_id)
        references actor (campaign_id, id) on delete restrict,
    constraint knowledge_belief_claim_fk foreign key (campaign_id, claim_id)
        references knowledge_claim (campaign_id, id) on delete restrict,
    constraint knowledge_belief_event_fk foreign key (campaign_id, learned_event_sequence)
        references campaign_event (campaign_id, sequence) on delete restrict,
    constraint knowledge_belief_supersedes_fk foreign key (campaign_id, supersedes)
        references knowledge_belief (campaign_id, id) on delete restrict,
    constraint knowledge_belief_told_ck check ((acquisition = 'TOLD') = (informant_actor_id is not null)),
    constraint knowledge_belief_read_ck check ((acquisition = 'READ') = (source_ref is not null)),
    constraint knowledge_belief_informant_ck check (informant_actor_id is distinct from holder_actor_id),
    constraint knowledge_belief_interval_ck check (superseded_revision is null or superseded_revision >= learned_revision),
    constraint knowledge_belief_not_self_ck check (supersedes is distinct from id)
);
create unique index knowledge_belief_active_uk on knowledge_belief (campaign_id, holder_actor_id, claim_id)
    where superseded_revision is null;
create index knowledge_belief_claim_idx on knowledge_belief (campaign_id, claim_id);
create trigger knowledge_belief_frozen before update on knowledge_belief
    for each row execute function reject_frozen_change('knowledge_belief_frozen', 'id', 'campaign_id',
        'holder_actor_id', 'claim_id', 'stance', 'acquisition', 'informant_actor_id', 'source_ref',
        'learned_revision', 'learned_world_time', 'learned_event_sequence', 'supersedes');
create trigger knowledge_belief_once before update on knowledge_belief
    for each row execute function reject_rewrite('knowledge_belief_once', 'superseded_revision');
create trigger knowledge_belief_provenance before insert on knowledge_belief
    for each row execute function require_event_at_revision('learned_event_sequence', 'learned_revision');
create trigger knowledge_belief_row_version before update on knowledge_belief
    for each row execute function require_row_version_step();
create trigger knowledge_belief_no_delete before delete on knowledge_belief
    for each row execute function reject_mutation();

-- Insert-time rules that read other rows: KNOWS needs a TRUE claim that is still current, and a
-- successor names a predecessor of the same holder closed at this revision.
create function guard_knowledge_belief() returns trigger language plpgsql as $$
declare
    predecessor knowledge_belief;
begin
    if new.stance = 'KNOWS' and not exists (select 1 from knowledge_claim c where c.campaign_id = new.campaign_id
            and c.id = new.claim_id and c.truth = 'TRUE' and c.superseded_revision is null) then
        raise exception using errcode = 'check_violation', constraint = 'knowledge_belief_knows_truth',
            message = 'knowledge_belief_knows_truth: KNOWS requires a current TRUE claim';
    end if;
    if new.supersedes is not null then
        select * into predecessor from knowledge_belief where campaign_id = new.campaign_id and id = new.supersedes;
        if not found or predecessor.holder_actor_id <> new.holder_actor_id
                or predecessor.superseded_revision is distinct from new.learned_revision then
            raise exception using errcode = 'check_violation', constraint = 'knowledge_belief_supersession',
                message = 'knowledge_belief_supersession: a successor replaces a belief of the same holder '
                    || 'closed at its own revision';
        end if;
    end if;
    return new;
end;
$$;
create trigger knowledge_belief_guard before insert on knowledge_belief
    for each row execute function guard_knowledge_belief();

-- 3. Last known locations. One row per observer and subject. The subject's real current location
-- stays in actor.location_id and is never copied here; this row is only what the observer last
-- perceived (OBSERVED) or was told (TOLD, with the informant), when, and from which event. When the
-- observer later perceives that location without the subject, the absence is recorded beside the
-- sighting instead of erasing it, so "last seen at the mill, not there when you returned" stays
-- expressible. Observation revisions never move backwards; a newer sighting clears the absence.
create table actor_sighting (
    campaign_id             uuid   not null references campaign (id) on delete restrict,
    observer_actor_id       uuid   not null,
    subject_actor_id        uuid   not null,
    location_id             text   not null constraint actor_sighting_location_ck
                                       check (knowledge_ref_valid('location:' || location_id)),
    acquisition             text   not null check (acquisition in ('OBSERVED', 'TOLD')),
    informant_actor_id      uuid,
    observed_revision       bigint not null check (observed_revision > 0),
    observed_world_time     bigint not null check (observed_world_time >= 0),
    observed_event_sequence bigint not null,
    absence_revision        bigint,
    absence_world_time      bigint check (absence_world_time is null or absence_world_time >= 0),
    absence_event_sequence  bigint,
    row_version             bigint not null default 0 check (row_version >= 0),
    primary key (campaign_id, observer_actor_id, subject_actor_id),
    constraint actor_sighting_observer_fk foreign key (campaign_id, observer_actor_id)
        references actor (campaign_id, id) on delete restrict,
    constraint actor_sighting_subject_fk foreign key (campaign_id, subject_actor_id)
        references actor (campaign_id, id) on delete restrict,
    constraint actor_sighting_informant_fk foreign key (campaign_id, informant_actor_id)
        references actor (campaign_id, id) on delete restrict,
    constraint actor_sighting_observed_fk foreign key (campaign_id, observed_event_sequence)
        references campaign_event (campaign_id, sequence) on delete restrict,
    constraint actor_sighting_absence_fk foreign key (campaign_id, absence_event_sequence)
        references campaign_event (campaign_id, sequence) on delete restrict,
    constraint actor_sighting_self_ck check (observer_actor_id <> subject_actor_id),
    constraint actor_sighting_told_ck check ((acquisition = 'TOLD') = (informant_actor_id is not null)),
    constraint actor_sighting_informant_ck check (informant_actor_id is distinct from observer_actor_id),
    constraint actor_sighting_absence_ck check ((absence_revision is null) = (absence_world_time is null)
        and (absence_revision is null) = (absence_event_sequence is null)),
    constraint actor_sighting_absence_order_ck check (absence_revision is null or absence_revision > observed_revision)
);
create trigger actor_sighting_frozen before update on actor_sighting
    for each row execute function reject_frozen_change('actor_sighting_frozen', 'campaign_id', 'observer_actor_id',
        'subject_actor_id');
create trigger actor_sighting_monotonic before update on actor_sighting
    for each row execute function reject_counter_decrease('actor_sighting_monotonic', 'observed_revision',
        'observed_world_time');
create trigger actor_sighting_provenance before insert or update on actor_sighting
    for each row execute function require_event_at_revision('observed_event_sequence', 'observed_revision',
        'absence_event_sequence', 'absence_revision');
create trigger actor_sighting_row_version before update on actor_sighting
    for each row execute function require_row_version_step();
create trigger actor_sighting_no_delete before delete on actor_sighting
    for each row execute function reject_mutation();

-- 4. Commitments. A promise, obligation or agreement from a debtor to a creditor, with the exact
-- terms in the language they were made (quoted = the wording itself matters for continuity), an
-- optional typed subject and an optional in-world deadline. OPEN is the only unresolved status;
-- a resolution is written once with its own provenance and never reopens. An overdue commitment
-- stays OPEN: lateness is derived from world time at read time, so a query never advances or
-- settles anything. PARTIES commitments reach only the debtor's and creditor's audiences.
create table actor_commitment (
    id                      uuid    primary key,
    campaign_id             uuid    not null references campaign (id) on delete restrict,
    kind                    text    not null check (kind in ('PROMISE', 'OBLIGATION', 'AGREEMENT')),
    debtor_actor_id         uuid    not null,
    creditor_actor_id       uuid    not null,
    subject_ref             text    constraint actor_commitment_subject_ck
                                        check (subject_ref is null or knowledge_ref_valid(subject_ref)),
    terms                   text    not null check (char_length(terms) between 1 and 2000),
    terms_locale            text    not null check (terms_locale in ('en', 'it')),
    quoted                  boolean not null,
    disclosure              text    not null check (disclosure in ('PARTIES', 'COMMON')),
    due_world_time          bigint  check (due_world_time is null or due_world_time >= 0),
    status                  text    not null check (status in ('OPEN', 'FULFILLED', 'BROKEN', 'RELEASED')),
    made_revision           bigint  not null check (made_revision > 0),
    made_world_time         bigint  not null check (made_world_time >= 0),
    made_event_sequence     bigint  not null,
    resolved_revision       bigint,
    resolved_world_time     bigint  check (resolved_world_time is null or resolved_world_time >= 0),
    resolved_event_sequence bigint,
    row_version             bigint  not null default 0 check (row_version >= 0),
    constraint actor_commitment_campaign_uk unique (campaign_id, id),
    constraint actor_commitment_debtor_fk foreign key (campaign_id, debtor_actor_id)
        references actor (campaign_id, id) on delete restrict,
    constraint actor_commitment_creditor_fk foreign key (campaign_id, creditor_actor_id)
        references actor (campaign_id, id) on delete restrict,
    constraint actor_commitment_made_fk foreign key (campaign_id, made_event_sequence)
        references campaign_event (campaign_id, sequence) on delete restrict,
    constraint actor_commitment_resolved_fk foreign key (campaign_id, resolved_event_sequence)
        references campaign_event (campaign_id, sequence) on delete restrict,
    constraint actor_commitment_parties_ck check (debtor_actor_id <> creditor_actor_id),
    constraint actor_commitment_resolution_ck check ((status = 'OPEN') = (resolved_revision is null)
        and (resolved_revision is null) = (resolved_world_time is null)
        and (resolved_revision is null) = (resolved_event_sequence is null)),
    constraint actor_commitment_order_ck check (resolved_revision is null or resolved_revision > made_revision)
);
create index actor_commitment_debtor_idx on actor_commitment (campaign_id, debtor_actor_id) where status = 'OPEN';
create index actor_commitment_creditor_idx on actor_commitment (campaign_id, creditor_actor_id) where status = 'OPEN';
create trigger actor_commitment_frozen before update on actor_commitment
    for each row execute function reject_frozen_change('actor_commitment_frozen', 'id', 'campaign_id', 'kind',
        'debtor_actor_id', 'creditor_actor_id', 'subject_ref', 'terms', 'terms_locale', 'quoted', 'disclosure',
        'due_world_time', 'made_revision', 'made_world_time', 'made_event_sequence');
-- The resolution columns go from null to a value once; together with the status check above this
-- also makes FULFILLED, BROKEN and RELEASED terminal.
create trigger actor_commitment_once before update on actor_commitment
    for each row execute function reject_rewrite('actor_commitment_once', 'resolved_revision',
        'resolved_world_time', 'resolved_event_sequence');
create trigger actor_commitment_provenance before insert or update on actor_commitment
    for each row execute function require_event_at_revision('made_event_sequence', 'made_revision',
        'resolved_event_sequence', 'resolved_revision');
create trigger actor_commitment_row_version before update on actor_commitment
    for each row execute function require_row_version_step();
create trigger actor_commitment_no_delete before delete on actor_commitment
    for each row execute function reject_mutation();

-- 5. Plan dependencies may now name claims, beliefs and commitments by row version, exactly like
-- the P07.6 kinds. Their writers step row_version on every change (determination, supersession,
-- resolution), so an admitted plan that read one becomes observably stale. Unknown, malformed or
-- foreign references still evaluate to null and therefore stale.
create or replace function plan_reference_version(target_campaign uuid, reference text) returns bigint
    language plpgsql stable as $$
declare
    kind text := split_part(reference, ':', 1);
    target uuid;
begin
    if reference is null or reference !~ ('^(actor|resource|item|character|known-fact|known-quest|claim|belief|commitment):'
            || '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') then
        return null;
    end if;
    target := substr(reference, char_length(kind) + 2)::uuid;
    return case kind
        when 'actor' then (select row_version from actor where campaign_id = target_campaign and id = target)
        when 'resource' then (select row_version from actor_resource
                              where campaign_id = target_campaign and id = target)
        when 'item' then (select row_version from item_instance where campaign_id = target_campaign and id = target)
        when 'character' then (select row_version from character_state
                               where campaign_id = target_campaign and actor_id = target)
        when 'known-fact' then (select row_version from campaign_known_fact
                                where campaign_id = target_campaign and id = target)
        when 'known-quest' then (select row_version from campaign_known_quest
                                 where campaign_id = target_campaign and id = target)
        when 'claim' then (select row_version from knowledge_claim where campaign_id = target_campaign and id = target)
        when 'belief' then (select row_version from knowledge_belief where campaign_id = target_campaign and id = target)
        when 'commitment' then (select row_version from actor_commitment
                                where campaign_id = target_campaign and id = target)
    end;
end;
$$;
