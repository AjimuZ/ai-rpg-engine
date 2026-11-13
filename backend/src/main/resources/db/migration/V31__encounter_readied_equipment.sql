-- P11.5: the player's equipment-derived fighting values after a Ready/equip action (docs/11 section 7, ADR 0034).
--
-- The frozen profile of a participant never changes (V22), so a Ready/equip action cannot rewrite the Guard, armor and
-- attacks it froze at entry. The player's participant row instead gains `readied`, canonical JSON with the Guard, armor,
-- attacks and worn items the action computed, which the engine reads in place of the frozen values for the rest of the fight.
-- The equipment assignment itself stays in equipment_assignment, written by the same canonical commit. Existing rows keep a
-- null: a fight frozen earlier has readied nothing, so upgrading never changes it.
--
-- The checks repeat what storage can see for itself: only an actor-backed participant (the player) wears campaign items, the
-- value is an object with its four members of the right JSON types, and once set it is replaced by the next action, never
-- removed. Which item may be
-- readied and what it is worth remain in CommandRules and ReadiedGear, which need the pinned item rules.

-- A missing member reads as 'missing' rather than null, because a null check result would let the row through.
alter table encounter_participant
    add column readied jsonb check (readied is null or jsonb_typeof(readied) = 'object'
        and coalesce(jsonb_typeof(readied -> 'guard'), 'missing') = 'number'
        and coalesce(jsonb_typeof(readied -> 'armor'), 'missing') = 'number'
        and coalesce(jsonb_typeof(readied -> 'attacks'), 'missing') = 'array'
        and coalesce(jsonb_typeof(readied -> 'equipment'), 'missing') = 'array'),
    add constraint encounter_participant_readied_ck check (readied is null or actor_id is not null);

create function encounter_participant_readied_kept() returns trigger language plpgsql as $$
begin
    if old.readied is not null and new.readied is null then
        raise exception using errcode = 'check_violation', constraint = 'encounter_participant_readied_kept',
            message = 'encounter_participant_readied_kept: readied equipment is replaced, never removed';
    end if;
    return new;
end $$;
create trigger encounter_participant_readied_kept before update on encounter_participant
    for each row execute function encounter_participant_readied_kept();
