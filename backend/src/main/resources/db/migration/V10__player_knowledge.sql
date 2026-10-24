-- P06.2 player knowledge is an explicit audience projection, never a view of secret
-- lore, planning artifacts or raw events. Only canonical reveal/progression writers
-- may populate these records; this migration invents no knowledge for old saves.
-- UUIDs identify records independently of equal names in other adventures.
create table campaign_known_fact (
    id uuid primary key,
    campaign_id uuid not null references campaign (id) on delete restrict,
    actor_id uuid not null,
    fact_text text not null check (char_length(fact_text) between 1 and 4000),
    confidence text not null check (confidence in ('KNOWN', 'BELIEVED', 'RUMORED')),
    learned_revision bigint not null check (learned_revision >= 0),
    row_version bigint not null default 0 check (row_version >= 0),
    foreign key (campaign_id, actor_id) references actor (campaign_id, id) on delete restrict
);
create index campaign_known_fact_audience_idx on campaign_known_fact (campaign_id, actor_id, id);
create trigger campaign_known_fact_frozen before update on campaign_known_fact
    for each row execute function reject_frozen_change('campaign_known_fact_frozen', 'campaign_id', 'actor_id', 'learned_revision');
create trigger campaign_known_fact_version before update on campaign_known_fact
    for each row execute function require_row_version_step();

-- Quest text is the player's known objective, not the full authored quest graph,
-- secret prerequisites or unrevealed rewards. A future command must update it in
-- the same transaction as the canonical world change and its evidence.
create table campaign_known_quest (
    id uuid primary key,
    campaign_id uuid not null references campaign (id) on delete restrict,
    actor_id uuid not null,
    title text not null check (char_length(title) between 1 and 200),
    objective text not null check (char_length(objective) between 1 and 4000),
    status text not null check (status in ('ACTIVE', 'COMPLETED', 'FAILED', 'ABANDONED')),
    updated_revision bigint not null check (updated_revision >= 0),
    row_version bigint not null default 0 check (row_version >= 0),
    foreign key (campaign_id, actor_id) references actor (campaign_id, id) on delete restrict
);
create index campaign_known_quest_audience_idx on campaign_known_quest (campaign_id, actor_id, id);
create trigger campaign_known_quest_frozen before update on campaign_known_quest
    for each row execute function reject_frozen_change('campaign_known_quest_frozen', 'campaign_id', 'actor_id');
create trigger campaign_known_quest_version before update on campaign_known_quest
    for each row execute function require_row_version_step();
create trigger campaign_known_quest_revision before update on campaign_known_quest
    for each row execute function reject_counter_decrease('campaign_known_quest_revision', 'updated_revision');
