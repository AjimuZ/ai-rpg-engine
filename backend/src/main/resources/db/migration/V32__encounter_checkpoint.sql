-- P11.6-A: the operational checkpoint records the active encounter (docs/08 sections 3 and 10, docs/11 section 2,
-- ADR 0037).
--
-- V16 kept an "encounter" key that was always null, because no encounter existed before P11. This migration replaces
-- memory_operational_snapshot with the same layout plus the campaign's active encounter (a phase other than SETTLED and
-- NARRATIVE_RESUME; V22 allows at most one): its lifecycle cursor, the time boundary of the round in progress, the
-- negotiation on the table, and every participant's mutable tactical facts (zone, standing, local pools, initiative,
-- reaction budget and policies, stance, effect instances with their clocks, ability uses and readied values). Frozen
-- content appears only as its stored SHA-256, because V22 never lets it change. Everything else the snapshot already held
-- keeps its form: the player's pools stay under "resources", ammunition under "items" and the RNG cursor under "rng".
-- A campaign without an active encounter keeps "encounter": null, so its snapshot is exactly the V16 one.
--
-- roundEndsAt mirrors CombatScheduler: while the cursor is set (an activation is in progress, or the fight was decided
-- inside a round) the round in progress still owes its roundSeconds of world time, which its round end or the settlement
-- adds exactly once; the boundary is then the current world time plus those seconds. Without a cursor nothing is owed
-- (before the first round, or at a round end that already added its time) and roundEndsAt is null.
--
-- This is layout version 2 of the snapshot (OperationalCheckpoints.SNAPSHOT_SCHEMA_VERSION). A version 1 checkpoint
-- is reported UNSUPPORTED, so restart reconciliation discards it without invalidating any summary and captures a new
-- one, instead of reporting a fight in progress as DIVERGED. No table, row or canonical value changes here.
create or replace function memory_operational_snapshot(target uuid) returns jsonb
    language sql stable as $$
select jsonb_build_object(
    'kind', 'operational',
    'campaign', jsonb_build_object(
        'adventureId', c.adventure_id,
        'contentRevisionId', c.content_revision_id,
        'mode', c.mode,
        'pendingDecision', c.pending_decision,
        'stateRevision', c.state_revision,
        'gameTurn', c.game_turn,
        'worldTimeSeconds', c.world_time_seconds,
        'eventSequence', c.event_sequence),
    'playerActorId', (select actor_id from campaign_creation where campaign_id = c.id),
    'actors', (select coalesce(jsonb_agg(jsonb_build_object('id', a.id, 'kind', a.kind,
            'definitionId', a.definition_id, 'name', a.display_name, 'location', a.location_id,
            'rowVersion', a.row_version) order by a.id), '[]'::jsonb)
        from actor a where a.campaign_id = c.id),
    'resources', (select coalesce(jsonb_agg(jsonb_build_object('actorId', r.actor_id, 'key', r.resource_key,
            'minimum', r.minimum_value, 'current', r.current_value, 'maximum', r.maximum_value)
            order by r.actor_id, r.resource_key), '[]'::jsonb)
        from actor_resource r where r.campaign_id = c.id),
    'items', (select coalesce(jsonb_agg(jsonb_build_object('id', i.id, 'actorId', i.actor_id,
            'definitionId', i.definition_id, 'quantity', i.quantity) order by i.id), '[]'::jsonb)
        from item_instance i where i.campaign_id = c.id),
    'abilities', (select coalesce(jsonb_agg(jsonb_build_object('actorId', l.actor_id,
            'definitionId', l.definition_id, 'rank', l.rank) order by l.actor_id, l.definition_id), '[]'::jsonb)
        from learned_ability l where l.campaign_id = c.id),
    'quests', (select coalesce(jsonb_agg(jsonb_build_object('id', q.id, 'actorId', q.actor_id,
            'status', q.status, 'updatedRevision', q.updated_revision) order by q.id), '[]'::jsonb)
        from campaign_known_quest q where q.campaign_id = c.id),
    'openCommitments', (select coalesce(jsonb_agg(jsonb_build_object('id', m.id, 'kind', m.kind,
            'debtorId', m.debtor_actor_id, 'creditorId', m.creditor_actor_id, 'dueWorldTime', m.due_world_time)
            order by m.id), '[]'::jsonb)
        from actor_commitment m where m.campaign_id = c.id and m.status = 'OPEN'),
    'pendingNarration', (select coalesce(jsonb_agg(jsonb_build_object('operationId', o.id,
            'status', o.status, 'committedRevision', o.committed_revision)
            order by o.committed_revision, o.id), '[]'::jsonb)
        from operation_journal o where o.campaign_id = c.id
            and o.status in ('COMMITTED', 'NARRATING', 'COMMITTED_NARRATION_PENDING')),
    'rng', (select jsonb_build_object('algorithm', s.algorithm, 'algorithmVersion', s.algorithm_version,
            'draws', (select count(*) from operation_random_draw d where d.campaign_id = c.id))
        from campaign_random_seed s where s.campaign_id = c.id),
    'encounter', (select jsonb_build_object(
            'id', e.id,
            'templateId', e.template_id,
            'locationId', e.location_id,
            'phase', e.phase,
            'round', e.round,
            'actorCursor', e.actor_cursor,
            'actionPoints', e.action_points,
            'speechUsed', e.speech_used,
            'roundSeconds', (e.profile -> 'values' ->> 'roundSeconds')::integer,
            'roundEndsAt', case when e.actor_cursor is not null
                then c.world_time_seconds + (e.profile -> 'values' ->> 'roundSeconds')::integer end,
            'openingStatus', e.opening_status,
            'passages', e.passages,
            'effectsApplied', e.effects_applied,
            'openOffer', e.open_offer,
            'agreement', e.agreement,
            'profileHash', e.profile_hash,
            'definitionHash', e.definition_hash,
            'updatedRevision', e.updated_revision,
            'rowVersion', e.row_version,
            'participants', (select coalesce(jsonb_agg(jsonb_build_object('id', p.participant_id,
                    'ordinal', p.ordinal, 'actorId', p.actor_id, 'side', p.side, 'zone', p.zone_id,
                    'status', p.status, 'health', p.health_current, 'focus', p.focus_current,
                    'initiative', p.initiative, 'initiativeRank', p.initiative_rank, 'reactions', p.reactions,
                    'guarding', p.guarding, 'effects', p.effects, 'reactionPolicies', p.reaction_policies,
                    'abilityUses', p.ability_uses, 'readied', p.readied, 'rowVersion', p.row_version)
                    order by p.ordinal), '[]'::jsonb)
                from encounter_participant p where p.encounter_id = e.id))
        from encounter e where e.campaign_id = c.id and e.phase not in ('SETTLED', 'NARRATIVE_RESUME')))
from campaign c where c.id = target
$$;
