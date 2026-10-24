-- P03.2 campaign-scoped constraints, resource and ownership bounds, explicit versions
-- (docs/05 sections 1, 3 and 4; QA-20 in docs/15).
--
-- Forward-only on top of V1; never edit this file after release.
-- What this migration adds:
--   * row_version: an explicit optimistic-locking version on every mutable table. JPA
--     maps it with @Version, and a trigger requires every UPDATE to advance it by
--     exactly one. Raw SQL writers must also compare the expected version in WHERE
--     and reject a zero affected-row count. It is a storage token only; domain counters
--     (state_revision, operation_version, presentation generations) keep their
--     docs/05 meaning and are never reused as JPA versions.
--   * Ownership bounds: identity, ownership and provenance columns are frozen after
--     insert, so no row can be moved to another owner, adventure or campaign.
--   * Monotonic counters: canonical counters, journal versions and presentation
--     generations never decrease; state_revision advances at most once per update.
--   * Missing campaign-scoped unique keys, a commit-once journal rule, and size
--     bounds on JSONB payloads.
--   * actor_resource: bounded actor resource values (current within [minimum, maximum]).
-- Trigger failures use SQLSTATE 23514 (check_violation) so they surface like CHECK
-- constraints, and every message starts with the rule name for diagnosis and tests.
-- Triggers on one table fire in name order; rule names are unique per table.

-- Reject an UPDATE that does not advance row_version by exactly one.
-- TG_ARGV: none. Fires BEFORE UPDATE FOR EACH ROW on mutable tables only.
create function require_row_version_step() returns trigger
    language plpgsql
as $$
begin
    if new.row_version is distinct from old.row_version + 1 then
        raise exception using
            errcode = 'check_violation',
            constraint = tg_table_name || '_row_version_step',
            message = format('%s_row_version_step: update on %s must set row_version to %s, not %s',
                             tg_table_name, tg_table_name, old.row_version + 1, new.row_version);
    end if;
    return new;
end;
$$;

-- Reject an UPDATE that changes any listed column.
-- TG_ARGV[0]: rule name used in the message; TG_ARGV[1..]: frozen column names.
-- Columns are compared through to_jsonb so one function serves every table; an
-- unknown column name is a migration bug and fails loudly instead of passing silently.
create function reject_frozen_change() returns trigger
    language plpgsql
as $$
declare
    old_row jsonb := to_jsonb(old);
    new_row jsonb := to_jsonb(new);
    column_name text;
begin
    foreach column_name in array tg_argv[1:] loop
        if not old_row ? column_name then
            raise exception '%: unknown column % on %', tg_argv[0], column_name, tg_table_name;
        end if;
        if old_row -> column_name is distinct from new_row -> column_name then
            raise exception using
                errcode = 'check_violation',
                constraint = tg_argv[0],
                message = format('%s: %s.%s is fixed after insert', tg_argv[0], tg_table_name, column_name);
        end if;
    end loop;
    return new;
end;
$$;

-- Reject an UPDATE that lowers any listed non-null integer column.
-- TG_ARGV[0]: rule name used in the message; TG_ARGV[1..]: counter column names.
create function reject_counter_decrease() returns trigger
    language plpgsql
as $$
declare
    old_row jsonb := to_jsonb(old);
    new_row jsonb := to_jsonb(new);
    column_name text;
begin
    foreach column_name in array tg_argv[1:] loop
        if not old_row ? column_name then
            raise exception '%: unknown column % on %', tg_argv[0], column_name, tg_table_name;
        end if;
        if (new_row ->> column_name)::numeric < (old_row ->> column_name)::numeric then
            raise exception using
                errcode = 'check_violation',
                constraint = tg_argv[0],
                message = format('%s: %s.%s cannot decrease from %s to %s', tg_argv[0], tg_table_name,
                                 column_name, old_row ->> column_name, new_row ->> column_name);
        end if;
    end loop;
    return new;
end;
$$;

-- Reject an UPDATE that rewrites a listed column once it holds a value.
-- TG_ARGV[0]: rule name used in the message; TG_ARGV[1..]: write-once column names.
-- NULL to a value is the single allowed write; a value never changes or returns to NULL.
create function reject_rewrite() returns trigger
    language plpgsql
as $$
declare
    old_row jsonb := to_jsonb(old);
    new_row jsonb := to_jsonb(new);
    column_name text;
begin
    foreach column_name in array tg_argv[1:] loop
        if not old_row ? column_name then
            raise exception '%: unknown column % on %', tg_argv[0], column_name, tg_table_name;
        end if;
        if old_row -> column_name <> 'null'::jsonb
                and old_row -> column_name is distinct from new_row -> column_name then
            raise exception using
                errcode = 'check_violation',
                constraint = tg_argv[0],
                message = format('%s: %s.%s is write-once', tg_argv[0], tg_table_name, column_name);
        end if;
    end loop;
    return new;
end;
$$;

-- Campaign-specific rule: one successful state-changing operation advances the
-- canonical revision by exactly one (docs/05 section 1), so an update may keep it or
-- add one. Rewinding is a restore or branch operation (docs/05 section 7), not an update.
create function require_state_revision_step() returns trigger
    language plpgsql
as $$
begin
    -- Numeric arithmetic also permits a non-canonical update at BIGINT's upper bound.
    if new.state_revision::numeric not between old.state_revision::numeric and old.state_revision::numeric + 1 then
        raise exception using
            errcode = 'check_violation',
            constraint = 'campaign_state_revision_step',
            message = format('campaign_state_revision_step: state_revision may only move from %s to %s, not %s',
                             old.state_revision, old.state_revision::numeric + 1, new.state_revision);
    end if;
    return new;
end;
$$;

-- Journal-specific rule: terminal lifecycle statuses (docs/07 section 2) never change.
-- The Java OperationStatus table remains the full transition authority; the database
-- guarantees only that finished operations cannot be reopened by any client.
create function reject_terminal_status_change() returns trigger
    language plpgsql
as $$
begin
    if old.status in ('NEEDS_CLARIFICATION', 'REJECTED', 'DELIVERED', 'COMMITTED_NARRATION_PENDING',
                      'CANCELLED', 'FAILED_BEFORE_COMMIT')
            and new.status is distinct from old.status then
        raise exception using
            errcode = 'check_violation',
            constraint = 'operation_journal_terminal_status',
            message = format('operation_journal_terminal_status: %s is terminal', old.status);
    end if;
    return new;
end;
$$;

-- Explicit version fields on every mutable V1 table. Existing rows start at zero.
alter table identity_owner_profile
    add column row_version bigint not null default 0
        constraint identity_owner_profile_row_version_ck check (row_version >= 0);
alter table adventure_workspace
    add column row_version bigint not null default 0
        constraint adventure_workspace_row_version_ck check (row_version >= 0);
alter table campaign
    add column row_version bigint not null default 0
        constraint campaign_row_version_ck check (row_version >= 0);
alter table presentation_campaign_settings
    add column row_version bigint not null default 0
        constraint presentation_campaign_settings_row_version_ck check (row_version >= 0);
alter table actor
    add column row_version bigint not null default 0
        constraint actor_row_version_ck check (row_version >= 0);
alter table operation_journal
    add column row_version bigint not null default 0
        constraint operation_journal_row_version_ck check (row_version >= 0);

-- Campaign-scoped unique keys so later tables can reference settlements and
-- checkpoints through composite (campaign_id, id) foreign keys like other rows.
alter table reward_settlement
    add constraint reward_settlement_campaign_uk unique (campaign_id, id);
alter table campaign_checkpoint
    add constraint campaign_checkpoint_campaign_uk unique (campaign_id, id);

-- At most one operation commits a given campaign revision (docs/05 section 4); NULL
-- revisions of uncommitted operations are distinct and never collide.
alter table operation_journal
    add constraint operation_journal_committed_revision_uk unique (campaign_id, committed_revision);
-- A committed status carries its revision and receipt; an uncommitted one has neither
-- a revision nor a canonical commit. Matches OperationStatus.isCommitted().
alter table operation_journal
    add constraint operation_journal_commit_consistency_ck check (
        (status in ('COMMITTED', 'NARRATING', 'DELIVERED', 'COMMITTED_NARRATION_PENDING')
            and committed_revision is not null and receipt is not null)
        or (status not in ('COMMITTED', 'NARRATING', 'DELIVERED', 'COMMITTED_NARRATION_PENDING')
            and committed_revision is null and receipt is null));

-- JSONB size bounds in bytes of the canonical text rendering. jsonb normalizes
-- whitespace, so the stored rendering can be slightly larger than compact input; the
-- engine pre-checks the input size, and these constraints remain the authority.
alter table campaign
    add constraint campaign_pending_decision_size_ck
        check (pending_decision is null or octet_length(pending_decision::text) <= 16384);
alter table operation_journal
    add constraint operation_journal_receipt_size_ck
        check (receipt is null or octet_length(receipt::text) <= 65536);
alter table campaign_event
    add constraint campaign_event_payload_size_ck check (octet_length(payload::text) <= 65536);
alter table reward_settlement
    add constraint reward_settlement_rewards_size_ck check (octet_length(rewards::text) <= 16384);
alter table campaign_checkpoint
    add constraint campaign_checkpoint_snapshot_size_ck check (octet_length(snapshot::text) <= 8388608);

-- Bounded numeric resource of one actor, such as health or stamina (docs/05 sections
-- 2 and 4). The value always stays within the declared bounds; going below the
-- minimum is never stored as a negative balance, because debt is a separate entity.
-- The actor must belong to the same campaign (composite foreign key).
create table actor_resource (
    id               uuid        primary key,
    campaign_id      uuid        not null references campaign (id) on delete restrict,
    actor_id         uuid        not null,
    resource_key     text        not null check (char_length(resource_key) between 1 and 256),
    minimum_value    bigint      not null,
    current_value    bigint      not null,
    maximum_value    bigint      not null,
    updated_revision bigint      not null check (updated_revision >= 0),
    row_version      bigint      not null default 0
                                 constraint actor_resource_row_version_ck check (row_version >= 0),
    created_at       timestamptz not null,
    updated_at       timestamptz not null,
    constraint actor_resource_bounds_ck
        check (minimum_value <= current_value and current_value <= maximum_value),
    constraint actor_resource_actor_fk foreign key (campaign_id, actor_id)
        references actor (campaign_id, id) on delete restrict,
    -- Also the index for per-actor lookups and for the actor foreign key checks.
    constraint actor_resource_key_uk unique (campaign_id, actor_id, resource_key),
    constraint actor_resource_campaign_uk unique (campaign_id, id)
);

-- Row version step on every mutable table.
create trigger identity_owner_profile_row_version before update on identity_owner_profile
    for each row execute function require_row_version_step();
create trigger adventure_workspace_row_version before update on adventure_workspace
    for each row execute function require_row_version_step();
create trigger campaign_row_version before update on campaign
    for each row execute function require_row_version_step();
create trigger presentation_campaign_settings_row_version before update on presentation_campaign_settings
    for each row execute function require_row_version_step();
create trigger actor_row_version before update on actor
    for each row execute function require_row_version_step();
create trigger operation_journal_row_version before update on operation_journal
    for each row execute function require_row_version_step();
create trigger actor_resource_row_version before update on actor_resource
    for each row execute function require_row_version_step();

-- Frozen identity, ownership and provenance columns.
create trigger identity_owner_profile_frozen before update on identity_owner_profile
    for each row execute function reject_frozen_change('identity_owner_profile_frozen', 'id', 'created_at');
create trigger adventure_workspace_frozen before update on adventure_workspace
    for each row execute function reject_frozen_change('adventure_workspace_frozen', 'id', 'owner_id',
        'created_at');
create trigger campaign_frozen before update on campaign
    for each row execute function reject_frozen_change('campaign_frozen', 'id', 'adventure_id',
        'content_revision_id', 'seed_identity', 'created_at');
create trigger presentation_campaign_settings_frozen before update on presentation_campaign_settings
    for each row execute function reject_frozen_change('presentation_campaign_settings_frozen', 'campaign_id');
create trigger actor_frozen before update on actor
    for each row execute function reject_frozen_change('actor_frozen', 'id', 'campaign_id', 'kind',
        'definition_id', 'created_revision', 'created_at');
create trigger operation_journal_frozen before update on operation_journal
    for each row execute function reject_frozen_change('operation_journal_frozen', 'id', 'campaign_id',
        'idempotency_key', 'payload_hash', 'command_type', 'base_revision', 'created_at');
create trigger actor_resource_frozen before update on actor_resource
    for each row execute function reject_frozen_change('actor_resource_frozen', 'id', 'campaign_id',
        'actor_id', 'resource_key', 'created_at');

-- Counters that only move forward.
create trigger campaign_counters_monotonic before update on campaign
    for each row execute function reject_counter_decrease('campaign_counters_monotonic', 'state_revision',
        'game_turn', 'world_time_seconds', 'event_sequence');
create trigger campaign_state_revision_step before update on campaign
    for each row execute function require_state_revision_step();
create trigger presentation_campaign_settings_monotonic before update on presentation_campaign_settings
    for each row execute function reject_counter_decrease('presentation_campaign_settings_monotonic',
        'locale_revision', 'glossary_revision', 'media_configuration_generation');
create trigger operation_journal_monotonic before update on operation_journal
    for each row execute function reject_counter_decrease('operation_journal_monotonic', 'operation_version');
create trigger actor_resource_monotonic before update on actor_resource
    for each row execute function reject_counter_decrease('actor_resource_monotonic', 'updated_revision');

-- Commit outcome is recorded once, and finished operations stay finished.
create trigger operation_journal_write_once before update on operation_journal
    for each row execute function reject_rewrite('operation_journal_write_once', 'committed_revision', 'receipt');
create trigger operation_journal_terminal_status before update on operation_journal
    for each row execute function reject_terminal_status_change();
