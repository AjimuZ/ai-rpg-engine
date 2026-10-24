-- P04.4: immutable pack registration (docs/09 sections 1, 9 and 10; docs/14 section 5).
-- An admitted ContentRevision now stores the exact staged bytes of every pack file and one
-- deterministic import report. Together with content_revision they form a sealed unit:
-- rows are never updated or deleted, files cannot be added once the report exists, and the
-- report can only be written when it matches the stored files. Revisions created before
-- this migration (test fixtures) have neither files nor a report and remain readable.

-- One stored pack file. The checks recompute size and SHA-256 from the stored bytes, so a
-- row can never claim a digest its content does not have. Paths repeat the portable path
-- rules of PackPaths as a coarse database guard.
create table content_revision_file (
    content_revision_id uuid   not null references content_revision (id) on delete restrict,
    path                text   not null constraint content_revision_file_path_ck
                                   check (char_length(path) between 1 and 240
                                          and path ~ '^[A-Za-z0-9_][A-Za-z0-9_.-]*(/[A-Za-z0-9_][A-Za-z0-9_.-]*)*$'),
    category            text   not null check (char_length(category) between 1 and 64),
    byte_size           bigint not null,
    sha256              text   not null,
    media_type          text   check (media_type is null or char_length(media_type) between 1 and 64),
    content             bytea  not null,
    constraint content_revision_file_size_ck check (byte_size = octet_length(content)),
    constraint content_revision_file_hash_ck check (sha256 = encode(sha256(content), 'hex')),
    primary key (content_revision_id, path)
);

-- Deterministic import report: no timestamps or host paths, so registering the same bytes
-- twice yields the same document. exports maps exported definition IDs to their kinds and
-- is what dependent packs resolve against.
create table content_revision_report (
    content_revision_id uuid    primary key references content_revision (id) on delete restrict,
    report_version      integer not null check (report_version = 1),
    file_count          integer not null check (file_count between 1 and 2000),
    total_bytes         bigint  not null check (total_bytes >= 0),
    report              jsonb   not null check (jsonb_typeof(report) = 'object'),
    exports             jsonb   not null check (jsonb_typeof(exports) = 'object')
);

create trigger content_revision_file_immutable before update or delete on content_revision_file
    for each row execute function reject_mutation();
create trigger content_revision_report_immutable before update or delete on content_revision_report
    for each row execute function reject_mutation();

-- Sealing, part 1: once a report exists the file set is final. Without this a later insert
-- could add a file the report, and every hash derived from it, never described.
create function reject_sealed_file() returns trigger language plpgsql as $$
begin
    -- Serialize file insertion and sealing, including callers outside PackRegistry. A plain
    -- existence check races with an uncommitted seal in another READ COMMITTED transaction.
    -- Snapshot isolation cannot refresh the seal lookup after waiting for this row lock.
    if current_setting('transaction_isolation') <> 'read committed' then
        raise exception 'pack file insertion requires read committed isolation'
            using errcode = 'feature_not_supported';
    end if;
    perform 1 from content_revision where id = new.content_revision_id for no key update;
    if exists (select 1 from content_revision_report r where r.content_revision_id = new.content_revision_id) then
        raise exception 'content revision % is sealed', new.content_revision_id
            using errcode = 'restrict_violation';
    end if;
    return new;
end;
$$;
create trigger content_revision_file_sealed before insert on content_revision_file
    for each row execute function reject_sealed_file();

-- Sealing, part 2: a report must describe exactly the stored files by count and total size.
create function require_report_matches_files() returns trigger language plpgsql as $$
declare
    stored_count bigint;
    stored_bytes bigint;
begin
    -- Use the same parent lock as file insertion; the following query sees its committed writes.
    if current_setting('transaction_isolation') <> 'read committed' then
        raise exception 'pack sealing requires read committed isolation'
            using errcode = 'feature_not_supported';
    end if;
    perform 1 from content_revision where id = new.content_revision_id for no key update;
    select count(*), coalesce(sum(f.byte_size), 0) into stored_count, stored_bytes
        from content_revision_file f where f.content_revision_id = new.content_revision_id;
    if stored_count <> new.file_count or stored_bytes <> new.total_bytes then
        raise exception 'report for content revision % does not match its files', new.content_revision_id
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger content_revision_report_matches before insert on content_revision_report
    for each row execute function require_report_matches_files();
