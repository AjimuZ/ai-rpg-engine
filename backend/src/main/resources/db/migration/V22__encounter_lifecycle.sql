-- P11.1: frozen encounters and their participants (docs/11 sections 2-5, ADR 0028).
--
-- An encounter row is written only by a canonical commit, in the same transaction as the event that
-- announces the entry, and starts in phase ENTERING: everything it fixes at entry (template, location,
-- pinned content revision and ruleset, the resolved combat profile and the frozen definition with zones,
-- objectives, opening policy and roster) is stored with its SHA-256 and can never change afterwards. Only
-- the lifecycle columns move, and only along the arrows of the combat state machine. A campaign has at
-- most one active encounter. Participants carry their frozen profile; a template participant keeps its
-- own Health and Focus here, while the player's pools stay in actor_resource like in exploration.

create table encounter (
    id                   uuid        primary key,
    campaign_id          uuid        not null references campaign (id) on delete restrict,
    template_id          text        not null check (char_length(template_id) between 1 and 256),
    location_id          text        not null check (char_length(location_id) between 1 and 256),
    phase                text        not null check (phase in ('ENTERING', 'ROUND_OPEN', 'ACTOR_START',
                                         'AWAITING_ACTION', 'RESOLVING', 'ACTOR_END', 'ROUND_END',
                                         'OUTCOME_PENDING', 'SETTLED', 'NARRATIVE_RESUME')),
    -- Completed or current round; 0 until initiative opens the first round.
    round                integer     not null default 0 check (round between 0 and 100),
    content_revision_id  uuid        not null references content_revision (id) on delete restrict,
    ruleset_hash         text        not null check (ruleset_hash ~ '^[0-9a-f]{64}$'),
    profile              jsonb       not null check (jsonb_typeof(profile) = 'object'),
    profile_hash         text        not null check (profile_hash ~ '^[0-9a-f]{64}$'),
    definition           jsonb       not null check (jsonb_typeof(definition) = 'object'),
    definition_hash      text        not null check (definition_hash ~ '^[0-9a-f]{64}$'),
    opening_policy       text        not null check (opening_policy in ('initiative', 'first', 'forbidden')),
    -- Hostile participant of the declared opening strike; NONE without one, PENDING until it resolves once.
    opening_target       text        check (opening_target is null or char_length(opening_target) between 1 and 128),
    opening_status       text        not null check (opening_status in ('NONE', 'PENDING', 'RESOLVED')),
    started_operation_id uuid        not null references operation_journal (id) on delete restrict,
    event_sequence       bigint      not null check (event_sequence > 0),
    started_revision     bigint      not null check (started_revision > 0),
    updated_revision     bigint      not null check (updated_revision > 0),
    row_version          bigint      not null default 0 check (row_version >= 0),
    created_at           timestamptz not null,
    constraint encounter_campaign_uk unique (campaign_id, id),
    foreign key (campaign_id, event_sequence) references campaign_event (campaign_id, sequence) on delete restrict,
    constraint encounter_opening_ck check ((opening_target is null) = (opening_status = 'NONE')
        and (opening_target is null or opening_policy <> 'forbidden'))
);
-- At most one active encounter per campaign: a replayed or concurrent entry fails here.
create unique index encounter_active_uk on encounter (campaign_id)
    where phase not in ('SETTLED', 'NARRATIVE_RESUME');

create trigger encounter_frozen before update on encounter
    for each row execute function reject_frozen_change('encounter_frozen', 'id', 'campaign_id', 'template_id',
        'location_id', 'content_revision_id', 'ruleset_hash', 'profile', 'profile_hash', 'definition',
        'definition_hash', 'opening_policy', 'opening_target', 'started_operation_id', 'event_sequence',
        'started_revision', 'created_at');
create trigger encounter_row_version before update on encounter
    for each row execute function require_row_version_step();
create trigger encounter_revision before update on encounter
    for each row execute function reject_counter_decrease('encounter_revision', 'updated_revision', 'round');
create trigger encounter_no_delete before delete on encounter
    for each row execute function reject_mutation();

-- The lifecycle of docs/11 section 2, mirrored from EncounterPhase: a stored encounter starts at
-- ENTERING in round 0, every update follows one arrow (or keeps its phase), a round number grows by one
-- exactly when a round opens, and an opening strike resolves at most once.
create function encounter_lifecycle() returns trigger language plpgsql as $$
begin
    if tg_op = 'INSERT' then
        if new.phase <> 'ENTERING' or new.round <> 0 or new.opening_status = 'RESOLVED' then
            raise exception using errcode = 'check_violation', constraint = 'encounter_lifecycle',
                message = 'encounter_lifecycle: an encounter is stored at ENTERING, round 0, opening unresolved';
        end if;
        return new;
    end if;
    if new.phase is distinct from old.phase and not (old.phase, new.phase) in (
            ('ENTERING', 'ROUND_OPEN'),
            ('ROUND_OPEN', 'ACTOR_START'),
            ('ACTOR_START', 'AWAITING_ACTION'), ('ACTOR_START', 'OUTCOME_PENDING'),
            ('AWAITING_ACTION', 'RESOLVING'), ('AWAITING_ACTION', 'ACTOR_END'),
            ('RESOLVING', 'AWAITING_ACTION'), ('RESOLVING', 'ACTOR_END'), ('RESOLVING', 'OUTCOME_PENDING'),
            ('ACTOR_END', 'ACTOR_START'), ('ACTOR_END', 'ROUND_END'),
            ('ROUND_END', 'ROUND_OPEN'), ('ROUND_END', 'OUTCOME_PENDING'),
            ('OUTCOME_PENDING', 'SETTLED'),
            ('SETTLED', 'NARRATIVE_RESUME')) then
        raise exception using errcode = 'check_violation', constraint = 'encounter_lifecycle',
            message = format('encounter_lifecycle: illegal transition %s -> %s', old.phase, new.phase);
    end if;
    if new.round <> old.round and not (new.round = old.round + 1 and new.phase = 'ROUND_OPEN'
            and old.phase is distinct from 'ROUND_OPEN') then
        raise exception using errcode = 'check_violation', constraint = 'encounter_lifecycle',
            message = 'encounter_lifecycle: the round grows by one exactly when a round opens';
    end if;
    if new.opening_status is distinct from old.opening_status
            and not (old.opening_status = 'PENDING' and new.opening_status = 'RESOLVED') then
        raise exception using errcode = 'check_violation', constraint = 'encounter_lifecycle',
            message = 'encounter_lifecycle: an opening strike resolves once';
    end if;
    return new;
end $$;
create trigger encounter_lifecycle before insert or update on encounter
    for each row execute function encounter_lifecycle();

-- One row per roster entry: the player (participant_id 'player', ordinal 0) and every template participant
-- in authored order. Frozen columns never change; zone, status and an encounter-local pool are the mutable
-- tactical state later operations update.
create table encounter_participant (
    encounter_id   uuid    not null,
    campaign_id    uuid    not null,
    participant_id text    not null check (char_length(participant_id) between 1 and 128),
    ordinal        integer not null check (ordinal between 0 and 8),
    actor_id       uuid,
    side           text    not null check (side in ('PLAYER', 'ALLY', 'HOSTILE')),
    zone_id        text    not null check (char_length(zone_id) between 1 and 128),
    status         text    not null default 'ACTIVE' check (status in ('ACTIVE', 'DEFEATED', 'ESCAPED', 'SURRENDERED')),
    -- Encounter-local pools of a template participant; null for an actor-backed participant (actor_resource).
    health_current integer check (health_current is null or health_current between 0 and 999),
    focus_current  integer check (focus_current is null or focus_current between 0 and 999),
    frozen         jsonb   not null check (jsonb_typeof(frozen) = 'object'),
    row_version    bigint  not null default 0 check (row_version >= 0),
    primary key (encounter_id, participant_id),
    constraint encounter_participant_ordinal_uk unique (encounter_id, ordinal),
    foreign key (campaign_id, encounter_id) references encounter (campaign_id, id) on delete restrict,
    foreign key (campaign_id, actor_id) references actor (campaign_id, id) on delete restrict,
    constraint encounter_participant_player_ck check ((side = 'PLAYER') = (participant_id = 'player')
        and (side = 'PLAYER') = (ordinal = 0) and (side <> 'PLAYER' or actor_id is not null)),
    constraint encounter_participant_pool_ck check ((actor_id is null) = (health_current is not null)
        and (actor_id is null) = (focus_current is not null))
);
create trigger encounter_participant_frozen before update on encounter_participant
    for each row execute function reject_frozen_change('encounter_participant_frozen', 'encounter_id', 'campaign_id',
        'participant_id', 'ordinal', 'actor_id', 'side', 'frozen');
create trigger encounter_participant_row_version before update on encounter_participant
    for each row execute function require_row_version_step();
create trigger encounter_participant_no_delete before delete on encounter_participant
    for each row execute function reject_mutation();
