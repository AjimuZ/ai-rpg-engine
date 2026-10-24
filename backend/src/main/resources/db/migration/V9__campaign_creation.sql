-- P06.1 creation checkpoint: no rows are backfilled for existing campaigns. Initial
-- character state, grants and a durable request identity commit with the campaign.
-- Live progression/equipment/actions remain later command work, never generic patches.
create table character_state (
    campaign_id uuid not null references campaign (id) on delete restrict,
    actor_id uuid primary key,
    attributes jsonb not null check (jsonb_typeof(attributes) = 'object' and octet_length(attributes::text) <= 16384),
    skills jsonb not null check (jsonb_typeof(skills) = 'object' and octet_length(skills::text) <= 16384),
    background text check (background is null or char_length(background) between 1 and 200),
    role text check (role is null or char_length(role) between 1 and 200),
    row_version bigint not null default 0 check (row_version >= 0),
    foreign key (campaign_id, actor_id) references actor (campaign_id, id) on delete restrict
);
create trigger character_state_frozen before update on character_state
    for each row execute function reject_frozen_change('character_state_frozen', 'campaign_id', 'actor_id');
create trigger character_state_row_version before update on character_state
    for each row execute function require_row_version_step();

-- Item identity and stack policy are pinned; quantities may later change only through
-- validated canonical commands. Storage enforces bounds even against a stray SQL writer.
create table item_instance (
    id uuid primary key,
    campaign_id uuid not null references campaign (id) on delete restrict,
    actor_id uuid not null,
    definition_id text not null check (char_length(definition_id) between 1 and 256),
    quantity integer not null,
    maximum_stack integer not null check (maximum_stack between 1 and 9999),
    acquired_revision bigint not null check (acquired_revision >= 0),
    row_version bigint not null default 0 check (row_version >= 0),
    check (quantity between 1 and maximum_stack),
    unique (campaign_id, id),
    foreign key (campaign_id, actor_id) references actor (campaign_id, id) on delete restrict
);
create index item_instance_actor_idx on item_instance (campaign_id, actor_id);
create trigger item_instance_frozen before update on item_instance
    for each row execute function reject_frozen_change('item_instance_frozen', 'campaign_id', 'definition_id',
        'maximum_stack', 'acquired_revision');
create trigger item_instance_row_version before update on item_instance
    for each row execute function require_row_version_step();

create table learned_ability (
    campaign_id uuid not null references campaign (id) on delete restrict,
    actor_id uuid not null,
    definition_id text not null check (char_length(definition_id) between 1 and 256),
    rank integer not null check (rank between 1 and 10),
    acquired_revision bigint not null check (acquired_revision >= 0),
    row_version bigint not null default 0 check (row_version >= 0),
    primary key (campaign_id, actor_id, definition_id),
    foreign key (campaign_id, actor_id) references actor (campaign_id, id) on delete restrict
);
create trigger learned_ability_frozen before update on learned_ability
    for each row execute function reject_frozen_change('learned_ability_frozen', 'campaign_id', 'actor_id',
        'definition_id', 'acquired_revision');
create trigger learned_ability_row_version before update on learned_ability
    for each row execute function require_row_version_step();

-- A successful request is immutable and scoped to its owning adventure. Serialize
-- service creation on that adventure, then compare hashes before returning a retry.
-- No tentative success is visible until COMMIT, including deferred constraint checks.
create table campaign_creation (
    adventure_id uuid not null references adventure_workspace (id) on delete restrict,
    request_id uuid not null,
    payload_hash text not null check (payload_hash ~ '^[0-9a-f]{64}$'),
    campaign_id uuid not null unique,
    actor_id uuid not null,
    profile_id text not null check (char_length(profile_id) between 1 and 256),
    choices jsonb not null check (jsonb_typeof(choices) = 'object' and octet_length(choices::text) <= 32768),
    created_at timestamptz not null,
    primary key (adventure_id, request_id),
    foreign key (adventure_id, campaign_id) references campaign (adventure_id, id) on delete restrict,
    foreign key (campaign_id, actor_id) references actor (campaign_id, id) on delete restrict
);
create trigger campaign_creation_immutable before update or delete on campaign_creation
    for each row execute function reject_mutation();
