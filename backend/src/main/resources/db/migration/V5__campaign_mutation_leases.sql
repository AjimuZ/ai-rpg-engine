-- P03.5: a durable high-water fence survives release, expiry and process restarts.
-- All application writers lock campaign, journal, then lease in that order.
create table campaign_mutation_lease (
    campaign_id uuid primary key references campaign (id) on delete restrict,
    fence bigint not null default 0 check (fence >= 0),
    operation_id uuid,
    holder_id uuid,
    expires_at timestamptz,
    foreign key (campaign_id, operation_id) references operation_journal (campaign_id, id),
    constraint campaign_mutation_lease_complete_ck check (
        (operation_id is null and holder_id is null and expires_at is null)
        or (operation_id is not null and holder_id is not null and expires_at is not null and fence > 0))
);

-- Input: old/new lease rows. Output: the checked new row. Reject scope changes,
-- fence rewind/skips and ownership changes without a fresh fence; overflow fails closed.
create function guard_mutation_lease() returns trigger language plpgsql as $$
begin
    if new.campaign_id is distinct from old.campaign_id
        or new.fence::numeric not between old.fence::numeric and old.fence::numeric + 1
        or ((new.operation_id is distinct from old.operation_id or new.holder_id is distinct from old.holder_id)
            and new.fence::numeric <> old.fence::numeric + 1) then
        raise exception using errcode = 'check_violation', message = 'invalid mutation lease fence transition';
    end if;
    return new;
end;
$$;

create trigger campaign_mutation_lease_guard before update on campaign_mutation_lease
    for each row execute function guard_mutation_lease();

-- Deletion would permit fence reuse. Explicit owner deletion/restore needs its own workflow.
create trigger campaign_mutation_lease_no_delete before delete on campaign_mutation_lease
    for each row execute function reject_mutation();

-- Keyset scanning keeps startup memory bounded without an offset over changing rows.
create index operation_journal_recovery_idx on operation_journal (campaign_id, id)
    where status in ('ACCEPTED', 'PLANNING', 'VALIDATING', 'COMMITTING', 'COMMITTED', 'NARRATING');
