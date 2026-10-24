-- P03.6: durable preparation jobs, immutable planning snapshots, versioned plan and
-- dependency records, per-scope query epochs and campaign recovery generations
-- (docs/28 sections 5, 7, 9 and 10). None of these rows is canonical world state:
-- writing them never advances state_revision, game_turn, world time or the event
-- sequence. Lock order for every writer that needs both rows is campaign, then job.
-- Database guards repeat the service rules so a stray client cannot reopen a terminal
-- job, reuse a fence, rewrite an admitted artifact or rewind an epoch/generation.

-- A restore increments this generation (tools/database_backup). Jobs, snapshots and
-- callbacks created under an earlier generation can never be admitted afterwards,
-- even when every revision number happens to match. The JPA entity does not map it.
alter table campaign
    add column recovery_generation bigint not null default 0
        constraint campaign_recovery_generation_ck check (recovery_generation >= 0);
create trigger campaign_generation_monotonic before update on campaign
    for each row execute function reject_counter_decrease('campaign_generation_monotonic', 'recovery_generation');
-- Composite target so job rows bind their adventure and campaign together.
alter table campaign add constraint campaign_adventure_uk unique (adventure_id, id);
alter table campaign add constraint campaign_content_uk unique (id, content_revision_id);

-- Per-campaign bounded query scope (for example "quest:q-1" or "region:north").
-- Canonical writers increment an epoch in the same transaction as the world change;
-- a missing row means epoch 0. Plans store the epoch they read, so any later write
-- to that scope makes their QUERY dependencies observably stale.
create table query_epoch (
    campaign_id uuid   not null references campaign (id) on delete restrict,
    scope_key   text   not null constraint query_epoch_scope_ck check (scope_key ~ '^[a-z][a-z0-9_.:-]{0,127}$'),
    epoch       bigint not null check (epoch >= 0),
    row_version bigint not null default 0 check (row_version >= 0),
    primary key (campaign_id, scope_key)
);
create trigger query_epoch_frozen before update on query_epoch
    for each row execute function reject_frozen_change('query_epoch_frozen', 'campaign_id', 'scope_key');
create trigger query_epoch_monotonic before update on query_epoch
    for each row execute function reject_counter_decrease('query_epoch_monotonic', 'epoch');
create trigger query_epoch_no_delete before delete on query_epoch
    for each row execute function reject_mutation();
create trigger query_epoch_row_version before update on query_epoch
    for each row execute function require_row_version_step();

-- Immutable planner input. Counters and pinned hashes are copied by the service from
-- locked database rows, never supplied by a caller; context_digest is computed in SQL
-- over every stored field so equal digests mean equal recorded inputs.
create table planning_snapshot (
    id                     uuid        primary key,
    campaign_id            uuid        not null references campaign (id) on delete restrict,
    campaign_generation    bigint      not null check (campaign_generation >= 0),
    content_revision_id    uuid        not null references content_revision (id) on delete restrict,
    source_revision        bigint      not null check (source_revision >= 0),
    event_high_water       bigint      not null check (event_high_water >= 0),
    world_time_seconds     bigint      not null check (world_time_seconds >= 0),
    locale_revision        bigint      not null check (locale_revision >= 0),
    glossary_revision      bigint      not null check (glossary_revision >= 0),
    audience               text        not null check (audience in ('PLANNER_PRIVATE', 'PUBLIC')),
    selected_state         jsonb       not null check (jsonb_typeof(selected_state) = 'object'
                                                       and octet_length(selected_state::text) <= 65536),
    source_refs            jsonb       not null check (jsonb_typeof(source_refs) = 'array'
                                                       and octet_length(source_refs::text) <= 16384),
    dependency_manifest    jsonb       not null check (jsonb_typeof(dependency_manifest) = 'array'
                                                       and octet_length(dependency_manifest::text) <= 65536),
    pack_hash              text        not null check (pack_hash ~ '^[0-9a-f]{64}$'),
    ruleset_hash           text        not null check (ruleset_hash ~ '^[0-9a-f]{64}$'),
    author_constraint_hash text        not null check (author_constraint_hash ~ '^[0-9a-f]{64}$'),
    policy_hash            text        not null check (policy_hash ~ '^[0-9a-f]{64}$'),
    context_digest         text        not null check (context_digest ~ '^[0-9a-f]{64}$'),
    created_at             timestamptz not null,
    constraint planning_snapshot_campaign_uk unique (campaign_id, id),
    constraint planning_snapshot_content_fk foreign key (campaign_id, content_revision_id)
        references campaign (id, content_revision_id) on delete restrict
);
create trigger planning_snapshot_immutable before update or delete on planning_snapshot
    for each row execute function reject_mutation();

-- One logical preparation job. Retries add ai_job_attempt rows under the same job,
-- so a new runtime thread never resets attempt counts. snapshot_id is the snapshot
-- bound by the latest attempt (each attempt keeps its own write-once copy);
-- result_digest and failure_code describe the terminal outcome only, while every
-- attempt keeps its own result and failure history. fence is a high-water mark:
-- every claim and every lease release increments it, so a worker that lost its
-- lease can never heartbeat, record a result or be admitted with an older value.
create table ai_job (
    id                             uuid        primary key,
    adventure_id                   uuid        not null,
    campaign_id                    uuid        not null,
    campaign_generation            bigint      not null check (campaign_generation >= 0),
    kind                           text        not null check (kind ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    target                         text        not null check (char_length(target) between 1 and 256),
    purpose                        text        not null check (purpose ~ '^[a-z][a-z0-9_-]{0,63}$'),
    contract_version               text        not null check (contract_version ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    dependency_fingerprint         text        not null check (dependency_fingerprint ~ '^[0-9a-f]{64}$'),
    dedup_key                      text        not null check (dedup_key ~ '^[0-9a-f]{64}$'),
    priority                       integer     not null check (priority between 0 and 1000),
    critical                       boolean     not null,
    status                         text        not null check (status in (
        'QUEUED', 'LEASED', 'RUNNING', 'CANCEL_REQUESTED', 'RESULT_RECEIVED', 'VALIDATING', 'COMPLETED',
        'SUPERSEDED', 'CANCELLED', 'FAILED', 'EXPIRED', 'RECONCILIATION_REQUIRED')),
    payload_hash                   text        not null check (payload_hash ~ '^[0-9a-f]{64}$'),
    schema_hash                    text        not null check (schema_hash ~ '^[0-9a-f]{64}$'),
    policy_hash                    text        not null check (policy_hash ~ '^[0-9a-f]{64}$'),
    snapshot_id                    uuid,
    origin_operation_id            uuid,
    locale_revision                bigint      not null check (locale_revision >= 0),
    glossary_revision              bigint      not null check (glossary_revision >= 0),
    media_configuration_generation bigint      not null check (media_configuration_generation >= 0),
    due_at                         timestamptz not null,
    expires_at                     timestamptz not null,
    max_attempts                   integer     not null check (max_attempts between 1 and 20),
    attempt_count                  integer     not null default 0 check (attempt_count >= 0),
    regeneration_count             integer     not null default 0 check (regeneration_count between 0 and 20),
    fence                          bigint      not null default 0 check (fence >= 0),
    lease_holder                   uuid,
    lease_expires_at               timestamptz,
    cancel_requested_at            timestamptz,
    estimated_usage                bigint      not null check (estimated_usage >= 0),
    reserved_usage                 bigint      not null check (reserved_usage >= 0),
    observed_usage                 bigint      not null default 0 check (observed_usage >= 0),
    result_digest                  text        check (result_digest ~ '^[0-9a-f]{64}$'),
    failure_code                   text        check (failure_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    created_at                     timestamptz not null,
    updated_at                     timestamptz not null,
    row_version                    bigint      not null default 0 check (row_version >= 0),
    constraint ai_job_campaign_fk foreign key (adventure_id, campaign_id)
        references campaign (adventure_id, id) on delete restrict,
    constraint ai_job_campaign_uk unique (campaign_id, id),
    -- Duplicate requests (including a replayed outbox intent) resolve to one job.
    constraint ai_job_dedup_uk unique (campaign_id, dedup_key),
    constraint ai_job_snapshot_fk foreign key (campaign_id, snapshot_id)
        references planning_snapshot (campaign_id, id) on delete restrict,
    constraint ai_job_origin_fk foreign key (campaign_id, origin_operation_id)
        references operation_journal (campaign_id, id) on delete restrict,
    constraint ai_job_window_ck check (expires_at > due_at),
    constraint ai_job_attempts_ck check (attempt_count <= max_attempts),
    -- Exactly the lease-bearing states carry a complete lease; a lease needs a fence.
    constraint ai_job_lease_ck check (
        (status in ('LEASED', 'RUNNING', 'CANCEL_REQUESTED', 'RESULT_RECEIVED', 'VALIDATING')
            and lease_holder is not null and lease_expires_at is not null and fence > 0)
        or (status not in ('LEASED', 'RUNNING', 'CANCEL_REQUESTED', 'RESULT_RECEIVED', 'VALIDATING')
            and lease_holder is null and lease_expires_at is null)),
    -- Critical jobs are created inside their originating canonical transaction.
    constraint ai_job_critical_origin_ck check (not critical or origin_operation_id is not null),
    constraint ai_job_completed_ck check (status <> 'COMPLETED' or (result_digest is not null and snapshot_id is not null))
);
-- Claim scan: highest priority first, oldest due time next, identity breaks ties.
create index ai_job_claim_idx on ai_job (priority desc, due_at, id) where status = 'QUEUED';
-- Recovery scan over unfinished jobs only.
create index ai_job_unfinished_idx on ai_job (campaign_id, id)
    where status not in ('COMPLETED', 'SUPERSEDED', 'CANCELLED', 'FAILED', 'EXPIRED');

-- Critical preparation is an outbox effect of a committed command. Defer the check
-- until COMMIT so ORM flush ordering cannot expose a half-built canonical bundle.
-- Input is the inserted job; output is ignored; a missing committed origin aborts
-- the whole transaction, including world state and its event/receipt/outbox rows.
create function require_committed_job_origin() returns trigger language plpgsql as $$
begin
    if new.critical and not exists (select 1 from operation_journal
            where id = new.origin_operation_id and campaign_id = new.campaign_id and committed_revision is not null) then
        raise exception using errcode = 'check_violation', constraint = 'ai_job_committed_origin',
            message = 'ai_job_committed_origin: critical job requires a committed origin';
    end if;
    return null;
end;
$$;
create constraint trigger ai_job_committed_origin after insert on ai_job
    deferrable initially deferred for each row execute function require_committed_job_origin();

-- Input: old/new job rows. Output: the checked new row. Terminal states are final.
-- Status changes follow the docs/28 lifecycle map; a same-status update is allowed
-- for heartbeats and snapshot binding. A claim (entering LEASED) consumes exactly one
-- attempt and one fence; any holder change also needs a fresh fence, as in V5.
create function guard_ai_job() returns trigger language plpgsql as $$
begin
    if old.status in ('COMPLETED', 'SUPERSEDED', 'CANCELLED', 'FAILED', 'EXPIRED') then
        raise exception using errcode = 'check_violation', constraint = 'ai_job_terminal',
            message = format('ai_job_terminal: %s is terminal', old.status);
    end if;
    if new.status is distinct from old.status and not (new.status = any (case old.status
            when 'QUEUED' then array['LEASED', 'CANCELLED', 'EXPIRED', 'SUPERSEDED']
            when 'LEASED' then array['RUNNING', 'QUEUED', 'CANCEL_REQUESTED', 'RECONCILIATION_REQUIRED', 'FAILED']
            when 'RUNNING' then array['RESULT_RECEIVED', 'CANCEL_REQUESTED', 'QUEUED', 'FAILED',
                                      'RECONCILIATION_REQUIRED']
            when 'CANCEL_REQUESTED' then array['CANCELLED', 'RECONCILIATION_REQUIRED']
            when 'RESULT_RECEIVED' then array['VALIDATING', 'CANCEL_REQUESTED', 'RECONCILIATION_REQUIRED']
            when 'VALIDATING' then array['COMPLETED', 'FAILED', 'SUPERSEDED', 'QUEUED', 'RECONCILIATION_REQUIRED']
            when 'RECONCILIATION_REQUIRED' then array['QUEUED', 'FAILED', 'CANCELLED', 'EXPIRED', 'SUPERSEDED']
            else array[]::text[] end)) then
        raise exception using errcode = 'check_violation', constraint = 'ai_job_transition',
            message = format('ai_job_transition: %s cannot become %s', old.status, new.status);
    end if;
    -- Numeric arithmetic keeps the fence rule fail-closed at BIGINT's upper bound.
    if new.fence::numeric not between old.fence::numeric and old.fence::numeric + 1
            or (new.lease_holder is distinct from old.lease_holder and new.fence::numeric <> old.fence::numeric + 1)
            or ((new.status = 'LEASED' and old.status <> 'LEASED')
                <> (new.attempt_count::numeric = old.attempt_count::numeric + 1))
            or ((new.status = 'LEASED' and old.status <> 'LEASED')
                and new.fence::numeric <> old.fence::numeric + 1)
            or (new.attempt_count <> old.attempt_count and not (new.status = 'LEASED' and old.status <> 'LEASED')) then
        raise exception using errcode = 'check_violation', constraint = 'ai_job_fence',
            message = 'ai_job_fence: invalid job fence or attempt transition';
    end if;
    -- The current snapshot is bound by the active attempt only; once an attempt
    -- reaches RESULT_RECEIVED its input is fixed until the job is released.
    if new.snapshot_id is distinct from old.snapshot_id
            and not (old.status in ('LEASED', 'RUNNING') and new.status = old.status and new.snapshot_id is not null) then
        raise exception using errcode = 'check_violation', constraint = 'ai_job_snapshot',
            message = 'ai_job_snapshot: a snapshot can only be bound by an active attempt';
    end if;
    return new;
end;
$$;

-- Trigger names sort so the terminal/transition guard runs before generic checks.
create trigger ai_job_a_guard before update on ai_job
    for each row execute function guard_ai_job();
create trigger ai_job_frozen before update on ai_job
    for each row execute function reject_frozen_change('ai_job_frozen', 'id', 'adventure_id', 'campaign_id',
        'campaign_generation', 'kind', 'target', 'purpose', 'contract_version', 'dependency_fingerprint',
        'dedup_key', 'priority', 'critical', 'payload_hash', 'schema_hash', 'policy_hash', 'origin_operation_id',
        'locale_revision', 'glossary_revision', 'media_configuration_generation', 'due_at', 'expires_at',
        'max_attempts', 'estimated_usage', 'created_at');
create trigger ai_job_monotonic before update on ai_job
    for each row execute function reject_counter_decrease('ai_job_monotonic', 'regeneration_count',
        'reserved_usage', 'observed_usage');
create trigger ai_job_no_delete before delete on ai_job
    for each row execute function reject_mutation();
create trigger ai_job_row_version before update on ai_job
    for each row execute function require_row_version_step();
create trigger ai_job_write_once before update on ai_job
    for each row execute function reject_rewrite('ai_job_write_once', 'cancel_requested_at', 'result_digest',
        'failure_code');

-- Durable history of each lease of a job. Identity and lease columns are fixed; the
-- runtime references, result and outcome may be written once, so a duplicate or late
-- delivery cannot replace what an earlier attempt recorded.
create table ai_job_attempt (
    job_id             uuid        not null,
    attempt_number     integer     not null check (attempt_number between 1 and 20),
    campaign_id        uuid        not null,
    fence              bigint      not null check (fence > 0),
    lease_holder       uuid        not null,
    snapshot_id        uuid,
    runtime_generation bigint      check (runtime_generation >= 0),
    runtime_thread_ref text        check (char_length(runtime_thread_ref) between 1 and 128),
    runtime_turn_ref   text        check (char_length(runtime_turn_ref) between 1 and 128),
    started_at         timestamptz not null,
    outcome            text        check (outcome in ('ACCEPTED', 'REJECTED', 'FAILED', 'CANCELLED', 'INTERRUPTED')),
    result_digest      text        check (result_digest ~ '^[0-9a-f]{64}$'),
    observed_usage     bigint      check (observed_usage >= 0),
    failure_code       text        check (failure_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    finished_at        timestamptz,
    primary key (job_id, attempt_number),
    constraint ai_job_attempt_job_fk foreign key (campaign_id, job_id)
        references ai_job (campaign_id, id) on delete restrict,
    constraint ai_job_attempt_campaign_uk unique (campaign_id, job_id, attempt_number),
    constraint ai_job_attempt_snapshot_fk foreign key (campaign_id, snapshot_id)
        references planning_snapshot (campaign_id, id) on delete restrict,
    constraint ai_job_attempt_finish_ck check ((outcome is null) = (finished_at is null)),
    constraint ai_job_attempt_accepted_ck check (outcome is distinct from 'ACCEPTED' or result_digest is not null)
);
create trigger ai_job_attempt_frozen before update on ai_job_attempt
    for each row execute function reject_frozen_change('ai_job_attempt_frozen', 'job_id', 'attempt_number',
        'campaign_id', 'fence', 'lease_holder', 'started_at');
create trigger ai_job_attempt_no_delete before delete on ai_job_attempt
    for each row execute function reject_mutation();
create trigger ai_job_attempt_write_once before update on ai_job_attempt
    for each row execute function reject_rewrite('ai_job_attempt_write_once', 'snapshot_id', 'runtime_generation',
        'runtime_thread_ref', 'runtime_turn_ref', 'outcome', 'result_digest', 'observed_usage', 'failure_code',
        'finished_at');

-- Immutable admitted plan version. Version 1 has no predecessor; every later version
-- names exactly the previous one, so a plan history is a single verifiable chain.
-- At most one artifact exists per job attempt, so a duplicate result cannot branch.
create table quest_plan_artifact (
    id                     uuid        primary key,
    campaign_id            uuid        not null,
    plan_id                uuid        not null,
    plan_version           integer     not null check (plan_version between 1 and 10000),
    predecessor_version    integer,
    quest_target           text        not null check (char_length(quest_target) between 1 and 256),
    contract_version       text        not null check (contract_version ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    snapshot_id            uuid        not null,
    body                   jsonb       not null check (jsonb_typeof(body) = 'object' and octet_length(body::text) <= 65536),
    dependency_fingerprint text        not null check (dependency_fingerprint ~ '^[0-9a-f]{64}$'),
    job_id                 uuid        not null,
    attempt_number         integer     not null,
    created_at             timestamptz not null,
    constraint quest_plan_artifact_campaign_uk unique (campaign_id, id),
    constraint quest_plan_artifact_version_uk unique (campaign_id, plan_id, plan_version),
    constraint quest_plan_artifact_attempt_uk unique (job_id, attempt_number),
    constraint quest_plan_artifact_predecessor_ck check (
        (plan_version = 1 and predecessor_version is null)
        or (plan_version > 1 and predecessor_version is not null and predecessor_version = plan_version - 1)),
    constraint quest_plan_artifact_predecessor_fk foreign key (campaign_id, plan_id, predecessor_version)
        references quest_plan_artifact (campaign_id, plan_id, plan_version) on delete restrict,
    constraint quest_plan_artifact_snapshot_fk foreign key (campaign_id, snapshot_id)
        references planning_snapshot (campaign_id, id) on delete restrict,
    constraint quest_plan_artifact_attempt_fk foreign key (campaign_id, job_id, attempt_number)
        references ai_job_attempt (campaign_id, job_id, attempt_number) on delete restrict
);
create trigger quest_plan_artifact_immutable before update or delete on quest_plan_artifact
    for each row execute function reject_mutation();

-- Typed dependency of one artifact. QUERY rows record the query definition reference,
-- parameters (predicate), audience, scope, epoch read and result digest; the other
-- kinds record an expected version and/or predicate. Severity says whether a change
-- invalidates only the listed branches or the whole artifact.
create table plan_dependency (
    artifact_id      uuid    not null,
    ordinal          integer not null check (ordinal between 0 and 511),
    campaign_id      uuid    not null,
    kind             text    not null check (kind in ('ENTITY_FIELD', 'FACT', 'QUERY', 'CONSTRAINT')),
    reference        text    not null check (char_length(reference) between 1 and 256),
    expected_version bigint  check (expected_version >= 0),
    predicate        jsonb   check (jsonb_typeof(predicate) = 'object' and octet_length(predicate::text) <= 4096),
    audience         text    check (audience in ('PLANNER_PRIVATE', 'PUBLIC')),
    scope_key        text    check (scope_key ~ '^[a-z][a-z0-9_.:-]{0,127}$'),
    query_epoch      bigint  check (query_epoch >= 0),
    result_digest    text    check (result_digest ~ '^[0-9a-f]{64}$'),
    severity         text    not null check (severity in ('BRANCH', 'ARTIFACT')),
    branch_refs      jsonb   not null check (jsonb_typeof(branch_refs) = 'array'
                                             and octet_length(branch_refs::text) <= 4096),
    primary key (artifact_id, ordinal),
    constraint plan_dependency_artifact_fk foreign key (campaign_id, artifact_id)
        references quest_plan_artifact (campaign_id, id) on delete restrict,
    constraint plan_dependency_shape_ck check (
        (kind = 'QUERY' and audience is not null and scope_key is not null and query_epoch is not null
            and result_digest is not null and expected_version is null)
        or (kind <> 'QUERY' and audience is null and scope_key is null and query_epoch is null
            and result_digest is null and (expected_version is not null or predicate is not null)))
);
create index plan_dependency_scope_idx on plan_dependency (campaign_id, scope_key) where kind = 'QUERY';
create trigger plan_dependency_immutable before update or delete on plan_dependency
    for each row execute function reject_mutation();

-- Immutable validation receipt. Unique artifact and attempt keys give at most one
-- accepted artifact version per attempt, whatever the worker delivery count.
create table plan_admission (
    id                     uuid        primary key,
    campaign_id            uuid        not null,
    artifact_id            uuid        not null,
    job_id                 uuid        not null,
    attempt_number         integer     not null,
    validator_version      text        not null check (validator_version ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    checked_revision       bigint      not null check (checked_revision >= 0),
    campaign_generation    bigint      not null check (campaign_generation >= 0),
    dependency_fingerprint text        not null check (dependency_fingerprint ~ '^[0-9a-f]{64}$'),
    accepted_branches      jsonb       not null check (jsonb_typeof(accepted_branches) = 'array'
                                                       and octet_length(accepted_branches::text) <= 8192),
    rejected_branches      jsonb       not null check (jsonb_typeof(rejected_branches) = 'array'
                                                       and octet_length(rejected_branches::text) <= 8192),
    reasons                jsonb       not null check (jsonb_typeof(reasons) = 'array'
                                                       and octet_length(reasons::text) <= 8192),
    source_hash            text        not null check (source_hash ~ '^[0-9a-f]{64}$'),
    result_hash            text        not null check (result_hash ~ '^[0-9a-f]{64}$'),
    admission_key          text        not null check (admission_key ~ '^[0-9a-f]{64}$'),
    created_at             timestamptz not null,
    constraint plan_admission_artifact_uk unique (artifact_id),
    constraint plan_admission_attempt_uk unique (job_id, attempt_number),
    constraint plan_admission_key_uk unique (campaign_id, admission_key),
    constraint plan_admission_artifact_fk foreign key (campaign_id, artifact_id)
        references quest_plan_artifact (campaign_id, id) on delete restrict,
    constraint plan_admission_attempt_fk foreign key (campaign_id, job_id, attempt_number)
        references ai_job_attempt (campaign_id, job_id, attempt_number) on delete restrict
);
create trigger plan_admission_immutable before update or delete on plan_admission
    for each row execute function reject_mutation();

-- Append-only plan lifecycle; the highest sequence is the artifact's current status.
-- A validated artifact is eligible preparation, never canonical truth.
create table plan_disposition (
    artifact_id      uuid        not null,
    sequence         integer     not null check (sequence between 1 and 10000),
    campaign_id      uuid        not null,
    status           text        not null check (status in (
        'PROPOSED', 'VALIDATED', 'PARTIALLY_INVALIDATED', 'INVALIDATED', 'SUPERSEDED', 'REJECTED')),
    reason_code      text        not null check (reason_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    checked_revision bigint      not null check (checked_revision >= 0),
    created_at       timestamptz not null,
    primary key (artifact_id, sequence),
    constraint plan_disposition_artifact_fk foreign key (campaign_id, artifact_id)
        references quest_plan_artifact (campaign_id, id) on delete restrict
);
create trigger plan_disposition_immutable before update or delete on plan_disposition
    for each row execute function reject_mutation();
