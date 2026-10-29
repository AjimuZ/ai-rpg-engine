-- P10.3: quest lifecycle state, satisfied objectives and visited locations. All three are written only
-- by canonical commits, in the same transaction as the events that announce them. A quest has a row
-- only once it has left its pack-declared initial status, so an old save gains empty tables and keeps
-- reading every quest at its initial status.

-- One row per quest that has been stored. The primary key is the quest's canonical definition ID
-- (root quests keep their local ID, dependency quests use 'pack#id'). Terminal rows (completed,
-- failed) are immutable: a quest that has ended can never be re-settled, whatever paraphrase of its
-- source is presented later. The reward settlement of its ending is keyed 'quest:<id>:outcome' in
-- reward_settlement, which is unique per recipient.
create table quest_state (
    campaign_id uuid not null references campaign (id) on delete restrict,
    quest_id text not null check (char_length(quest_id) between 1 and 256),
    status text not null check (status in ('undiscovered', 'available', 'active', 'suspended', 'completed', 'failed')),
    -- The outcome that ended the quest; present exactly for terminal statuses.
    outcome_id text check (outcome_id is null or char_length(outcome_id) between 1 and 256),
    -- Sequence of the event that announced the last transition.
    event_sequence bigint not null check (event_sequence > 0),
    updated_revision bigint not null check (updated_revision > 0),
    row_version bigint not null default 0 check (row_version >= 0),
    primary key (campaign_id, quest_id),
    foreign key (campaign_id, event_sequence) references campaign_event (campaign_id, sequence) on delete restrict,
    constraint quest_state_outcome_ck check ((status in ('completed', 'failed')) = (outcome_id is not null))
);
create trigger quest_state_frozen before update on quest_state
    for each row execute function reject_frozen_change('quest_state_frozen', 'campaign_id', 'quest_id');
create trigger quest_state_row_version before update on quest_state
    for each row execute function require_row_version_step();
create trigger quest_state_revision before update on quest_state
    for each row execute function reject_counter_decrease('quest_state_revision', 'updated_revision');

-- The lifecycle only moves forward: an ended quest cannot change, and nothing returns to 'available'
-- (discovery happens once).
create function quest_state_transition() returns trigger language plpgsql as $$
begin
    if old.status in ('completed', 'failed') then
        raise exception 'quest_state_terminal' using errcode = '23514';
    end if;
    if new.status = 'available' and old.status <> 'available' then
        raise exception 'quest_state_discovery' using errcode = '23514';
    end if;
    return new;
end $$;
create trigger quest_state_transition before update on quest_state
    for each row execute function quest_state_transition();

-- Satisfied objectives. Satisfaction is a fact about what happened, so a row is never changed or
-- removed, even if the proof (an item, a position) is later given away.
create table quest_objective (
    campaign_id uuid not null references campaign (id) on delete restrict,
    quest_id text not null,
    objective_id text not null check (char_length(objective_id) between 1 and 256),
    event_sequence bigint not null check (event_sequence > 0),
    satisfied_revision bigint not null check (satisfied_revision > 0),
    primary key (campaign_id, quest_id, objective_id),
    foreign key (campaign_id, quest_id) references quest_state (campaign_id, quest_id) on delete restrict,
    foreign key (campaign_id, event_sequence) references campaign_event (campaign_id, sequence) on delete restrict
);
create trigger quest_objective_immutable before update or delete on quest_objective
    for each row execute function reject_mutation();

-- Locations an actor has been recorded at (the 'visited' objective atom). The first row for a
-- location is written when the actor moves away from or into it; the actor's current location also
-- counts as visited without a row. Rows are immutable.
create table location_visit (
    campaign_id uuid not null references campaign (id) on delete restrict,
    actor_id uuid not null,
    location_id text not null check (char_length(location_id) between 1 and 256),
    first_revision bigint not null check (first_revision > 0),
    primary key (campaign_id, actor_id, location_id),
    foreign key (campaign_id, actor_id) references actor (campaign_id, id) on delete restrict
);
create trigger location_visit_immutable before update or delete on location_visit
    for each row execute function reject_mutation();
