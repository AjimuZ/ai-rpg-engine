-- P17.6-D: durable runtime image jobs, their budget charges and the provenance of accepted images (docs/23 section 4,
-- docs/adr/0056).
--
-- One row per job that RuntimeImageJobs queued. The row outlives the process: a restart resumes QUEUED rows in their
-- order, ends RUNNING rows of the previous process without sending them again, and keeps every charge the budget
-- counted, so neither a result nor a charge is duplicated or lost. Terminal rows are kept as the job history the
-- playthrough screen reads its pending and failed states from.
--
-- appearance is the public appearance text the job was asked with; the game flow builds it from public pack fields
-- only (docs/23 section 3), and it is kept so a queued job can resume after a restart. No other prompt text, seed,
-- path, URL, credential or backend address is stored. provenance holds the hashes and settings the image adapter
-- reported plus the acceptance review, never prompt text.
--
-- Like asset_association (V37) the table has no foreign key, so truncating the campaign tables does not depend on it;
-- a row whose campaign no longer exists is ended by the startup recovery. The ordinal comes from a plain sequence,
-- not an identity column, because a campaign restore refuses tables with identity columns it does not know.

create sequence runtime_image_job_order;

create table runtime_image_job (
    id                  uuid        primary key,
    ordinal             bigint      not null default nextval('runtime_image_job_order'),
    adventure_id        uuid        not null,
    campaign_id         uuid        not null,
    category            text        not null check (category in ('scene', 'portrait', 'item')),
    subject             text        not null check (subject ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    appearance_revision bigint      not null check (appearance_revision >= 1),
    appearance          text        not null check (char_length(appearance) between 1 and 1000
                                                    and appearance !~ '[[:cntrl:]]'),
    source_revision     bigint      not null check (source_revision >= 0),
    content_revision_id uuid        not null,
    recovery_generation bigint      not null check (recovery_generation >= 0),
    status              text        not null check (status in ('QUEUED', 'RUNNING', 'ACCEPTED', 'REUSED',
        'STALE_APPEARANCE', 'STALE_SOURCE', 'STALE_CONTENT', 'STALE_GENERATION', 'SUPERSEDED', 'CANCELLED', 'FAILED')),
    error_code          text        check (error_code ~ '^[a-z0-9][a-z0-9-]{0,63}$'),
    -- The instant the budget counts; null once the charge was given back (the job ended before it was sent).
    charged_at          timestamptz,
    -- The process instance that sent the job; only that instance may settle it.
    runner_id           uuid,
    sha256              text        check (sha256 ~ '^[0-9a-f]{64}$'),
    provenance          jsonb       check (provenance is null or jsonb_typeof(provenance) = 'object'),
    created_at          timestamptz not null,
    sent_at             timestamptz,
    settled_at          timestamptz,
    constraint runtime_image_job_open_ck check ((status in ('QUEUED', 'RUNNING')) = (settled_at is null)),
    constraint runtime_image_job_runner_ck check (status <> 'RUNNING' or (runner_id is not null and sent_at is not null)),
    constraint runtime_image_job_failure_ck check ((status = 'FAILED') = (error_code is not null)),
    constraint runtime_image_job_accepted_ck check ((status = 'ACCEPTED') = (sha256 is not null and provenance is not null))
);

-- At most one open job per subject and appearance of a campaign: equal requests coalesce even across a restart.
create unique index runtime_image_job_open_uk on runtime_image_job (campaign_id, category, subject, appearance_revision)
    where status in ('QUEUED', 'RUNNING');
create index runtime_image_job_budget_idx on runtime_image_job (campaign_id, charged_at) where charged_at is not null;
create index runtime_image_job_subject_idx on runtime_image_job (campaign_id, category, subject, ordinal);
create index runtime_image_job_open_idx on runtime_image_job (ordinal) where status in ('QUEUED', 'RUNNING');
