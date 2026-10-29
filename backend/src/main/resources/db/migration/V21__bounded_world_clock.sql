-- P10.4: the in-world clock is bounded and moves only with a committed revision.
--
-- campaign.world_time_seconds already only moves forward (V2). These rules add what a bounded clock
-- needs even against a stray writer:
--   * the clock never passes 3,155,760,000 seconds (one hundred 365.25-day years), the same end the
--     engine's WorldClock uses;
--   * time may change only in the update that also advances state_revision by one, so a read, a
--     projection or any statement that leaves the revision alone cannot advance the world;
--   * one revision may add at most 86,400 seconds (one in-world day), the engine's per-operation step.
-- An event stores the clock reading of its commit, so it carries the same upper bound.

alter table campaign
    add constraint campaign_world_time_bounded check (world_time_seconds <= 3155760000);
alter table campaign_event
    add constraint campaign_event_world_time_bounded check (world_time_seconds <= 3155760000);

create function require_world_time_step() returns trigger
    language plpgsql
as $$
begin
    if new.world_time_seconds is distinct from old.world_time_seconds then
        if new.state_revision = old.state_revision then
            raise exception using
                errcode = 'check_violation',
                constraint = 'campaign_world_time_step',
                message = 'campaign_world_time_step: world time may only change together with the state revision';
        end if;
        if new.world_time_seconds - old.world_time_seconds > 86400 then
            raise exception using
                errcode = 'check_violation',
                constraint = 'campaign_world_time_step',
                message = format('campaign_world_time_step: one revision may add at most 86400 seconds, not %s',
                                 new.world_time_seconds - old.world_time_seconds);
        end if;
    end if;
    return new;
end;
$$;

create trigger campaign_world_time_step before update on campaign
    for each row execute function require_world_time_step();
