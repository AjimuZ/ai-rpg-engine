-- P16.3-A: audited owner canon corrections (docs/14 section 8, QA-27, FR-026).
--
-- A correction is an ordinary committed operation of the campaign: one operation_journal row of
-- command type CANON_CORRECTION, one INTERNAL CANON_CORRECTED event at the next revision, a
-- superseding claim and carried beliefs that cite that event, a memory_invalidation of the affected
-- summaries, bumped query epochs and re-evaluated plan dispositions, all in one transaction. History
-- is never edited: earlier events, the corrected claim and the replaced beliefs keep every value they
-- had and are only closed at the correcting revision, as any supersession closes them.
--
-- This table keeps, per correction operation, what the owner approved and what restores the state
-- before it: the exact preview document whose digest the owner confirmed, and the full-owner campaign
-- export taken at the base revision. It deliberately has no campaign_id column. The full-owner export
-- copies every table that has one, so a backup stored in such a table would contain every earlier
-- backup and grow without bound; the campaign is reached through the operation instead. The digests
-- are checked by the database, so a stored document always matches the digest the event cites.
create table canon_correction (
    operation_id   uuid        primary key references operation_journal (id) on delete restrict,
    base_revision  bigint      not null check (base_revision >= 0),
    preview        bytea       not null check (octet_length(preview) between 2 and 262144),
    preview_sha256 text        not null check (preview_sha256 ~ '^[0-9a-f]{64}$'),
    backup         bytea       not null check (octet_length(backup) between 2 and 268435456),
    backup_sha256  text        not null check (backup_sha256 ~ '^[0-9a-f]{64}$'),
    created_at     timestamptz not null,
    constraint canon_correction_preview_digest_ck check (encode(sha256(preview), 'hex') = preview_sha256),
    constraint canon_correction_backup_digest_ck check (encode(sha256(backup), 'hex') = backup_sha256)
);

-- A record belongs to a correction operation and to that operation's base revision, so a backup can
-- never be attached to a player action or describe another revision than the one corrected.
create function guard_canon_correction() returns trigger language plpgsql as $$
begin
    if not exists (select 1 from operation_journal o where o.id = new.operation_id
            and o.command_type = 'CANON_CORRECTION' and o.base_revision = new.base_revision) then
        raise exception using errcode = 'check_violation', constraint = 'canon_correction_operation',
            message = 'canon_correction_operation: a record belongs to a correction operation at its base revision';
    end if;
    return new;
end;
$$;
create trigger canon_correction_operation before insert on canon_correction
    for each row execute function guard_canon_correction();
create trigger canon_correction_immutable before update or delete on canon_correction
    for each row execute function reject_mutation();
