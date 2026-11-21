-- P17.1-A: managed presentation assets and their campaign associations (docs/23 sections 3-4, docs/14 section 5).
--
-- Two immutable tables. managed_asset is a content-addressed store of validated media bytes: the key is the
-- SHA-256 of the bytes, so equal bytes are kept once and a served URL names exactly the content it returns.
-- asset_association says which asset a campaign shows for one subject in one category at one appearance
-- revision. A newer appearance is a new row; no row is edited, so historical artwork is never replaced.
--
-- Neither table holds a prompt, seed, file path, URL, provider name or canonical entity data. The only free
-- text is a short credit line; every other column is a hash, a number, a media type or a stable reference.
--
-- asset_association deliberately has no foreign key. Its campaign link is checked by the insert trigger below
-- instead, so truncating the campaign tables (campaigns are never deleted) does not depend on this table, and a
-- restored association whose bytes are absent from a clean installation is a missing image, not a refused restore.
-- managed_asset carries no campaign identity, so a full-owner export copies the associations and never the bytes.

-- One stored media file. The checks recompute size and SHA-256 from the stored bytes, so a row can never claim a
-- digest or size its content does not have. Dimensions are the declared ones for images and 0 for audio.
create table managed_asset (
    sha256     text        primary key check (sha256 ~ '^[0-9a-f]{64}$'),
    media_type text        not null check (media_type in ('image/png', 'image/jpeg', 'image/webp', 'audio/ogg',
                                                          'audio/mpeg', 'audio/wav')),
    byte_size  bigint      not null check (byte_size between 1 and 33554432),
    width      integer     not null check (width between 0 and 8192),
    height     integer     not null check (height between 0 and 8192),
    content    bytea       not null,
    created_at timestamptz not null,
    constraint managed_asset_size_ck check (byte_size = octet_length(content)),
    constraint managed_asset_hash_ck check (sha256 = encode(sha256(content), 'hex')),
    constraint managed_asset_dimensions_ck check (
        (media_type like 'image/%' and width >= 1 and height >= 1) or (media_type not like 'image/%' and width = 0 and height = 0))
);

-- One accepted asset for a subject of a campaign. category is one of the seven separate presentation categories;
-- subject is a stable public reference (a scene, NPC or item definition ID), never a display name. The revision
-- columns are what a late job result is judged by: appearance_revision orders the versions of one subject,
-- source_revision is the campaign state revision the job was requested at, and content_revision_id is the pinned
-- pack revision it was requested under.
create table asset_association (
    campaign_id         uuid        not null,
    category            text        not null check (category in ('scene', 'map', 'portrait', 'item', 'ui', 'music',
                                                                  'effect')),
    subject             text        not null check (subject ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    appearance_revision bigint      not null check (appearance_revision >= 1),
    source_revision     bigint      not null check (source_revision >= 0),
    content_revision_id uuid        not null,
    sha256              text        not null check (sha256 ~ '^[0-9a-f]{64}$'),
    credit              text        not null check (char_length(credit) between 1 and 200 and credit !~ '[[:cntrl:]]'),
    created_at          timestamptz not null,
    primary key (campaign_id, category, subject, appearance_revision)
);
create index asset_association_hash_idx on asset_association (campaign_id, sha256);

-- Input: the association about to be inserted. Output: the row, or an exception. A row needs an existing campaign
-- that still pins the content revision named, stored bytes of the hash named, and no newer appearance of the same
-- subject, so even a caller that bypasses the service cannot make an old result overwrite a newer one. Restores
-- run with triggers off and are checked by the restore's own reference verification.
create function guard_asset_association() returns trigger language plpgsql as $$
begin
    perform 1 from campaign c where c.id = new.campaign_id and c.content_revision_id = new.content_revision_id;
    if not found then
        raise exception using errcode = 'check_violation', constraint = 'asset_association_campaign',
            message = 'asset_association_campaign: an association belongs to a campaign and its pinned content revision';
    end if;
    perform 1 from managed_asset m where m.sha256 = new.sha256;
    if not found then
        raise exception using errcode = 'check_violation', constraint = 'asset_association_bytes',
            message = 'asset_association_bytes: an association names stored asset bytes';
    end if;
    perform 1 from asset_association a where a.campaign_id = new.campaign_id and a.category = new.category
        and a.subject = new.subject and a.appearance_revision > new.appearance_revision;
    if found then
        raise exception using errcode = 'check_violation', constraint = 'asset_association_stale',
            message = 'asset_association_stale: a newer appearance of this subject exists';
    end if;
    return new;
end;
$$;
create trigger asset_association_guard before insert on asset_association
    for each row execute function guard_asset_association();

create trigger managed_asset_immutable before update or delete on managed_asset
    for each row execute function reject_mutation();
create trigger asset_association_immutable before update or delete on asset_association
    for each row execute function reject_mutation();
