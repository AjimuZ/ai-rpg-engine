-- P11.2: initiative, rounds, activations, action points, speech allowance and scene passages of an encounter
-- (docs/11 sections 6-7.1, ADR 0029).
--
-- The tactical cursor lives on the encounter row: the acting participant's initiative rank and remaining action
-- points (set exactly while an activation is in progress), the speech-only utterances already made in that
-- activation, and the passages declared scene actions have opened or closed. Each participant row gains its
-- initiative total and rank, written once before the first round opens, and its reaction budget. V22 keeps checking
-- every phase arrow; the triggers below add the scheduling guarantees, so no writer can grant points mid-activation,
-- exceed the frozen allowances or re-roll initiative.

alter table encounter
    add column actor_cursor  integer check (actor_cursor between 0 and 8),
    add column action_points integer check (action_points between 0 and 6),
    add column speech_used   integer not null default 0 check (speech_used between 0 and 3),
    add column passages      jsonb   not null default '[]'::jsonb check (jsonb_typeof(passages) = 'array'
                                 and jsonb_array_length(passages) <= 32),
    add constraint encounter_cursor_ck check ((actor_cursor is null) = (action_points is null)
        and (phase not in ('ACTOR_START', 'AWAITING_ACTION', 'RESOLVING', 'ACTOR_END') or actor_cursor is not null)
        and (phase not in ('ENTERING', 'ROUND_OPEN', 'ROUND_END') or actor_cursor is null));

alter table encounter_participant
    add column initiative      integer check (initiative between 2 and 32),
    add column initiative_rank integer check (initiative_rank between 0 and 8),
    add column reactions       integer not null default 0 check (reactions between 0 and 3),
    add constraint encounter_participant_initiative_ck check ((initiative is null) = (initiative_rank is null)),
    add constraint encounter_participant_rank_uk unique (encounter_id, initiative_rank);

-- Scheduling guarantees of docs/11 sections 6-7.1, checked against the frozen profile of the same row:
-- the first round opens only after every participant has an initiative rank; action points never exceed the
-- frozen allowance and grow only when an activation starts; the speech count stays within the frozen allowance,
-- grows by at most one per update and resets only when an activation starts or the round boundary clears it.
create function encounter_schedule() returns trigger language plpgsql as $$
begin
    if new.phase = 'ROUND_OPEN' and old.phase = 'ENTERING' and exists (select 1 from encounter_participant p
            where p.encounter_id = new.id and p.initiative_rank is null) then
        raise exception using errcode = 'check_violation', constraint = 'encounter_schedule',
            message = 'encounter_schedule: initiative is rolled for every participant before the first round';
    end if;
    if new.action_points is not null then
        if new.action_points > (new.profile -> 'values' ->> 'actionPoints')::integer then
            raise exception using errcode = 'check_violation', constraint = 'encounter_schedule',
                message = 'encounter_schedule: action points exceed the frozen allowance';
        end if;
        if new.action_points > coalesce(old.action_points, -1) and new.phase <> 'ACTOR_START' then
            raise exception using errcode = 'check_violation', constraint = 'encounter_schedule',
                message = 'encounter_schedule: action points are granted only when an activation starts';
        end if;
    end if;
    if new.speech_used > (new.profile -> 'values' ->> 'speechPerActivation')::integer
            or new.speech_used > old.speech_used + 1
            or (new.speech_used < old.speech_used and new.phase not in ('ACTOR_START', 'ROUND_END')) then
        raise exception using errcode = 'check_violation', constraint = 'encounter_schedule',
            message = 'encounter_schedule: speech-only utterances exceed the activation allowance';
    end if;
    return new;
end $$;
create trigger encounter_schedule before update on encounter
    for each row execute function encounter_schedule();

-- Initiative is rolled once: a stored total or rank never changes afterwards (docs/11 section 6: no new roll on
-- refresh, renewal or reconnect).
create function encounter_initiative_once() returns trigger language plpgsql as $$
begin
    if old.initiative_rank is not null and (new.initiative is distinct from old.initiative
            or new.initiative_rank is distinct from old.initiative_rank) then
        raise exception using errcode = 'check_violation', constraint = 'encounter_initiative_once',
            message = 'encounter_initiative_once: initiative is rolled once';
    end if;
    return new;
end $$;
create trigger encounter_initiative_once before update on encounter_participant
    for each row execute function encounter_initiative_once();
