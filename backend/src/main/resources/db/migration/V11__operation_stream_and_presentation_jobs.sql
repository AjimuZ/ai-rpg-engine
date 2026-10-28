-- P07.5: equivalent-attempt draw streams, the resumable public operation stream and
-- durable post-commit presentation jobs (docs/07 sections 2, 4, 8 and 11; docs/13
-- section 4). None of these rows is canonical world state: writing them never advances
-- state_revision, game_turn, world time, the event sequence or a resource.

-- 1. Equivalent attempts. attempt_key hashes the normalized intention plus its base
-- revision; every fresh admission with the same key in a campaign shares the draw
-- stream of the earliest such attempt, so cancelling or failing an attempt and
-- submitting an equivalent one at the same revision reproduces the same draw values
-- instead of re-rolling. Legacy rows keep both columns null and draw from their own id.
alter table operation_journal
    add column attempt_key text constraint operation_journal_attempt_key_ck check (attempt_key ~ '^[0-9a-f]{64}$'),
    add column draw_stream_id uuid,
    add constraint operation_journal_attempt_ck check ((attempt_key is null) = (draw_stream_id is null)),
    -- A stream is always an operation of the same campaign; a self-reference is the root.
    add constraint operation_journal_draw_stream_fk foreign key (campaign_id, draw_stream_id)
        references operation_journal (campaign_id, id) on delete restrict;
create index operation_journal_attempt_idx on operation_journal (campaign_id, attempt_key, created_at, id)
    where attempt_key is not null;
create trigger operation_journal_attempt_frozen before update on operation_journal
    for each row execute function reject_frozen_change('operation_journal_attempt_frozen', 'attempt_key',
        'draw_stream_id');

-- 2. Public stream. One message per journal status change, written by the trigger
-- below inside the transaction that changed the status, so no writer (admission,
-- execution, cancellation, recovery or presentation) can forget or duplicate one.
-- Messages hold references only; the HTTP adapter renders the allowlisted view from
-- the immutable journal outcome at delivery time. The head row allocates the
-- per-campaign sequence and never decreases, even after retention removes messages.
create table campaign_stream_head (
    campaign_id   uuid   primary key references campaign (id) on delete restrict,
    last_sequence bigint not null check (last_sequence > 0)
);
create trigger campaign_stream_head_monotonic before update on campaign_stream_head
    for each row execute function reject_counter_decrease('campaign_stream_head_monotonic', 'last_sequence');
create trigger campaign_stream_head_no_delete before delete on campaign_stream_head
    for each row execute function reject_mutation();

create table campaign_stream_message (
    campaign_id         uuid        not null references campaign (id) on delete restrict,
    sequence            bigint      not null check (sequence > 0),
    campaign_generation bigint      not null check (campaign_generation >= 0),
    operation_id        uuid        not null,
    operation_version   bigint      not null check (operation_version >= 0),
    message_type        text        not null check (message_type in ('operation.accepted', 'operation.progress',
        'operation.clarification', 'operation.rejected', 'operation.cancelled', 'operation.failed',
        'state.committed', 'narrative.ready', 'narrative.pending')),
    status              text        not null check (char_length(status) between 1 and 64),
    state_revision      bigint      not null check (state_revision >= 0),
    created_at          timestamptz not null,
    primary key (campaign_id, sequence),
    constraint campaign_stream_message_operation_fk foreign key (campaign_id, operation_id)
        references operation_journal (campaign_id, id) on delete restrict
);
-- No uniqueness beyond the sequence: the trigger announces each actual status change exactly
-- once, and the acyclic lifecycle plus the terminal-status guard prevent repeats. Recovery
-- fixtures that inject crash evidence by raw SQL must remain representable.
create index campaign_stream_message_operation_idx on campaign_stream_message (campaign_id, operation_id);
-- Messages are immutable; deletion is reserved for the bounded retention below.
create trigger campaign_stream_message_immutable before update on campaign_stream_message
    for each row execute function reject_mutation();

-- Input: inserted or status-changed journal row. Output: ignored (AFTER trigger).
-- The head upsert serializes concurrent writers of one campaign; production writers
-- already hold the campaign lock first, so this adds no new lock-order edge for them.
-- Retention keeps the newest 1000 messages per campaign; older cursors get a reset.
create function append_operation_stream_message() returns trigger language plpgsql as $$
declare
    next_sequence bigint;
begin
    if tg_op = 'UPDATE' and new.status is not distinct from old.status then
        return null;
    end if;
    insert into campaign_stream_head as h (campaign_id, last_sequence) values (new.campaign_id, 1)
        on conflict (campaign_id) do update set last_sequence = h.last_sequence + 1
        returning last_sequence into next_sequence;
    insert into campaign_stream_message (campaign_id, sequence, campaign_generation, operation_id,
            operation_version, message_type, status, state_revision, created_at)
        select new.campaign_id, next_sequence, c.recovery_generation, new.id, new.operation_version,
            case new.status
                when 'ACCEPTED' then 'operation.accepted'
                when 'NEEDS_CLARIFICATION' then 'operation.clarification'
                when 'REJECTED' then 'operation.rejected'
                when 'CANCELLED' then 'operation.cancelled'
                when 'FAILED_BEFORE_COMMIT' then 'operation.failed'
                when 'COMMITTED' then 'state.committed'
                when 'DELIVERED' then 'narrative.ready'
                when 'COMMITTED_NARRATION_PENDING' then 'narrative.pending'
                else 'operation.progress' end,
            new.status, coalesce(new.committed_revision, new.base_revision), clock_timestamp()
        from campaign c where c.id = new.campaign_id;
    delete from campaign_stream_message where campaign_id = new.campaign_id and sequence <= next_sequence - 1000;
    return null;
end;
$$;
create trigger operation_journal_stream after insert or update of status on operation_journal
    for each row execute function append_operation_stream_message();

-- 3. Post-commit presentation. One NARRATION job per committed operation that requires
-- narration, inserted by the canonical commit transaction; the deferred foreign key
-- proves the referenced operation committed at exactly this revision. Workers claim a
-- job with a fresh fence and a lease, run presentation outside any transaction, then
-- settle the job and the journal's presentation status together under campaign,
-- journal and job locks. A late worker whose fence or lease no longer matches writes
-- nothing. Jobs never call the canonical commit service or touch world rows.
create table presentation_job (
    id                  uuid        primary key,
    adventure_id        uuid        not null,
    campaign_id         uuid        not null,
    campaign_generation bigint      not null check (campaign_generation >= 0),
    operation_id        uuid        not null,
    committed_revision  bigint      not null check (committed_revision > 0),
    kind                text        not null check (kind = 'NARRATION'),
    status              text        not null check (status in ('QUEUED', 'RUNNING', 'DELIVERED', 'PENDING')),
    attempt_count       integer     not null default 0 check (attempt_count >= 0),
    max_attempts        integer     not null check (max_attempts between 1 and 5),
    fence               bigint      not null default 0 check (fence >= 0),
    lease_holder        uuid,
    lease_expires_at    timestamptz,
    due_at              timestamptz not null,
    cancel_requested_at timestamptz,
    outcome_code        text        check (outcome_code in ('NARRATION_DELIVERED', 'NARRATION_UNAVAILABLE',
        'NARRATION_CANCELLED', 'NARRATION_FAILED', 'GENERATION_RETIRED')),
    created_at          timestamptz not null,
    updated_at          timestamptz not null,
    row_version         bigint      not null default 0 check (row_version >= 0),
    constraint presentation_job_operation_uk unique (campaign_id, operation_id, kind),
    constraint presentation_job_campaign_fk foreign key (adventure_id, campaign_id)
        references campaign (adventure_id, id) on delete restrict,
    constraint presentation_job_commit_fk foreign key (campaign_id, operation_id, committed_revision)
        references operation_journal (campaign_id, id, committed_revision) on delete restrict
        deferrable initially deferred,
    constraint presentation_job_attempts_ck check (attempt_count <= max_attempts),
    -- Exactly RUNNING carries a complete lease; a lease needs a fence.
    constraint presentation_job_lease_ck check (
        (status = 'RUNNING' and lease_holder is not null and lease_expires_at is not null and fence > 0)
        or (status <> 'RUNNING' and lease_holder is null and lease_expires_at is null)),
    -- Exactly the terminal states carry an outcome, and DELIVERED only its own.
    constraint presentation_job_outcome_ck check (
        (status in ('QUEUED', 'RUNNING') and outcome_code is null)
        or (status = 'DELIVERED' and outcome_code = 'NARRATION_DELIVERED')
        or (status = 'PENDING' and outcome_code is not null and outcome_code <> 'NARRATION_DELIVERED'))
);
create index presentation_job_due_idx on presentation_job (due_at, id) where status = 'QUEUED';
create index presentation_job_running_idx on presentation_job (lease_expires_at, id) where status = 'RUNNING';

-- Input: old/new job rows. Output: the checked new row. DELIVERED and PENDING are final.
-- Allowed moves: QUEUED to RUNNING (a claim: one attempt and one fence) or PENDING;
-- RUNNING to QUEUED (retry), DELIVERED or PENDING (each releases with one fence step);
-- same-status RUNNING updates only renew the lease or record a cancellation request.
create function guard_presentation_job() returns trigger language plpgsql as $$
begin
    if old.status in ('DELIVERED', 'PENDING') then
        raise exception using errcode = 'check_violation', constraint = 'presentation_job_terminal',
            message = format('presentation_job_terminal: %s is terminal', old.status);
    end if;
    if new.status is distinct from old.status and not (new.status = any (case old.status
            when 'QUEUED' then array['RUNNING', 'PENDING']
            when 'RUNNING' then array['QUEUED', 'DELIVERED', 'PENDING']
            else array[]::text[] end)) then
        raise exception using errcode = 'check_violation', constraint = 'presentation_job_transition',
            message = format('presentation_job_transition: %s cannot become %s', old.status, new.status);
    end if;
    -- Numeric arithmetic keeps the rule fail-closed at BIGINT's upper bound.
    if (new.lease_holder is distinct from old.lease_holder) <> (new.fence::numeric = old.fence::numeric + 1)
            or new.fence::numeric not between old.fence::numeric and old.fence::numeric + 1
            or (new.attempt_count::numeric = old.attempt_count::numeric + 1)
                <> (old.status = 'QUEUED' and new.status = 'RUNNING')
            or new.attempt_count not between old.attempt_count and old.attempt_count + 1 then
        raise exception using errcode = 'check_violation', constraint = 'presentation_job_fence',
            message = 'presentation_job_fence: invalid fence or attempt transition';
    end if;
    return new;
end;
$$;
-- Trigger names sort so the lifecycle guard runs before the generic checks.
create trigger presentation_job_a_guard before update on presentation_job
    for each row execute function guard_presentation_job();
create trigger presentation_job_frozen before update on presentation_job
    for each row execute function reject_frozen_change('presentation_job_frozen', 'id', 'adventure_id',
        'campaign_id', 'campaign_generation', 'operation_id', 'committed_revision', 'kind', 'max_attempts',
        'created_at');
create trigger presentation_job_write_once before update on presentation_job
    for each row execute function reject_rewrite('presentation_job_write_once', 'cancel_requested_at',
        'outcome_code');
create trigger presentation_job_row_version before update on presentation_job
    for each row execute function require_row_version_step();
create trigger presentation_job_no_delete before delete on presentation_job
    for each row execute function reject_mutation();
