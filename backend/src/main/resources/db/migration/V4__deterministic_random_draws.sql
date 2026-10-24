-- P03.4: private seed material and durable preparation records, separate from world effects.
-- Seed creation is lazy: upgrading a campaign never fabricates historical draws.
create table campaign_random_seed (
    campaign_id uuid primary key references campaign (id),
    seed bytea not null check (octet_length(seed) = 32),
    algorithm text not null check (algorithm = 'HMAC-SHA-256'),
    algorithm_version integer not null check (algorithm_version = 1),
    created_at timestamptz not null,
    unique (campaign_id, algorithm, algorithm_version)
);

create table operation_random_draw (
    id uuid primary key,
    campaign_id uuid not null,
    operation_id uuid not null,
    ordinal integer not null check (ordinal between 0 and 127),
    sides integer not null check (sides > 0),
    value integer not null check (value between 1 and sides),
    algorithm text not null,
    algorithm_version integer not null,
    created_at timestamptz not null,
    unique (campaign_id, operation_id, ordinal),
    -- The leading columns of this unique index also serve operation receipt reads.
    foreign key (campaign_id, operation_id) references operation_journal (campaign_id, id),
    foreign key (campaign_id, algorithm, algorithm_version)
        references campaign_random_seed (campaign_id, algorithm, algorithm_version)
);

-- A cancelled/failed attempt retains its reservation: deleting it would permit rerolls.
create trigger campaign_random_seed_immutable before update or delete on campaign_random_seed
    for each row execute function reject_mutation();
create trigger operation_random_draw_immutable before update or delete on operation_random_draw
    for each row execute function reject_mutation();
