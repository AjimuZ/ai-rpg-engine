-- P15.4-B: learned creative recipes (docs/10 section 9, ADR 0053). One immutable row per version of one recipe a player
-- saved with the explicit LEARN_DRAFT command, written by the canonical commit in the same transaction as the
-- RECIPE_LEARNED event that announces it. The row keeps the draft as the player wrote it, never a cost the player chose:
-- complexity, action points, Focus and cooldown are what the engine derived from the creative profile named here when
-- the command previewed the draft again under the campaign lock. Learning a recipe ID again adds the next version; the
-- latest version is the one an encounter entry previews again and freezes. Campaigns whose content does not enable the
-- creativeAbilities module never write here.

create table learned_recipe (
    campaign_id            uuid    not null references campaign (id) on delete restrict,
    actor_id               uuid    not null,
    recipe_id              text    not null check (recipe_id ~ '^[a-z][a-z0-9-]{0,31}$'),
    -- 1..99 (LearnedRecipes.MAX_VERSIONS); versions of one recipe are consecutive.
    version                integer not null check (version between 1 and 99),
    label                  text    not null check (char_length(label) between 1 and 80),
    -- The draft's wire form: label, targets, maxTargets, range and 1..4 steps; no cost field exists.
    draft                  jsonb   not null check (jsonb_typeof(draft) = 'object'
                                                   and octet_length(draft::text) <= 4096),
    -- The creative profile the draft was previewed against: its declaring pack and canonical rule ID.
    profile_pack_id        text    not null check (char_length(profile_pack_id) between 1 and 256),
    profile_id             text    not null check (char_length(profile_id) between 1 and 256),
    content_revision_id    uuid    not null references content_revision (id) on delete restrict,
    complexity             integer not null check (complexity between 1 and 32),
    action_points          integer not null check (action_points between 1 and 99),
    focus                  integer not null check (focus between 0 and 99),
    cooldown_rounds        integer not null check (cooldown_rounds between 0 and 30),
    learned_revision       bigint  not null check (learned_revision > 0),
    learned_event_sequence bigint  not null,
    learned_world_time     bigint  not null check (learned_world_time >= 0),
    primary key (campaign_id, actor_id, recipe_id, version),
    constraint learned_recipe_actor_fk foreign key (campaign_id, actor_id)
        references actor (campaign_id, id) on delete restrict,
    constraint learned_recipe_event_fk foreign key (campaign_id, learned_event_sequence)
        references campaign_event (campaign_id, sequence) on delete restrict,
    -- One learning per announcing event.
    constraint learned_recipe_event_uk unique (campaign_id, learned_event_sequence)
);
create trigger learned_recipe_immutable before update or delete on learned_recipe
    for each row execute function reject_mutation();
-- The announcing event must be an event of the revision that records the recipe.
create trigger learned_recipe_provenance before insert on learned_recipe
    for each row execute function require_event_at_revision('learned_event_sequence', 'learned_revision');

-- A version follows its predecessor, and one actor holds at most 8 distinct recipes (LearnedRecipes.MAX_RECIPES).
create function require_learned_recipe_bounds() returns trigger language plpgsql as $$
begin
    if new.version > 1 and not exists (select 1 from learned_recipe r where r.campaign_id = new.campaign_id
            and r.actor_id = new.actor_id and r.recipe_id = new.recipe_id and r.version = new.version - 1) then
        raise exception using errcode = 'check_violation', constraint = 'learned_recipe_version_sequence',
            message = format('learned_recipe_version_sequence: version %s of %s has no predecessor', new.version,
                             new.recipe_id);
    end if;
    if new.version = 1 and (select count(distinct r.recipe_id) from learned_recipe r
            where r.campaign_id = new.campaign_id and r.actor_id = new.actor_id) >= 8 then
        raise exception using errcode = 'check_violation', constraint = 'learned_recipe_count',
            message = 'learned_recipe_count: an actor holds at most 8 recipes';
    end if;
    return new;
end;
$$;
create trigger learned_recipe_bounds before insert on learned_recipe
    for each row execute function require_learned_recipe_bounds();
