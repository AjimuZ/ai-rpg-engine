-- P03.3: durable notification intents written by the canonical commit transaction.
-- Envelopes contain references only, never private event bodies or narration text.
-- Dispatch/acknowledgment and retention are separate later worker responsibilities.
alter table operation_journal
    add constraint operation_journal_commit_reference_uq
        unique (campaign_id, id, committed_revision);

create table canonical_outbox (
    id uuid primary key,
    campaign_id uuid not null,
    operation_id uuid not null,
    state_revision bigint not null check (state_revision > 0),
    kind text not null check (kind in ('PUBLIC_STATE', 'NARRATION_REQUIRED')),
    created_at timestamptz not null,
    constraint canonical_outbox_operation_kind_uq unique (campaign_id, operation_id, kind),
    -- Hibernate can insert an envelope before flushing the journal UPDATE. Check the
    -- complete committed identity at COMMIT, so an uncommitted/mismatched reference
    -- can never become visible even when the insert itself succeeds.
    constraint canonical_outbox_committed_operation_fk
        foreign key (campaign_id, operation_id, state_revision)
        references operation_journal (campaign_id, id, committed_revision)
        deferrable initially deferred
);

-- Per-campaign committed ordering; message identity breaks the two-kind tie.
create index canonical_outbox_campaign_revision_idx
    on canonical_outbox (campaign_id, state_revision, id);

-- Immutable intents survive delivery failure; future delivery state is kept separately.
create trigger canonical_outbox_immutable
    before update or delete on canonical_outbox
    for each row execute function reject_mutation();
