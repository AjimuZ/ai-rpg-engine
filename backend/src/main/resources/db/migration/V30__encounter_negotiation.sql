-- P11.5: terms on the table and accepted truces of an encounter, and surrender as a lasting standing (docs/11 sections 7.1
-- and 13, ADR 0033).
--
-- The encounter row gains the one offer waiting for the player's explicit answer and the truce the player accepted. Both
-- are canonical JSON written with the row's other facts by one canonical commit; the frozen definition holds what the terms
-- mean. Existing rows keep nulls: a fight frozen earlier has no negotiation under way, so upgrading never changes it.
--
-- The checks and triggers repeat what storage can see for itself: an offer waits only at a decision, a truce appears only
-- with the outcome it causes and never changes, and a surrendered participant does not return. Which response a recipient
-- gives and who may accept remain in Negotiation and CombatScheduler, which need the whole fight.

alter table encounter
    add column open_offer jsonb check (open_offer is null or jsonb_typeof(open_offer) = 'object'),
    add column agreement  jsonb check (agreement is null or jsonb_typeof(agreement) = 'object'),
    add constraint encounter_open_offer_ck check (open_offer is null or phase = 'AWAITING_ACTION'),
    add constraint encounter_agreement_ck check (agreement is null
        or phase in ('OUTCOME_PENDING', 'SETTLED', 'NARRATIVE_RESUME'));

create function encounter_agreement_once() returns trigger language plpgsql as $$
begin
    if old.agreement is not null and new.agreement is distinct from old.agreement then
        raise exception using errcode = 'check_violation', constraint = 'encounter_agreement_once',
            message = 'encounter_agreement_once: an accepted agreement never changes';
    end if;
    return new;
end $$;
create trigger encounter_agreement_once before update on encounter
    for each row execute function encounter_agreement_once();

create function encounter_participant_surrender() returns trigger language plpgsql as $$
begin
    if old.status = 'SURRENDERED' and new.status <> 'SURRENDERED' then
        raise exception using errcode = 'check_violation', constraint = 'encounter_participant_surrender',
            message = 'encounter_participant_surrender: a surrendered participant does not return';
    end if;
    return new;
end $$;
create trigger encounter_participant_surrender before update on encounter_participant
    for each row execute function encounter_participant_surrender();
