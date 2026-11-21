-- P15.2-B: faction clock changes with their causes (docs/10 section 7, ADR 0052). One immutable row per change of one
-- faction clock, written by the canonical commit in the same transaction as the revision that records it. A clock
-- fills from 0 to its target; its current value is value_after of its latest row (by revision, then ordinal), or 0
-- when it has none, and it is complete once a row reaches the target. Each row names exactly one cause:
--   * WORLD_TIME: a time boundary of the clock's tick, due at due_world_time;
--   * QUEST_ENDED: the ending of cause_quest_id, announced by event cause_event_sequence at due_world_time;
--   * CLOCK_COMPLETED: the completion of the clock (cause_faction_id, cause_clock_id) at due_world_time.
-- A cause may be recorded by a later commit than the one in which it became due (bounded catch-up), never earlier.
-- Campaigns whose content declares no clock never write here.

create table faction_clock_change (
    campaign_id          uuid    not null references campaign (id) on delete restrict,
    changed_revision     bigint  not null check (changed_revision > 0),
    -- Order of the change within its commit, 0..31 (FactionClocks.MAX_CHANGES).
    ordinal              integer not null check (ordinal between 0 and 31),
    -- Canonical faction ID (root factions keep their local ID, dependency factions use 'pack#id').
    faction_id           text    not null check (char_length(faction_id) between 1 and 256),
    clock_id             text    not null check (clock_id ~ '^[a-z][a-z0-9._:-]{0,127}$'),
    cause                text    not null check (cause in ('WORLD_TIME', 'QUEST_ENDED', 'CLOCK_COMPLETED')),
    due_world_time       bigint  not null check (due_world_time >= 0),
    cause_quest_id       text,
    cause_event_sequence bigint,
    cause_faction_id     text    check (cause_faction_id is null or char_length(cause_faction_id) between 1 and 256),
    cause_clock_id       text    check (cause_clock_id is null or cause_clock_id ~ '^[a-z][a-z0-9._:-]{0,127}$'),
    value_before         integer not null check (value_before >= 0),
    value_after          integer not null,
    -- The clock's target, 1..12 (FactionClockRules.MAX_SEGMENTS).
    target               integer not null check (target between 1 and 12),
    completed            boolean not null,
    changed_world_time   bigint  not null check (changed_world_time <= 3155760000),
    primary key (campaign_id, changed_revision, ordinal),
    constraint faction_clock_change_quest_fk foreign key (campaign_id, cause_quest_id)
        references quest_state (campaign_id, quest_id) on delete restrict,
    constraint faction_clock_change_event_fk foreign key (campaign_id, cause_event_sequence)
        references campaign_event (campaign_id, sequence) on delete restrict,
    -- A recorded change fills at least one segment and never passes the target; a full clock is complete.
    constraint faction_clock_change_moves_ck check (value_after > value_before and value_after <= target),
    constraint faction_clock_change_completed_ck check (completed = (value_after = target)),
    -- A cause is due no later than the commit that records it.
    constraint faction_clock_change_due_ck check (due_world_time <= changed_world_time),
    -- Exactly the columns of the named cause are present.
    constraint faction_clock_change_cause_ck check (case cause
        when 'WORLD_TIME' then num_nonnulls(cause_quest_id, cause_event_sequence, cause_faction_id, cause_clock_id) = 0
        when 'QUEST_ENDED' then num_nonnulls(cause_quest_id, cause_event_sequence) = 2
            and num_nonnulls(cause_faction_id, cause_clock_id) = 0
        else num_nonnulls(cause_quest_id, cause_event_sequence) = 0
            and num_nonnulls(cause_faction_id, cause_clock_id) = 2 end),
    constraint faction_clock_change_self_ck check (cause_faction_id is null
        or (cause_faction_id, cause_clock_id) <> (faction_id, clock_id))
);
-- Values only rise, so a value is reached once; this also makes completion happen at most once per clock.
create unique index faction_clock_change_value_uq on faction_clock_change (campaign_id, faction_id, clock_id, value_after);
-- Each cause counts at most once per clock.
create unique index faction_clock_change_tick_uq on faction_clock_change (campaign_id, faction_id, clock_id, due_world_time)
    where cause = 'WORLD_TIME';
create unique index faction_clock_change_quest_uq on faction_clock_change (campaign_id, faction_id, clock_id, cause_quest_id)
    where cause = 'QUEST_ENDED';
create unique index faction_clock_change_source_uq on faction_clock_change
    (campaign_id, faction_id, clock_id, cause_faction_id, cause_clock_id) where cause = 'CLOCK_COMPLETED';
create trigger faction_clock_change_immutable before update or delete on faction_clock_change
    for each row execute function reject_mutation();

-- Provenance of every new row: it is written by the commit that produces its revision at its world time, it continues
-- the clock from its current value, and its cause is a recorded fact that was due by then.
create function faction_clock_change_provenance() returns trigger language plpgsql as $$
declare
    current_value integer;
begin
    if not exists (select 1 from campaign c where c.id = new.campaign_id and c.state_revision = new.changed_revision
            and c.world_time_seconds = new.changed_world_time) then
        raise exception using errcode = 'check_violation', constraint = 'faction_clock_change_provenance',
            message = 'faction_clock_change_provenance: a change belongs to the committing revision and its world time';
    end if;
    select f.value_after into current_value from faction_clock_change f where f.campaign_id = new.campaign_id
        and f.faction_id = new.faction_id and f.clock_id = new.clock_id
        order by f.changed_revision desc, f.ordinal desc limit 1;
    if coalesce(current_value, 0) <> new.value_before then
        raise exception using errcode = 'check_violation', constraint = 'faction_clock_change_provenance',
            message = format('faction_clock_change_provenance: the clock stands at %s, not %s',
                             coalesce(current_value, 0), new.value_before);
    end if;
    if new.cause = 'QUEST_ENDED' and not exists (select 1 from quest_state q join campaign_event e
            on e.campaign_id = q.campaign_id and e.sequence = q.event_sequence
            where q.campaign_id = new.campaign_id and q.quest_id = new.cause_quest_id
              and q.status in ('completed', 'failed') and q.event_sequence = new.cause_event_sequence
              and e.world_time_seconds = new.due_world_time and e.state_revision <= new.changed_revision) then
        raise exception using errcode = 'check_violation', constraint = 'faction_clock_change_provenance',
            message = 'faction_clock_change_provenance: the cited event did not end the cited quest by then';
    end if;
    if new.cause = 'CLOCK_COMPLETED' and not exists (select 1 from faction_clock_change f
            where f.campaign_id = new.campaign_id and f.faction_id = new.cause_faction_id
              and f.clock_id = new.cause_clock_id and f.completed and f.due_world_time = new.due_world_time) then
        raise exception using errcode = 'check_violation', constraint = 'faction_clock_change_provenance',
            message = 'faction_clock_change_provenance: the cited clock has not completed at that time';
    end if;
    return new;
end;
$$;
create trigger faction_clock_change_provenance before insert on faction_clock_change
    for each row execute function faction_clock_change_provenance();
