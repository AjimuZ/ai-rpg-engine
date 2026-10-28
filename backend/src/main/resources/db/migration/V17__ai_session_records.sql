-- P09.4: durable records of logical AI sessions and their renewal (docs/08 section 7, ADR 0005,
-- ADR 0007). One row describes one logical session of one campaign line (campaign, role and
-- audience): what it was opened under, why, what continuity it was rehydrated from, how much it
-- was used and why it ended. The rows are diagnostics and continuity provenance only. They are
-- not canonical world state: writing them never advances state_revision, game_turn, world time
-- or the event sequence, and the in-memory session registry stays the authority for which
-- session is live in this process. A failed write is logged and never blocks inference.
--
-- line_generation numbers the sessions of one line (1, 2, ...) across processes and restarts, so
-- a renewed session can name its predecessor. The runtime conversation behind a session is never
-- recorded here, and no prompt, instruction or output text is stored.
create table ai_session_record (
    id                   uuid        primary key,
    adventure_id         uuid        not null,
    campaign_id          uuid        not null references campaign (id) on delete restrict,
    campaign_generation  bigint      not null check (campaign_generation >= 0),
    role                 text        not null check (role ~ '^[a-z][a-z-]{0,31}$'),
    audience             text        not null check (audience ~ '^(public|privileged|character:[A-Za-z0-9._:-]{1,128})$'),
    pack_hash            text        not null check (pack_hash ~ '^[0-9a-f]{64}$'),
    policy_hash          text        not null check (policy_hash ~ '^[0-9a-f]{64}$'),
    line_generation      bigint      not null check (line_generation >= 1),
    predecessor_id       uuid,
    -- Why this session was opened: FIRST for a line without history, otherwise the retirement
    -- reason of the session it replaces (RESTART when that session belonged to an earlier process).
    opened_cause         text        not null check (opened_cause in ('FIRST', 'EXPLICIT', 'SUPERSEDED', 'CAMPAIGN',
                                                     'INSTALLATION', 'CAPACITY', 'TURN_CEILING', 'CONTEXT_PRESSURE',
                                                     'UNRELIABLE', 'OWNER', 'RESTART')),
    status               text        not null check (status in ('ACTIVE', 'RETIRED')),
    retired_reason       text        check (retired_reason in ('EXPLICIT', 'SUPERSEDED', 'CAMPAIGN', 'INSTALLATION',
                                                               'CAPACITY', 'TURN_CEILING', 'CONTEXT_PRESSURE',
                                                               'UNRELIABLE', 'OWNER', 'RESTART')),
    -- Runtime turns that may have run in the session (a result with provenance, or an unconfirmed
    -- one), of which completed_tasks completed; context_units is the estimated size of everything
    -- the conversation has accumulated (inputs plus outputs, 4 UTF-8 bytes per unit).
    turns                integer     not null default 0 check (turns >= 0),
    completed_tasks      integer     not null default 0 check (completed_tasks between 0 and turns),
    context_units        bigint      not null default 0 check (context_units >= 0),
    context_warned       boolean     not null default false,
    -- How the first task of the session was rehydrated: NONE (first session of the line),
    -- SUMMARY (a usable continuity summary was part of the block), CHECKPOINT (recent events and a
    -- verified operational checkpoint, no usable summary), STATE (canonical state and recent events
    -- only), UNAVAILABLE (assembly failed; rehydration_code says why) or OMITTED (the block did not
    -- fit the instruction bound).
    rehydration_basis    text        not null check (rehydration_basis in ('NONE', 'SUMMARY', 'CHECKPOINT', 'STATE',
                                                                           'UNAVAILABLE', 'OMITTED')),
    rehydration_code     text        check (rehydration_code ~ '^[a-z][a-z0-9-]{0,63}$'),
    rehydration_revision bigint      check (rehydration_revision >= 0),
    rehydration_digest   text        check (rehydration_digest ~ '^[0-9a-f]{64}$'),
    -- The operational checkpoint verified for the rehydration (no foreign key: retention prunes
    -- checkpoints, and the hash stays as evidence). Its status is the verification result.
    checkpoint_id        uuid,
    checkpoint_revision  bigint      check (checkpoint_revision >= 0),
    checkpoint_hash      text        check (checkpoint_hash ~ '^[0-9a-f]{64}$'),
    checkpoint_status    text        check (checkpoint_status in ('NONE', 'CORRUPT', 'UNSUPPORTED', 'INCOMPATIBLE',
                                                                  'DIVERGED', 'OUTDATED', 'VALID', 'UNSAFE')),
    summary_id           uuid,
    opened_at            timestamptz not null,
    last_used_at         timestamptz,
    retired_at           timestamptz,
    row_version          bigint      not null default 0,
    constraint ai_session_record_line_uk unique (campaign_id, role, audience, line_generation),
    constraint ai_session_record_retired_ck check ((status = 'ACTIVE') = (retired_reason is null)
        and (status = 'ACTIVE') = (retired_at is null)),
    constraint ai_session_record_first_ck check ((opened_cause = 'FIRST') = (predecessor_id is null)
        and (opened_cause <> 'FIRST' or rehydration_basis = 'NONE')),
    constraint ai_session_record_digest_ck check ((rehydration_basis in ('SUMMARY', 'CHECKPOINT', 'STATE', 'OMITTED'))
        = (rehydration_digest is not null and rehydration_revision is not null)),
    constraint ai_session_record_summary_ck check ((rehydration_basis = 'SUMMARY') = (summary_id is not null)),
    constraint ai_session_record_checkpoint_ck check ((checkpoint_hash is null) = (checkpoint_id is null)
        and (checkpoint_hash is null) = (checkpoint_revision is null))
);
create index ai_session_record_active_ix on ai_session_record (campaign_id) where status = 'ACTIVE';

-- A retired record is terminal; only its usage counters may still grow, because a task that was
-- already running when its session was retired finishes on that conversation.
create function guard_ai_session_record() returns trigger language plpgsql as $$
begin
    if old.status = 'RETIRED' and (new.status <> 'RETIRED' or new.retired_reason is distinct from old.retired_reason
            or new.retired_at is distinct from old.retired_at) then
        raise exception using errcode = 'check_violation', constraint = 'ai_session_record_terminal',
            message = 'a retired AI session record is terminal';
    end if;
    return new;
end;
$$;
create trigger ai_session_record_terminal before update on ai_session_record
    for each row execute function guard_ai_session_record();
create trigger ai_session_record_frozen before update on ai_session_record
    for each row execute function reject_frozen_change('ai_session_record_frozen', 'id', 'adventure_id',
        'campaign_id', 'campaign_generation', 'role', 'audience', 'pack_hash', 'policy_hash', 'line_generation',
        'predecessor_id', 'opened_cause', 'rehydration_basis', 'rehydration_code', 'rehydration_revision',
        'rehydration_digest', 'checkpoint_id', 'checkpoint_revision', 'checkpoint_hash', 'checkpoint_status',
        'summary_id', 'opened_at');
create trigger ai_session_record_monotonic before update on ai_session_record
    for each row execute function reject_counter_decrease('ai_session_record_monotonic', 'turns',
        'completed_tasks', 'context_units');
create trigger ai_session_record_row_version before update on ai_session_record
    for each row execute function require_row_version_step();
create trigger ai_session_record_no_delete before delete on ai_session_record
    for each row execute function reject_mutation();
