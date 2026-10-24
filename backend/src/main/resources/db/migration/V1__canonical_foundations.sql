-- P03.1 canonical foundations (docs/05 sections 1-7, docs/25 section 1).
--
-- Forward-only: never edit this file after release; later changes add V2, V3, ...
-- Conventions used by every table below:
--   * Opaque UUID primary keys are assigned by the engine, never by the database,
--     so identities are known before insert and never encode meaning.
--   * Ownership is relational. A campaign belongs to exactly one adventure, and every
--     campaign-scoped child references (campaign_id, id) of its parent through a
--     composite foreign key, so a row can never point at another campaign's record.
--   * All foreign keys are ON DELETE RESTRICT: deleting an adventure or campaign is an
--     explicit, reviewed owner action, never an implicit cascade.
--   * Canonical counters are non-negative bigints. Presentation generations (locale,
--     glossary, media) live in a separate table so changing them cannot touch world
--     counters (docs/29 section 2).
--   * Enum-like columns are text with CHECK lists equal to the Java enum names.
--   * Hashes are lowercase hexadecimal SHA-256 digests (64 characters).
--   * JSONB holds only immutable definitions or bounded typed payloads, always objects.
-- This migration seeds no rows: gameplay data only appears through engine operations.

-- Local owner profile. ui_locale is a display preference (allowlist en/it) and is
-- never a world value; no account or credential material is stored here.
create table identity_owner_profile (
    id           uuid        primary key,
    display_name text        not null check (char_length(display_name) between 1 and 120),
    ui_locale    text        not null default 'en' check (ui_locale in ('en', 'it')),
    created_at   timestamptz not null,
    updated_at   timestamptz not null
);

-- Adventure workspace: the owner-scoped container for authoring, admitted content
-- revisions and campaigns (docs/25 section 1). source_locale is the authoring language.
create table adventure_workspace (
    id            uuid        primary key,
    owner_id      uuid        not null references identity_owner_profile (id) on delete restrict,
    title         text        not null check (char_length(title) between 1 and 200),
    genre         text        not null check (char_length(genre) between 1 and 80),
    source_locale text        not null check (source_locale in ('en', 'it')),
    created_at    timestamptz not null,
    updated_at    timestamptz not null
);
create index adventure_workspace_owner_idx on adventure_workspace (owner_id);

-- Immutable admitted content revision of one adventure: the exact pack snapshot and
-- ruleset a campaign is pinned to. A changed byte requires a new revision row.
create table content_revision (
    id              uuid        primary key,
    adventure_id    uuid        not null references adventure_workspace (id) on delete restrict,
    pack_id         text        not null check (char_length(pack_id) between 1 and 128),
    pack_version    text        not null check (char_length(pack_version) between 1 and 64),
    content_hash    text        not null check (content_hash ~ '^[0-9a-f]{64}$'),
    schema_version  integer     not null check (schema_version > 0),
    ruleset_id      text        not null check (char_length(ruleset_id) between 1 and 128),
    ruleset_version text        not null check (char_length(ruleset_version) between 1 and 64),
    ruleset_hash    text        not null check (ruleset_hash ~ '^[0-9a-f]{64}$'),
    source_locale   text        not null check (source_locale in ('en', 'it')),
    admitted_at     timestamptz not null,
    -- One admitted revision per pack version inside an adventure; a same-version
    -- conflict must become a new version, never a silent overwrite.
    constraint content_revision_version_uk unique (adventure_id, pack_id, pack_version),
    -- Target of the campaign composite key proving the revision belongs to the adventure.
    constraint content_revision_adventure_uk unique (adventure_id, id)
);

-- Campaign: one playthrough of one adventure pinned to one admitted content revision.
-- The composite key makes "revision belongs to my adventure" a database invariant.
-- The counters are the canonical world counters of docs/05 section 1; they are
-- advanced only by committed operations (P03.3) and never by presentation changes.
create table campaign (
    id                  uuid        primary key,
    adventure_id        uuid        not null references adventure_workspace (id) on delete restrict,
    content_revision_id uuid        not null,
    title               text        not null check (char_length(title) between 1 and 200),
    seed_identity       text        not null check (char_length(seed_identity) between 1 and 128),
    mode                text        not null check (mode in ('EXPLORATION', 'ENCOUNTER')),
    pending_decision    jsonb       check (pending_decision is null or jsonb_typeof(pending_decision) = 'object'),
    state_revision      bigint      not null default 0 check (state_revision >= 0),
    game_turn           bigint      not null default 0 check (game_turn >= 0),
    world_time_seconds  bigint      not null default 0 check (world_time_seconds >= 0),
    event_sequence      bigint      not null default 0 check (event_sequence >= 0),
    created_at          timestamptz not null,
    updated_at          timestamptz not null,
    constraint campaign_content_revision_fk foreign key (adventure_id, content_revision_id)
        references content_revision (adventure_id, id) on delete restrict
);
create index campaign_adventure_idx on campaign (adventure_id);
create index campaign_content_revision_idx on campaign (adventure_id, content_revision_id);

-- Presentation settings of a campaign, deliberately outside the campaign row:
-- narrative locale and the presentation generations are not world state, so updating
-- them never changes state_revision, game_turn, world time or the event sequence.
create table presentation_campaign_settings (
    campaign_id                    uuid        primary key references campaign (id) on delete restrict,
    narrative_locale               text        not null check (narrative_locale in ('en', 'it')),
    locale_revision                bigint      not null default 0 check (locale_revision >= 0),
    glossary_revision              bigint      not null default 0 check (glossary_revision >= 0),
    media_configuration_generation bigint      not null default 0 check (media_configuration_generation >= 0),
    updated_at                     timestamptz not null
);

-- Actor instance inside a campaign (character, NPC or creature). definition_id names
-- the content definition it was instantiated from; generated actors may have none.
create table actor (
    id               uuid        primary key,
    campaign_id      uuid        not null references campaign (id) on delete restrict,
    kind             text        not null check (kind in ('CHARACTER', 'NPC', 'CREATURE')),
    definition_id    text        check (definition_id is null or char_length(definition_id) between 1 and 256),
    display_name     text        not null check (char_length(display_name) between 1 and 120),
    location_id      text        check (location_id is null or char_length(location_id) between 1 and 256),
    created_revision bigint      not null check (created_revision >= 0),
    created_at       timestamptz not null,
    constraint actor_campaign_uk unique (campaign_id, id)
);

-- Operation journal (docs/07 section 2). The idempotency key is unique per campaign;
-- the same key with another payload hash is a conflict decided by the engine.
-- operation_version is the journal row's own lifecycle counter, separate from the
-- campaign state_revision. Lease and fencing columns arrive with P03.5.
create table operation_journal (
    id                uuid        primary key,
    campaign_id       uuid        not null references campaign (id) on delete restrict,
    idempotency_key   text        not null check (char_length(idempotency_key) between 1 and 128),
    payload_hash      text        not null check (payload_hash ~ '^[0-9a-f]{64}$'),
    command_type      text        not null check (char_length(command_type) between 1 and 128),
    base_revision     bigint      not null check (base_revision >= 0),
    status            text        not null check (status in (
                          'ACCEPTED', 'PLANNING', 'NEEDS_CLARIFICATION', 'VALIDATING', 'REJECTED',
                          'COMMITTING', 'COMMITTED', 'NARRATING', 'DELIVERED',
                          'COMMITTED_NARRATION_PENDING', 'CANCELLED', 'FAILED_BEFORE_COMMIT')),
    operation_version bigint      not null default 0 check (operation_version >= 0),
    committed_revision bigint     check (committed_revision is null or committed_revision > base_revision),
    receipt           jsonb       check (receipt is null or jsonb_typeof(receipt) = 'object'),
    created_at        timestamptz not null,
    updated_at        timestamptz not null,
    constraint operation_journal_idempotency_uk unique (campaign_id, idempotency_key),
    constraint operation_journal_campaign_uk unique (campaign_id, id)
);

-- Append-only domain event log (docs/05 section 6). The per-campaign sequence is
-- unique and positive; operation, actor and causation must belong to the same campaign.
create table campaign_event (
    id                     uuid        primary key,
    campaign_id            uuid        not null references campaign (id) on delete restrict,
    sequence               bigint      not null check (sequence > 0),
    operation_id           uuid        not null,
    actor_id               uuid,
    causation_event_id     uuid,
    correlation_id         uuid        not null,
    event_type             text        not null check (char_length(event_type) between 1 and 128),
    event_version          integer     not null check (event_version > 0),
    state_revision         bigint      not null check (state_revision >= 0),
    world_time_seconds     bigint      not null check (world_time_seconds >= 0),
    ruleset_hash           text        not null check (ruleset_hash ~ '^[0-9a-f]{64}$'),
    visibility             text        not null check (visibility in ('PUBLIC', 'INTERNAL')),
    payload_schema_version integer     not null check (payload_schema_version > 0),
    payload                jsonb       not null check (jsonb_typeof(payload) = 'object'),
    created_at             timestamptz not null,
    constraint campaign_event_sequence_uk unique (campaign_id, sequence),
    constraint campaign_event_campaign_uk unique (campaign_id, id),
    constraint campaign_event_operation_fk foreign key (campaign_id, operation_id)
        references operation_journal (campaign_id, id) on delete restrict,
    constraint campaign_event_actor_fk foreign key (campaign_id, actor_id)
        references actor (campaign_id, id) on delete restrict,
    constraint campaign_event_causation_fk foreign key (campaign_id, causation_event_id)
        references campaign_event (campaign_id, id) on delete restrict
);
create index campaign_event_operation_idx on campaign_event (campaign_id, operation_id);
create index campaign_event_actor_idx on campaign_event (campaign_id, actor_id);
create index campaign_event_causation_idx on campaign_event (campaign_id, causation_event_id);

-- Reward settlement identity (docs/05 section 2): one settlement per reward source and
-- recipient in a campaign, so a replayed quest or encounter can never pay twice.
create table reward_settlement (
    id              uuid        primary key,
    campaign_id     uuid        not null references campaign (id) on delete restrict,
    source_key      text        not null check (char_length(source_key) between 1 and 256),
    recipient_key   text        not null check (char_length(recipient_key) between 1 and 256),
    operation_id    uuid        not null,
    applied_revision bigint     not null check (applied_revision > 0),
    rewards         jsonb       not null check (jsonb_typeof(rewards) = 'object'),
    settled_at      timestamptz not null,
    constraint reward_settlement_source_uk unique (campaign_id, source_key, recipient_key),
    constraint reward_settlement_operation_fk foreign key (campaign_id, operation_id)
        references operation_journal (campaign_id, id) on delete restrict
);
create index reward_settlement_operation_idx on reward_settlement (campaign_id, operation_id);

-- Checkpoint (docs/05 section 7): a hashed snapshot at one state revision with the
-- event high-water mark it covers. Restore and RNG cursor handling arrive with P03.4/5.
create table campaign_checkpoint (
    id                      uuid        primary key,
    campaign_id             uuid        not null references campaign (id) on delete restrict,
    state_revision          bigint      not null check (state_revision >= 0),
    event_high_water        bigint      not null check (event_high_water >= 0),
    game_turn               bigint      not null check (game_turn >= 0),
    world_time_seconds      bigint      not null check (world_time_seconds >= 0),
    content_hash            text        not null check (content_hash ~ '^[0-9a-f]{64}$'),
    ruleset_hash            text        not null check (ruleset_hash ~ '^[0-9a-f]{64}$'),
    snapshot_schema_version integer     not null check (snapshot_schema_version > 0),
    snapshot_hash           text        not null check (snapshot_hash ~ '^[0-9a-f]{64}$'),
    snapshot                jsonb       not null check (jsonb_typeof(snapshot) = 'object'),
    created_at              timestamptz not null,
    constraint campaign_checkpoint_revision_uk unique (campaign_id, state_revision)
);

-- Immutability guard shared by append-only and admitted records. Raising here (rather
-- than relying on application discipline) means a stray UPDATE or DELETE from any
-- client fails loudly. TRUNCATE is not row-level and stays available to disposable
-- test databases; production restore paths are defined by P03.5.
create function reject_mutation() returns trigger language plpgsql as $$
begin
    raise exception '% on % is not allowed: rows are immutable', tg_op, tg_table_name
        using errcode = 'restrict_violation';
end;
$$;

create trigger campaign_event_immutable
    before update or delete on campaign_event
    for each row execute function reject_mutation();

create trigger content_revision_immutable
    before update on content_revision
    for each row execute function reject_mutation();

create trigger campaign_checkpoint_immutable
    before update on campaign_checkpoint
    for each row execute function reject_mutation();

create trigger reward_settlement_immutable
    before update on reward_settlement
    for each row execute function reject_mutation();
