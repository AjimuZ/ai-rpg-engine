-- P09.3: operational checkpoints, source-linked continuity summaries, explicit summary
-- invalidations and the bounded background jobs that produce summaries (docs/08 sections 3, 8
-- and 10, ADR 0005). None of these rows is canonical world state: writing them never advances
-- state_revision, game_turn, world time or the event sequence, and a missing or failed summary
-- never blocks a command. Summaries are derived retrieval aids; current structured state always
-- wins over them during context assembly.

-- Deterministic operational snapshot of one campaign, built from canonical rows only. The
-- result is a jsonb value, so its text form (PostgreSQL's canonical jsonb output: keys ordered,
-- whitespace normalized) is the serialization that campaign_checkpoint.snapshot_hash digests.
-- Equal canonical state therefore always yields an equal snapshot and hash, whichever process
-- captured it. The snapshot is privileged backend data (it names every actor and the real
-- locations); it is never rendered into a limited-audience context. RNG cursors are recorded as
-- the algorithm and the number of recorded draws, never the seed. There is no encounter state
-- before P11, so "encounter" is always null for now; the key is kept so the layout is stable.
create function memory_operational_snapshot(target uuid) returns jsonb
    language sql stable as $$
select jsonb_build_object(
    'kind', 'operational',
    'campaign', jsonb_build_object(
        'adventureId', c.adventure_id,
        'contentRevisionId', c.content_revision_id,
        'mode', c.mode,
        'pendingDecision', c.pending_decision,
        'stateRevision', c.state_revision,
        'gameTurn', c.game_turn,
        'worldTimeSeconds', c.world_time_seconds,
        'eventSequence', c.event_sequence),
    'playerActorId', (select actor_id from campaign_creation where campaign_id = c.id),
    'actors', (select coalesce(jsonb_agg(jsonb_build_object('id', a.id, 'kind', a.kind,
            'definitionId', a.definition_id, 'name', a.display_name, 'location', a.location_id,
            'rowVersion', a.row_version) order by a.id), '[]'::jsonb)
        from actor a where a.campaign_id = c.id),
    'resources', (select coalesce(jsonb_agg(jsonb_build_object('actorId', r.actor_id, 'key', r.resource_key,
            'minimum', r.minimum_value, 'current', r.current_value, 'maximum', r.maximum_value)
            order by r.actor_id, r.resource_key), '[]'::jsonb)
        from actor_resource r where r.campaign_id = c.id),
    'items', (select coalesce(jsonb_agg(jsonb_build_object('id', i.id, 'actorId', i.actor_id,
            'definitionId', i.definition_id, 'quantity', i.quantity) order by i.id), '[]'::jsonb)
        from item_instance i where i.campaign_id = c.id),
    'abilities', (select coalesce(jsonb_agg(jsonb_build_object('actorId', l.actor_id,
            'definitionId', l.definition_id, 'rank', l.rank) order by l.actor_id, l.definition_id), '[]'::jsonb)
        from learned_ability l where l.campaign_id = c.id),
    'quests', (select coalesce(jsonb_agg(jsonb_build_object('id', q.id, 'actorId', q.actor_id,
            'status', q.status, 'updatedRevision', q.updated_revision) order by q.id), '[]'::jsonb)
        from campaign_known_quest q where q.campaign_id = c.id),
    'openCommitments', (select coalesce(jsonb_agg(jsonb_build_object('id', m.id, 'kind', m.kind,
            'debtorId', m.debtor_actor_id, 'creditorId', m.creditor_actor_id, 'dueWorldTime', m.due_world_time)
            order by m.id), '[]'::jsonb)
        from actor_commitment m where m.campaign_id = c.id and m.status = 'OPEN'),
    'pendingNarration', (select coalesce(jsonb_agg(jsonb_build_object('operationId', o.id,
            'status', o.status, 'committedRevision', o.committed_revision)
            order by o.committed_revision, o.id), '[]'::jsonb)
        from operation_journal o where o.campaign_id = c.id
            and o.status in ('COMMITTED', 'NARRATING', 'COMMITTED_NARRATION_PENDING')),
    'rng', (select jsonb_build_object('algorithm', s.algorithm, 'algorithmVersion', s.algorithm_version,
            'draws', (select count(*) from operation_random_draw d where d.campaign_id = c.id))
        from campaign_random_seed s where s.campaign_id = c.id),
    'encounter', null)
from campaign c where c.id = target
$$;

-- One summary job: summarize exactly one audience's public events in [range_from, range_to] at
-- the campaign generation it was requested under. Bounds: at most one unfinished job per campaign
-- and audience (partial unique index below), max_attempts 1..5, a finite due/expiry window and a
-- fenced lease. A retry is the same row with attempt_count + 1, so a new runtime thread never
-- resets attempts. The installation-wide cap on unfinished jobs is enforced by the service under
-- an advisory lock, because a count is not expressible as a constraint.
create table memory_summary_job (
    id                  uuid        primary key,
    adventure_id        uuid        not null,
    campaign_id         uuid        not null,
    campaign_generation bigint      not null check (campaign_generation >= 0),
    audience            text        not null check (audience in ('public', 'privileged')),
    range_from          bigint      not null check (range_from > 0),
    range_to            bigint      not null,
    status              text        not null check (status in ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    attempt_count       integer     not null default 0 check (attempt_count >= 0),
    max_attempts        integer     not null check (max_attempts between 1 and 5),
    fence               bigint      not null default 0 check (fence >= 0),
    lease_holder        uuid,
    lease_expires_at    timestamptz,
    due_at              timestamptz not null,
    expires_at          timestamptz not null,
    failure_code        text        check (failure_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    summary_id          uuid,
    created_at          timestamptz not null,
    updated_at          timestamptz not null,
    row_version         bigint      not null default 0 check (row_version >= 0),
    constraint memory_summary_job_campaign_fk foreign key (adventure_id, campaign_id)
        references campaign (adventure_id, id) on delete restrict,
    constraint memory_summary_job_campaign_uk unique (campaign_id, id),
    constraint memory_summary_job_range_ck check (range_to >= range_from),
    constraint memory_summary_job_window_ck check (expires_at > due_at),
    constraint memory_summary_job_attempts_ck check (attempt_count <= max_attempts),
    -- Exactly a running job holds a complete lease, and a lease always has a fresh fence.
    constraint memory_summary_job_lease_ck check (
        (status = 'RUNNING' and lease_holder is not null and lease_expires_at is not null and fence > 0)
        or (status <> 'RUNNING' and lease_holder is null and lease_expires_at is null)),
    constraint memory_summary_job_completed_ck check ((status = 'COMPLETED') = (summary_id is not null)),
    -- A terminal failure names its code; a queued or running retry keeps its previous attempt's
    -- code for diagnostics; a completed job has none.
    constraint memory_summary_job_failure_ck check (
        (status not in ('FAILED', 'CANCELLED') or failure_code is not null)
        and (status <> 'COMPLETED' or failure_code is null))
);
create unique index memory_summary_job_pending_uk on memory_summary_job (campaign_id, audience)
    where status in ('QUEUED', 'RUNNING');
create index memory_summary_job_claim_idx on memory_summary_job (due_at, id) where status = 'QUEUED';
create index memory_summary_job_recovery_idx on memory_summary_job (lease_expires_at) where status = 'RUNNING';

-- Terminal jobs never change again, and identity and range are fixed at insert.
create function guard_memory_summary_job() returns trigger language plpgsql as $$
begin
    if old.status in ('COMPLETED', 'FAILED', 'CANCELLED') then
        raise exception using errcode = 'check_violation', constraint = 'memory_summary_job_terminal',
            message = format('memory_summary_job_terminal: %s is terminal', old.status);
    end if;
    return new;
end;
$$;
create trigger memory_summary_job_terminal before update on memory_summary_job
    for each row execute function guard_memory_summary_job();
create trigger memory_summary_job_frozen before update on memory_summary_job
    for each row execute function reject_frozen_change('memory_summary_job_frozen', 'id', 'adventure_id',
        'campaign_id', 'campaign_generation', 'audience', 'range_from', 'range_to', 'max_attempts', 'created_at');
create trigger memory_summary_job_monotonic before update on memory_summary_job
    for each row execute function reject_counter_decrease('memory_summary_job_monotonic', 'fence', 'attempt_count');
create trigger memory_summary_job_row_version before update on memory_summary_job
    for each row execute function require_row_version_step();
create trigger memory_summary_job_no_delete before delete on memory_summary_job
    for each row execute function reject_mutation();

-- One accepted continuity summary (docs/21 section 10). covered_events is the exact, sorted set
-- of event sequences that were supplied to the summarizer and that the output claims; it may be a
-- subset of the job's requested range when the budget dropped events, and it never claims more.
-- sources lists every knowledge record supplied as {"ref": "<kind>:<uuid>", "version": n}; a later
-- row_version change of any of them (supersession, determination, resolution) makes the summary
-- stale. invalidation_high_water is the newest memory_invalidation sequence of the campaign when
-- the summary was recorded under the campaign lock; later invalidations naming its events, sources
-- or entities make it stale. Rows are immutable: staleness is computed, never written.
create table memory_summary (
    id                      uuid        primary key,
    campaign_id             uuid        not null references campaign (id) on delete restrict,
    job_id                  uuid        not null,
    campaign_generation     bigint      not null check (campaign_generation >= 0),
    audience                text        not null check (audience in ('public', 'privileged')),
    kind                    text        not null check (kind in ('CONTINUITY')),
    covered_from            bigint      not null check (covered_from > 0),
    covered_to              bigint      not null,
    covered_events          bigint[]    not null check (cardinality(covered_events) between 1 and 64
                                                        and array_ndims(covered_events) = 1),
    source_revision         bigint      not null check (source_revision >= 0),
    event_high_water        bigint      not null check (event_high_water >= 0),
    locale                  text        not null check (locale in ('en', 'it')),
    locale_revision         bigint      not null check (locale_revision >= 0),
    glossary_revision       bigint      not null check (glossary_revision >= 0),
    invalidation_high_water bigint      not null check (invalidation_high_water >= 0),
    context_digest          text        not null check (context_digest ~ '^[0-9a-f]{64}$'),
    template_hash           text        not null check (template_hash ~ '^[0-9a-f]{64}$'),
    sources                 jsonb       not null check (jsonb_typeof(sources) = 'array'
                                                        and octet_length(sources::text) <= 32768),
    entity_refs             jsonb       not null check (jsonb_typeof(entity_refs) = 'array'
                                                        and octet_length(entity_refs::text) <= 16384),
    body                    jsonb       not null check (jsonb_typeof(body) = 'object'
                                                        and octet_length(body::text) <= 16384),
    body_digest             text        not null check (body_digest ~ '^[0-9a-f]{64}$'),
    created_at              timestamptz not null,
    constraint memory_summary_campaign_uk unique (campaign_id, id),
    constraint memory_summary_job_uk unique (job_id),
    constraint memory_summary_job_fk foreign key (campaign_id, job_id)
        references memory_summary_job (campaign_id, id) on delete restrict,
    constraint memory_summary_range_ck check (covered_to >= covered_from and covered_from = covered_events[1]
        and covered_to = covered_events[cardinality(covered_events)] and covered_to <= event_high_water)
);
create index memory_summary_latest_idx on memory_summary (campaign_id, audience, covered_to desc, created_at desc);
create trigger memory_summary_immutable before update or delete on memory_summary
    for each row execute function reject_mutation();

alter table memory_summary_job add constraint memory_summary_job_summary_fk foreign key (campaign_id, summary_id)
    references memory_summary (campaign_id, id) on delete restrict;

-- Explicit invalidation of derived summaries, for example by an administrative correction
-- (docs/08 section 8: "Corrections mark affected summaries stale"). refs names event:<sequence>,
-- actor:/location:/claim:/belief:/commitment: references; an empty array invalidates every summary
-- of the campaign recorded before it. Append-only; the sequence orders invalidations, and writers
-- take the campaign row lock so sequences become visible in order per campaign.
create table memory_invalidation (
    sequence       bigint      generated always as identity primary key,
    campaign_id    uuid        not null references campaign (id) on delete restrict,
    reason         text        not null check (reason ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    refs           text[]      not null check (cardinality(refs) <= 64 and coalesce(array_ndims(refs), 1) = 1),
    state_revision bigint      not null check (state_revision >= 0),
    created_at     timestamptz not null
);
create index memory_invalidation_campaign_idx on memory_invalidation (campaign_id, sequence);
create trigger memory_invalidation_immutable before update or delete on memory_invalidation
    for each row execute function reject_mutation();
