-- P11.3: attacks, defenses, damage and healing inside an encounter (docs/11 sections 8-9, ADR 0030).
--
-- A participant's Guard stance (the Guard action, +2 until the start of its next activation) is the only new stored
-- fact: every attack calculation is recorded in the operation's events and draws, not in a table. The trigger repeats
-- the guarantees of the rules for encounter-local pools, so no writer can overheal a participant, leave one standing
-- at zero Health or bring a defeated one back. The player's Health stays in actor_resource, whose own bounds apply.

alter table encounter_participant
    add column guarding boolean not null default false,
    add constraint encounter_participant_guarding_ck check (not guarding or status = 'ACTIVE');

create function encounter_participant_vitals() returns trigger language plpgsql as $$
begin
    if old.status = 'DEFEATED' and new.status <> 'DEFEATED' then
        raise exception using errcode = 'check_violation', constraint = 'encounter_participant_vitals',
            message = 'encounter_participant_vitals: a defeated participant does not return';
    end if;
    if new.health_current is not null then
        if new.health_current > (new.frozen ->> 'healthMax')::integer then
            raise exception using errcode = 'check_violation', constraint = 'encounter_participant_vitals',
                message = 'encounter_participant_vitals: Health exceeds the frozen maximum';
        end if;
        if old.health_current = 0 and new.health_current > 0 then
            raise exception using errcode = 'check_violation', constraint = 'encounter_participant_vitals',
                message = 'encounter_participant_vitals: ordinary healing does not revive';
        end if;
        if new.health_current = 0 and new.status = 'ACTIVE' then
            raise exception using errcode = 'check_violation', constraint = 'encounter_participant_vitals',
                message = 'encounter_participant_vitals: zero Health cannot stand';
        end if;
    end if;
    return new;
end $$;
create trigger encounter_participant_vitals before update on encounter_participant
    for each row execute function encounter_participant_vitals();
