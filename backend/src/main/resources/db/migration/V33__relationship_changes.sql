-- P15.1-B: relationship changes with their causes (docs/10 section 6, ADR 0050). One immutable row per change of
-- one NPC's bounded dimension toward another actor, written by the canonical commit in the same transaction as the
-- event that caused it. The current value of a relationship is value_after of its latest row (by revision, then
-- ordinal), or the dimension's authored initial value when it has none; companion membership is derived from the
-- same rows, and the rows that crossed the threshold carry JOINED or LEFT. Campaigns whose content does not enable
-- the relationships module never write here.

create table relationship_change (
    campaign_id          uuid    not null references campaign (id) on delete restrict,
    changed_revision     bigint  not null check (changed_revision > 0),
    -- Order of the change within its commit, 0..63 (Relationships.MAX_CHANGES).
    ordinal              integer not null check (ordinal between 0 and 63),
    holder_actor_id      uuid    not null,
    subject_actor_id     uuid    not null,
    dimension_id         text    not null check (dimension_id ~ '^[a-z][a-z0-9._:-]{0,127}$'),
    cause                text    not null check (cause in ('SPOKEN_TO', 'GIFT', 'TRADE', 'PROMISE_KEPT',
                                                           'PROMISE_BROKEN')),
    cause_event_sequence bigint  not null,
    commitment_id        uuid,
    delta                integer not null check (delta between -10 and 10 and delta <> 0),
    value_before         integer not null check (value_before between -100 and 100),
    value_after          integer not null check (value_after between -100 and 100),
    membership           text    check (membership is null or membership in ('JOINED', 'LEFT')),
    changed_world_time   bigint  not null check (changed_world_time >= 0),
    primary key (campaign_id, changed_revision, ordinal),
    constraint relationship_change_holder_fk foreign key (campaign_id, holder_actor_id)
        references actor (campaign_id, id) on delete restrict,
    constraint relationship_change_subject_fk foreign key (campaign_id, subject_actor_id)
        references actor (campaign_id, id) on delete restrict,
    constraint relationship_change_cause_fk foreign key (campaign_id, cause_event_sequence)
        references campaign_event (campaign_id, sequence) on delete restrict,
    constraint relationship_change_commitment_fk foreign key (campaign_id, commitment_id)
        references actor_commitment (campaign_id, id) on delete restrict,
    constraint relationship_change_parties_ck check (holder_actor_id <> subject_actor_id),
    -- A recorded change moves the value; a clamped no-op is not a change.
    constraint relationship_change_moves_ck check (value_after <> value_before),
    -- A promise names the commitment it resolved; every other cause is an event alone.
    constraint relationship_change_commitment_ck check ((cause in ('PROMISE_KEPT', 'PROMISE_BROKEN'))
        = (commitment_id is not null))
);
create index relationship_change_current_idx on relationship_change
    (campaign_id, holder_actor_id, subject_actor_id, dimension_id, changed_revision desc, ordinal desc);
create trigger relationship_change_immutable before update or delete on relationship_change
    for each row execute function reject_mutation();
-- The cause must be an event of the revision that records the change.
create trigger relationship_change_provenance before insert on relationship_change
    for each row execute function require_event_at_revision('cause_event_sequence', 'changed_revision');
