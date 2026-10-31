-- P21.4-A: durable record of the GPU and RAM admission coordinator's leases (docs/34 section 13).
-- One row per lease: the grant of GPU and RAM capacity for one resident model, shared by a batch of
-- compatible requests, from the grant to its release. It is installation-wide operating state, not
-- canonical world state: writing it never advances state_revision, game_turn, world time, the event
-- sequence, a resource, a receipt or a draw, and no row refers to a campaign or an owner row. It
-- stores identities, a model identifier, byte counts and times only: no prompt, output, key or
-- endpoint.
--
-- Lifecycle: ACTIVE (granted; its capacity is in use until expires_at unless renewed) -> RELEASED
-- (the last member finished), EXPIRED (nobody renewed it before expires_at) or REVOKED (the
-- coordinator took it back for more urgent work). A process that finds an ACTIVE or RECONCILING lease
-- of another process cannot know whether that model is still resident, so it moves the row to
-- RECONCILING under its own identity and keeps the capacity reserved until a caller confirms that the
-- model is gone (RELEASED). The coordinator, not this table, enforces the capacity total.
create table resource_lease (
    id          uuid        primary key,
    -- Coordinator process that owns the row. Each process start draws a fresh identity, so a row owned
    -- by another identity belongs to an earlier process; only the owner may renew or settle it.
    instance_id uuid        not null,
    modality    text        not null check (modality in ('TEXT', 'IMAGE', 'SPEECH')),
    model       text        not null check (model ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'),
    gpu_bytes   bigint      not null check (gpu_bytes >= 0),
    ram_bytes   bigint      not null check (ram_bytes >= 0),
    -- Members currently sharing the lease; an active lease always has at least one.
    batch_size  integer     not null check (batch_size between 0 and 64),
    status      text        not null check (status in ('ACTIVE', 'RECONCILING', 'RELEASED', 'EXPIRED', 'REVOKED')),
    granted_at  timestamptz not null,
    expires_at  timestamptz not null,
    finished_at timestamptz,
    row_version bigint      not null default 0 check (row_version >= 0),
    constraint resource_lease_demand_ck check (gpu_bytes + ram_bytes > 0),
    constraint resource_lease_expiry_ck check (expires_at > granted_at),
    constraint resource_lease_state_ck check (
        (status = 'ACTIVE' and batch_size >= 1 and finished_at is null)
        or (status = 'RECONCILING' and finished_at is null)
        or (status in ('RELEASED', 'EXPIRED', 'REVOKED') and finished_at is not null))
);

-- Startup recovery and capacity reads touch the open rows only.
create index resource_lease_open_idx on resource_lease (instance_id, status)
    where status in ('ACTIVE', 'RECONCILING');

-- Input: old/new lease rows. Output: the checked new row. Terminal rows are final.
-- Allowed moves:
--   ACTIVE      -> ACTIVE (resize or renew), RELEASED, EXPIRED, REVOKED or RECONCILING;
--   RECONCILING -> RECONCILING (a later process takes over the hold) or RELEASED.
-- Only a move into RECONCILING may change the owning process, and a renewal never shortens a lease.
create function guard_resource_lease() returns trigger language plpgsql as $$
begin
    if old.status not in ('ACTIVE', 'RECONCILING') then
        raise exception using errcode = 'check_violation', constraint = 'resource_lease_terminal',
            message = format('resource_lease_terminal: %s is terminal', old.status);
    end if;
    if not (new.status = any (case old.status
            when 'ACTIVE' then array['ACTIVE', 'RELEASED', 'EXPIRED', 'REVOKED', 'RECONCILING']
            else array['RECONCILING', 'RELEASED'] end)) then
        raise exception using errcode = 'check_violation', constraint = 'resource_lease_transition',
            message = format('resource_lease_transition: %s cannot become %s', old.status, new.status);
    end if;
    if new.instance_id is distinct from old.instance_id and new.status <> 'RECONCILING' then
        raise exception using errcode = 'check_violation', constraint = 'resource_lease_owner',
            message = 'resource_lease_owner: only a reconciliation takeover changes the owner';
    end if;
    if new.expires_at < old.expires_at then
        raise exception using errcode = 'check_violation', constraint = 'resource_lease_expiry',
            message = 'resource_lease_expiry: a renewal never shortens a lease';
    end if;
    return new;
end;
$$;
-- Trigger names sort so the lifecycle guard runs before the generic checks.
create trigger resource_lease_a_guard before update on resource_lease
    for each row execute function guard_resource_lease();
create trigger resource_lease_frozen before update on resource_lease
    for each row execute function reject_frozen_change('resource_lease_frozen', 'id', 'modality', 'model',
        'gpu_bytes', 'ram_bytes', 'granted_at');
create trigger resource_lease_write_once before update on resource_lease
    for each row execute function reject_rewrite('resource_lease_write_once', 'finished_at');
create trigger resource_lease_row_version before update on resource_lease
    for each row execute function require_row_version_step();
create trigger resource_lease_no_delete before delete on resource_lease
    for each row execute function reject_mutation();
