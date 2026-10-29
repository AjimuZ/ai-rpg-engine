-- P10.1: active conditions and equipment assignments. Both are character state owned by one
-- actor; they change only through the character service (conditions apply and expire by
-- explicit rules, equipment is checked against slots and hands), never by generic patches.

-- Equipment assignments name the owner of the item instance, so an item cannot be worn by an
-- actor that does not hold it. A transfer of an equipped instance is blocked by the foreign key
-- below until it has been unequipped.
alter table item_instance add constraint item_instance_owner_uk unique (campaign_id, actor_id, id);

-- Active conditions: one row per actor and condition. Stacks and remaining rounds are bounded here
-- as well as by the pack compiler, so a stray SQL writer cannot create an unbounded modifier.
create table actor_condition (
    campaign_id uuid not null references campaign (id) on delete restrict,
    actor_id uuid not null,
    condition_id text not null check (char_length(condition_id) between 1 and 256),
    stacks integer not null check (stacks between 1 and 10),
    remaining_rounds integer not null check (remaining_rounds between 1 and 10000),
    applied_revision bigint not null check (applied_revision >= 0),
    updated_revision bigint not null check (updated_revision >= 0),
    row_version bigint not null default 0 check (row_version >= 0),
    primary key (campaign_id, actor_id, condition_id),
    foreign key (campaign_id, actor_id) references actor (campaign_id, id) on delete restrict,
    check (updated_revision >= applied_revision)
);
create trigger actor_condition_frozen before update on actor_condition
    for each row execute function reject_frozen_change('actor_condition_frozen', 'campaign_id', 'actor_id',
        'condition_id', 'applied_revision');
create trigger actor_condition_row_version before update on actor_condition
    for each row execute function require_row_version_step();

-- Equipment assignments: one row per worn item instance (the primary key makes an instance
-- wearable once). The slot and hands are copied from the pinned definition when the item is
-- equipped and are frozen, so later content lookups cannot silently change what is worn.
create table equipment_assignment (
    campaign_id uuid not null references campaign (id) on delete restrict,
    actor_id uuid not null,
    item_instance_id uuid not null,
    slot_id text not null check (char_length(slot_id) between 1 and 256),
    hands integer not null check (hands between 0 and 4),
    equipped_revision bigint not null check (equipped_revision >= 0),
    primary key (campaign_id, item_instance_id),
    foreign key (campaign_id, actor_id) references actor (campaign_id, id) on delete restrict,
    foreign key (campaign_id, actor_id, item_instance_id) references item_instance (campaign_id, actor_id, id)
        on delete restrict
);
create index equipment_assignment_actor_idx on equipment_assignment (campaign_id, actor_id);
create trigger equipment_assignment_frozen before update on equipment_assignment
    for each row execute function reject_frozen_change('equipment_assignment_frozen', 'campaign_id', 'actor_id',
        'item_instance_id', 'slot_id', 'hands', 'equipped_revision');

-- Storage-level bounds that hold for every pack: a stack cannot be worn (an assignment names one
-- instance) and no actor occupies more than the largest hand count a pack may declare. Pack
-- specific limits (slot capacity, the pack's own hand count) are enforced by the character service.
create function equipment_assignment_bounds() returns trigger language plpgsql as $$
begin
    if (select maximum_stack from item_instance where campaign_id = new.campaign_id and id = new.item_instance_id) <> 1 then
        raise exception 'equipment_assignment_stack' using errcode = '23514';
    end if;
    if (select coalesce(sum(hands), 0) from equipment_assignment
            where campaign_id = new.campaign_id and actor_id = new.actor_id) + new.hands > 4 then
        raise exception 'equipment_assignment_hands' using errcode = '23514';
    end if;
    return new;
end $$;
create trigger equipment_assignment_bounds before insert on equipment_assignment
    for each row execute function equipment_assignment_bounds();
