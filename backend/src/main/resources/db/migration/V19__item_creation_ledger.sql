-- P10.2: source-linked item creation. Every unit that enters a campaign after creation (an effect
-- that creates an item, a settled reward) is written here, in the same transaction as the item
-- rows it explains, with the operation and the event that announced it. Moving, trading,
-- equipping and consuming never create units, so for every definition:
--   units held now = starting loadout + created in this ledger - consumed (ITEM_USED events).
-- The ledger is append-only and has no foreign key to item_instance, because a consumed or fully
-- transferred stack may be removed while its creation history must remain.

create table item_creation (
    id uuid primary key,
    campaign_id uuid not null references campaign (id) on delete restrict,
    operation_id uuid not null,
    event_sequence bigint not null check (event_sequence > 0),
    -- EFFECT: an effect node of an ability or item use, keyed '<operation>#<effect path>'.
    -- REWARD: a settled reward, keyed by the reward settlement's source key.
    source_kind text not null check (source_kind in ('EFFECT', 'REWARD')),
    source_key text not null check (char_length(source_key) between 1 and 512),
    recipient_actor_id uuid not null,
    definition_id text not null check (char_length(definition_id) between 1 and 256),
    quantity integer not null check (quantity between 1 and 9999),
    applied_revision bigint not null check (applied_revision > 0),
    created_at timestamptz not null,
    -- One creation per source, recipient and definition: a replayed source can never pay twice.
    constraint item_creation_source_uk unique (campaign_id, source_kind, source_key, recipient_actor_id, definition_id),
    constraint item_creation_operation_fk foreign key (campaign_id, operation_id)
        references operation_journal (campaign_id, id) on delete restrict,
    constraint item_creation_event_fk foreign key (campaign_id, event_sequence)
        references campaign_event (campaign_id, sequence) on delete restrict,
    constraint item_creation_recipient_fk foreign key (campaign_id, recipient_actor_id)
        references actor (campaign_id, id) on delete restrict
);
create index item_creation_operation_idx on item_creation (campaign_id, operation_id);
create trigger item_creation_immutable before update or delete on item_creation
    for each row execute function reject_mutation();
