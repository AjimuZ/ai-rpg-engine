-- P08.6: durable ledger of the fair inference scheduler (docs/06 section 12, docs/28
-- section 9). One row per task the scheduler admitted, from admission to its terminal
-- outcome, plus one installation-wide row of account backoff state. None of these rows is
-- canonical world state: writing them never advances state_revision, game_turn, world
-- time, the event sequence, a resource, a receipt or a draw.
--
-- The ledger stores identities, scope, class, timing, outcome codes and usage estimates
-- only. Instructions, contexts, schemas and outputs are never stored: queued work is not
-- resumed after a restart (its owning durable job re-requests it with a fresh context),
-- and captured runtime requests are never replayed (docs/28 section 10).

-- 1. Scheduled inference tasks.
--
-- Lifecycle: QUEUED (admitted, waiting for the turn permit) -> DISPATCHED (forwarded to
-- the runtime port; holds the permit) -> a terminal outcome, or -> RECONCILING when the
-- runtime could not confirm the outcome (the permit stays held until the port confirms
-- that nothing runs any more) -> UNRESOLVED. QUEUED work can also end without ever
-- reaching the runtime: cancelled, timed out in the queue, refused at dispatch, runtime
-- unavailable, or ABANDONED by the startup pass of a later process.
create table inference_task (
    id                  uuid        primary key,
    adventure_id        uuid        not null,
    campaign_id         uuid        not null,
    campaign_generation bigint      not null check (campaign_generation >= 0),
    role                text        not null check (role ~ '^[a-z][a-z-]{0,31}$'),
    audience            text        not null check (audience ~ '^(public|privileged|character:[A-Za-z0-9._:-]{1,128})$'),
    work_class          text        not null check (work_class in ('INTERACTIVE', 'CONTINUITY', 'PREPARATION',
                                                                   'SPECULATIVE')),
    slot                text        not null check (slot in ('light', 'standard', 'deliberate')),
    status              text        not null check (status in ('QUEUED', 'DISPATCHED', 'RECONCILING', 'COMPLETED',
                                                               'CANCELLED', 'FAILED', 'TIMED_OUT', 'UNRESOLVED',
                                                               'UNAVAILABLE', 'REJECTED', 'ABANDONED')),
    outcome_code        text        check (outcome_code ~ '^[a-z][A-Za-z0-9-]{0,63}$'),
    -- Scheduler process that owns the row. Each process start draws a fresh identity, so
    -- a row owned by another identity belongs to an earlier (or foreign) process; only the
    -- owner may dispatch or settle it.
    instance_id         uuid        not null,
    -- Estimated usage units (input bytes / 4 plus a per-slot output allowance) reserved for
    -- the task; charged_usage is what counts against the rolling quotas: the reservation
    -- once dispatched, released to zero only when the runtime reports that no turn started.
    estimated_usage     bigint      not null check (estimated_usage between 1 and 1000000),
    charged_usage       bigint      not null default 0 check (charged_usage >= 0),
    -- Opaque runtime reference recorded before the runtime turn starts (write-ahead), so a
    -- later process can ask the runtime to confirm that nothing still runs under it.
    -- Internal only: never projected, exported or shown.
    runtime_ref         text        check (char_length(runtime_ref) between 1 and 256 and runtime_ref !~ '[[:cntrl:]]'),
    preempted           boolean     not null default false,
    enqueued_at         timestamptz not null,
    deadline_at         timestamptz not null,
    dispatched_at       timestamptz,
    finished_at         timestamptz,
    row_version         bigint      not null default 0 check (row_version >= 0),
    -- Admission requires the campaign to belong to the adventure named by the scope.
    constraint inference_task_campaign_fk foreign key (adventure_id, campaign_id)
        references campaign (adventure_id, id) on delete restrict,
    constraint inference_task_deadline_ck check (deadline_at > enqueued_at),
    constraint inference_task_state_ck check (
        (status = 'QUEUED' and dispatched_at is null and finished_at is null and outcome_code is null
            and charged_usage = 0 and runtime_ref is null and not preempted)
        or (status = 'DISPATCHED' and dispatched_at is not null and finished_at is null and outcome_code is null)
        or (status = 'RECONCILING' and dispatched_at is not null and finished_at is null and outcome_code is not null)
        or (status not in ('QUEUED', 'DISPATCHED', 'RECONCILING') and finished_at is not null
            and (status = 'COMPLETED') = (outcome_code is null))),
    -- Only dispatched work can be charged, carry a runtime reference or be preempted.
    constraint inference_task_dispatch_ck check (
        dispatched_at is not null or (charged_usage = 0 and runtime_ref is null and not preempted)),
    constraint inference_task_charge_ck check (charged_usage in (0, estimated_usage))
);

-- The durable form of the qualified concurrency limit: at most one task installation-wide
-- holds the runtime turn permit (dispatched or awaiting reconciliation), whatever the
-- number of scheduler processes sharing this database (P01.7 selected serialized
-- inference; any higher limit needs the P08.6/QA-37 qualification and a migration that
-- replaces this index with a permit table).
create unique index inference_task_one_turn_uk on inference_task ((true))
    where status in ('DISPATCHED', 'RECONCILING');
-- Rolling usage windows read charged rows by dispatch time.
create index inference_task_usage_idx on inference_task (dispatched_at) where charged_usage > 0;
-- Startup reconciliation reads the unfinished rows only.
create index inference_task_open_idx on inference_task (instance_id, status)
    where status in ('QUEUED', 'DISPATCHED', 'RECONCILING');

-- Input: old/new task rows. Output: the checked new row. Terminal rows are final.
-- Allowed moves:
--   QUEUED      -> DISPATCHED (sets the charge to the estimate), CANCELLED, TIMED_OUT,
--                  REJECTED, UNAVAILABLE or ABANDONED (no charge);
--   DISPATCHED  -> DISPATCHED (records the runtime reference once or marks preemption once),
--                  RECONCILING, or a terminal outcome other than UNRESOLVED/ABANDONED, keeping
--                  the charge or releasing it to zero;
--   RECONCILING -> RECONCILING (a later process takes over the hold) or UNRESOLVED.
-- UNRESOLVED is reachable only through RECONCILING, so an unconfirmed turn always keeps the
-- permit until the runtime confirmed that nothing runs. Only a move into RECONCILING may
-- change the owning process.
create function guard_inference_task() returns trigger language plpgsql as $$
begin
    if old.status not in ('QUEUED', 'DISPATCHED', 'RECONCILING') then
        raise exception using errcode = 'check_violation', constraint = 'inference_task_terminal',
            message = format('inference_task_terminal: %s is terminal', old.status);
    end if;
    if not (new.status = any (case old.status
            when 'QUEUED' then array['DISPATCHED', 'CANCELLED', 'TIMED_OUT', 'REJECTED', 'UNAVAILABLE', 'ABANDONED']
            when 'DISPATCHED' then array['DISPATCHED', 'RECONCILING', 'COMPLETED', 'CANCELLED', 'FAILED', 'TIMED_OUT',
                                         'UNAVAILABLE', 'REJECTED']
            else array['RECONCILING', 'UNRESOLVED'] end)) then
        raise exception using errcode = 'check_violation', constraint = 'inference_task_transition',
            message = format('inference_task_transition: %s cannot become %s', old.status, new.status);
    end if;
    if new.instance_id is distinct from old.instance_id and new.status <> 'RECONCILING' then
        raise exception using errcode = 'check_violation', constraint = 'inference_task_owner',
            message = 'inference_task_owner: only a reconciliation takeover changes the owner';
    end if;
    if old.status = 'QUEUED' and new.status = 'DISPATCHED' then
        if new.charged_usage <> new.estimated_usage then
            raise exception using errcode = 'check_violation', constraint = 'inference_task_charge',
                message = 'inference_task_charge: dispatch reserves exactly the estimate';
        end if;
    elsif old.status = 'DISPATCHED' and new.status not in ('DISPATCHED', 'RECONCILING') then
        if new.charged_usage not in (0, old.charged_usage) then
            raise exception using errcode = 'check_violation', constraint = 'inference_task_charge',
                message = 'inference_task_charge: settlement keeps or releases the reservation';
        end if;
    elsif new.charged_usage <> old.charged_usage then
        raise exception using errcode = 'check_violation', constraint = 'inference_task_charge',
            message = 'inference_task_charge: the charge changes only at dispatch and settlement';
    end if;
    if old.preempted and not new.preempted then
        raise exception using errcode = 'check_violation', constraint = 'inference_task_preempted',
            message = 'inference_task_preempted: a preemption mark is permanent';
    end if;
    if new.preempted and not old.preempted and not (old.status = 'DISPATCHED' and new.status = 'DISPATCHED') then
        raise exception using errcode = 'check_violation', constraint = 'inference_task_preempted',
            message = 'inference_task_preempted: only a dispatched task is preempted';
    end if;
    if new.runtime_ref is distinct from old.runtime_ref
            and not (old.runtime_ref is null and old.status = 'DISPATCHED' and new.status = 'DISPATCHED') then
        raise exception using errcode = 'check_violation', constraint = 'inference_task_reference',
            message = 'inference_task_reference: recorded once, before the runtime turn starts';
    end if;
    return new;
end;
$$;
-- Trigger names sort so the lifecycle guard runs before the generic checks.
create trigger inference_task_a_guard before update on inference_task
    for each row execute function guard_inference_task();
create trigger inference_task_frozen before update on inference_task
    for each row execute function reject_frozen_change('inference_task_frozen', 'id', 'adventure_id', 'campaign_id',
        'campaign_generation', 'role', 'audience', 'work_class', 'slot', 'estimated_usage', 'enqueued_at',
        'deadline_at');
create trigger inference_task_write_once before update on inference_task
    for each row execute function reject_rewrite('inference_task_write_once', 'outcome_code', 'dispatched_at',
        'finished_at');
create trigger inference_task_row_version before update on inference_task
    for each row execute function require_row_version_step();
create trigger inference_task_no_delete before delete on inference_task
    for each row execute function reject_mutation();

-- 2. Account backoff. One installation-wide row (created on first use): after an account
-- condition (usage limit, rate limit, overload, authorization) the scheduler suspends
-- background admissions until suspended_until; consecutive_suspensions drives the bounded
-- exponential backoff and resets after a completed task. Durable so a restart neither
-- forgets an active suspension nor resets the backoff.
create table inference_scheduler_state (
    id                      smallint    primary key check (id = 1),
    suspended_until         timestamptz,
    suspension_code         text        check (suspension_code ~ '^[a-z][a-z0-9-]{0,63}$'),
    consecutive_suspensions integer     not null check (consecutive_suspensions between 0 and 64),
    updated_at              timestamptz not null,
    row_version             bigint      not null default 0 check (row_version >= 0),
    constraint inference_scheduler_state_suspension_ck check ((suspended_until is null) = (suspension_code is null))
);
create trigger inference_scheduler_state_row_version before update on inference_scheduler_state
    for each row execute function require_row_version_step();
