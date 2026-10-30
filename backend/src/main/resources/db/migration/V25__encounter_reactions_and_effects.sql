-- P11.4: predeclared reactions, timed effects and ability limits of an encounter (docs/11 sections 7, 10 and 11, ADR 0031).
--
-- Each participant row gains its active effect instances, predeclared reaction policies and ability uses, written with the
-- row's other final facts by one canonical commit. The encounter row gains the application counter that orders simultaneous
-- effects like the events announcing them. The frozen definition holds what the instances mean, so nothing here reads the
-- pack. Existing rows keep empty values: a fight frozen earlier has no effects, and without a stored policy no reaction
-- fires, so upgrading never changes a fight in progress.
--
-- The trigger repeats what storage can see for itself: the stored shapes and bounds, a reaction budget within the frozen
-- allowance, encounter-local Focus within its frozen maximum, ability uses that only grow, and no effect on a participant
-- that does not stand. The rules that need the whole fight remain in CombatScheduler and CombatEffects.

alter table encounter
    add column effects_applied integer not null default 0 check (effects_applied >= 0);

create trigger encounter_effects_applied before update on encounter
    for each row execute function reject_counter_decrease('encounter_effects_applied', 'effects_applied');

alter table encounter_participant
    add column effects           jsonb not null default '[]'::jsonb check (jsonb_typeof(effects) = 'array'
                                     and jsonb_array_length(effects) <= 8),
    add column reaction_policies jsonb not null default '{}'::jsonb check (jsonb_typeof(reaction_policies) = 'object'),
    add column ability_uses      jsonb not null default '{}'::jsonb check (jsonb_typeof(ability_uses) = 'object'),
    add constraint encounter_participant_effects_ck check (effects = '[]'::jsonb or status = 'ACTIVE');

create function encounter_participant_timers() returns trigger language plpgsql as $$
declare
    frozen_profile jsonb;
    ability text;
begin
    select profile into frozen_profile from encounter where id = new.encounter_id;
    if new.reactions > (frozen_profile -> 'values' ->> 'reactionsPerRound')::integer then
        raise exception using errcode = 'check_violation', constraint = 'encounter_participant_timers',
            message = 'encounter_participant_timers: reactions exceed the frozen allowance';
    end if;
    if new.focus_current is not null and new.focus_current > (new.frozen ->> 'focusMax')::integer then
        raise exception using errcode = 'check_violation', constraint = 'encounter_participant_timers',
            message = 'encounter_participant_timers: Focus exceeds the frozen maximum';
    end if;
    for ability in select jsonb_object_keys(old.ability_uses) loop
        if not new.ability_uses ? ability
                or (new.ability_uses -> ability ->> 'uses')::integer < (old.ability_uses -> ability ->> 'uses')::integer
                or (new.ability_uses -> ability ->> 'lastRound')::integer < (old.ability_uses -> ability ->> 'lastRound')::integer then
            raise exception using errcode = 'check_violation', constraint = 'encounter_participant_timers',
                message = 'encounter_participant_timers: an ability use never goes back';
        end if;
    end loop;
    return new;
end $$;
create trigger encounter_participant_timers before update on encounter_participant
    for each row execute function encounter_participant_timers();
