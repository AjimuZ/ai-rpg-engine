-- P08.5: publication of validated final narration, the deterministic receipt fallback and
-- narration-only retry rounds (docs/07 sections 6, 8 and 11; docs/13 section 4). None of
-- these rows is canonical world state: writing them never advances state_revision,
-- game_turn, world time, the event sequence, a resource, a receipt or a draw.

-- 1. Retry rounds. Round 1 is the job the canonical commit inserted (P07.5). A
-- narration-only retry appends round n + 1 for the same committed operation; it
-- never re-runs the action and never reopens the journal, whose terminal
-- COMMITTED_NARRATION_PENDING status keeps meaning "the action was saved without
-- narration". Existing rows become round 1, so their behavior is unchanged.
alter table presentation_job
    add column round integer not null default 1 constraint presentation_job_round_ck check (round between 1 and 4),
    drop constraint presentation_job_operation_uk,
    add constraint presentation_job_round_uk unique (campaign_id, operation_id, kind, round);
-- At most one round of an operation is in flight, so two workers can never narrate the
-- same operation at once.
create unique index presentation_job_active_uk on presentation_job (campaign_id, operation_id, kind)
    where status in ('QUEUED', 'RUNNING');
-- At most one round of an operation is ever delivered.
create unique index presentation_job_delivered_uk on presentation_job (campaign_id, operation_id, kind)
    where status = 'DELIVERED';
create trigger presentation_job_round_frozen before update on presentation_job
    for each row execute function reject_frozen_change('presentation_job_round_frozen', 'round');

-- 2. Published narration. Exactly one row per operation, written by the settling
-- transaction of the delivering job round together with that job's DELIVERED status,
-- under the campaign, journal and job locks, and only after the publisher validated the
-- output against the public event bundle and the binding is still the campaign's current
-- locale and glossary generation. Rows are immutable: a later locale switch keeps the
-- accepted text and its passage locale (docs/29 section 5). Speaker names come from the
-- trusted context the narration was requested with, never from model output. Provenance
-- is owner diagnostics and is not projected to the player.
create table narration_publication (
    campaign_id         uuid        not null,
    operation_id        uuid        not null,
    adventure_id        uuid        not null,
    job_id              uuid        not null unique references presentation_job (id) on delete restrict,
    committed_revision  bigint      not null check (committed_revision > 0),
    campaign_generation bigint      not null check (campaign_generation >= 0),
    passage_locale      text        not null check (passage_locale in ('en', 'it')),
    locale_revision     bigint      not null check (locale_revision >= 0),
    glossary_revision   bigint      not null check (glossary_revision >= 0),
    contract_version    integer     not null check (contract_version = 1),
    template_hash       text        not null check (template_hash ~ '^[0-9a-f]{64}$'),
    repaired            boolean     not null,
    passages            jsonb       not null check (jsonb_typeof(passages) = 'array'
        and jsonb_array_length(passages) between 1 and 8),
    suggestions         jsonb       not null check (jsonb_typeof(suggestions) = 'array'
        and jsonb_array_length(suggestions) between 0 and 3),
    runtime             text        check (runtime is null or char_length(runtime) between 1 and 64),
    model               text        check (model is null or char_length(model) between 1 and 128),
    effort              text        check (effort is null or char_length(effort) between 1 and 32),
    published_at        timestamptz not null,
    primary key (campaign_id, operation_id),
    constraint narration_publication_size_ck check (octet_length(passages::text) + octet_length(suggestions::text)
        <= 65536),
    constraint narration_publication_campaign_fk foreign key (adventure_id, campaign_id)
        references campaign (adventure_id, id) on delete restrict,
    constraint narration_publication_commit_fk foreign key (campaign_id, operation_id, committed_revision)
        references operation_journal (campaign_id, id, committed_revision) on delete restrict
);
create trigger narration_publication_immutable before update on narration_publication
    for each row execute function reject_mutation();
create trigger narration_publication_no_delete before delete on narration_publication
    for each row execute function reject_mutation();

-- Input: a new publication row. Output: the row, unchanged. The referenced job must be the
-- RUNNING or DELIVERED round of this exact operation and revision, so a publication can
-- never be attached to another operation's job or to a job that ended without narration.
create function guard_narration_publication() returns trigger language plpgsql as $$
begin
    if not exists (select 1 from presentation_job j where j.id = new.job_id and j.campaign_id = new.campaign_id
            and j.operation_id = new.operation_id and j.committed_revision = new.committed_revision
            and j.adventure_id = new.adventure_id and j.status in ('RUNNING', 'DELIVERED')) then
        raise exception using errcode = 'check_violation', constraint = 'narration_publication_job',
            message = 'narration_publication_job: publication needs the delivering job round';
    end if;
    return new;
end;
$$;
create trigger narration_publication_a_guard before insert on narration_publication
    for each row execute function guard_narration_publication();
