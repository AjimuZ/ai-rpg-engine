-- P07.6: transactional plan-admission gate and activation through ordinary commands
-- (docs/28 sections 7 and 8, docs/07 section 11, QA-36). Nothing here is canonical world
-- state: evaluating a dependency, admitting or invalidating a plan never advances
-- state_revision, game_turn, world time, the event sequence, a resource or a draw, and an
-- activation row exists only beside the committed command that used the plan.

-- 1. Typed version evaluator. A versioned (non-query) plan dependency names one canonical
-- row through a closed grammar, "<kind>:<uuid>", and records the storage row_version it
-- read. Every canonical writer of these rows steps row_version (require_row_version_step),
-- so a changed, moved, spent or removed row makes the recorded version observably stale.
-- Inputs: campaign scope and the reference text. Output: the current row_version, or null
-- when the reference is malformed, of an unsupported kind, absent or owned by another
-- campaign; callers treat null as stale, so foreign identities reveal nothing and fail
-- closed. The function only reads and is safe inside admission, commit and invalidation.
create function plan_reference_version(target_campaign uuid, reference text) returns bigint
    language plpgsql stable as $$
declare
    kind text := split_part(reference, ':', 1);
    target uuid;
begin
    if reference is null or reference !~ ('^(actor|resource|item|character|known-fact|known-quest):'
            || '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') then
        return null;
    end if;
    target := substr(reference, char_length(kind) + 2)::uuid;
    return case kind
        when 'actor' then (select row_version from actor where campaign_id = target_campaign and id = target)
        when 'resource' then (select row_version from actor_resource
                              where campaign_id = target_campaign and id = target)
        when 'item' then (select row_version from item_instance where campaign_id = target_campaign and id = target)
        when 'character' then (select row_version from character_state
                               where campaign_id = target_campaign and actor_id = target)
        when 'known-fact' then (select row_version from campaign_known_fact
                                where campaign_id = target_campaign and id = target)
        when 'known-quest' then (select row_version from campaign_known_quest
                                 where campaign_id = target_campaign and id = target)
    end;
end;
$$;

-- Input: one plan dependency row and its campaign. Output: true when the recorded
-- observation still holds now: a QUERY scope epoch is unchanged (a missing epoch row
-- is epoch 0), or a versioned reference has exactly its expected row_version. A
-- predicate-only or unknown row is never fresh: semantic evaluators belong to P08.7.
create function plan_dependency_fresh(target_campaign uuid, dependency plan_dependency) returns boolean
    language sql stable as $$
    select case
        when dependency.kind = 'QUERY' then dependency.query_epoch = coalesce((select epoch from query_epoch
            where campaign_id = target_campaign and scope_key = dependency.scope_key), 0)
        when dependency.kind in ('ENTITY_FIELD', 'FACT') and dependency.predicate is null
            and dependency.expected_version is not null
            then plan_reference_version(target_campaign, dependency.reference) is not distinct from
                dependency.expected_version
        else false
    end
$$;

-- 2. Admission receipts written by the transactional gate record its version and the
-- typed references it rechecked (campaign entities, pinned content definitions and
-- author constraints). Receipts written before V12 keep both columns null: their
-- artifacts never passed this gate, so activation refuses them.
alter table plan_admission
    add column gate_version text constraint plan_admission_gate_version_ck
        check (gate_version ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    add column checked_references jsonb constraint plan_admission_checked_references_ck
        check (jsonb_typeof(checked_references) = 'object' and octet_length(checked_references::text) <= 32768),
    add constraint plan_admission_gate_ck check ((gate_version is null) = (checked_references is null));

-- 3. Activation. A plan never writes the world; an ordinary canonical command that a
-- trusted resolver built may cite admitted branches it used. The canonical commit
-- rechecks them under the campaign lock and records one row per branch here, in the
-- same transaction as its effects. The primary key makes each artifact branch usable
-- at most once; the deferred trigger proves the citing operation committed at exactly
-- the recorded revision, so a rolled-back or foreign command can never leave a row.
create table plan_activation (
    artifact_id        uuid        not null,
    branch_ref         text        not null check (char_length(branch_ref) between 1 and 128),
    campaign_id        uuid        not null,
    operation_id       uuid        not null,
    committed_revision bigint      not null check (committed_revision > 0),
    created_at         timestamptz not null,
    primary key (artifact_id, branch_ref),
    constraint plan_activation_artifact_fk foreign key (campaign_id, artifact_id)
        references quest_plan_artifact (campaign_id, id) on delete restrict,
    constraint plan_activation_operation_fk foreign key (campaign_id, operation_id)
        references operation_journal (campaign_id, id) on delete restrict
);
create index plan_activation_operation_idx on plan_activation (campaign_id, operation_id);
create trigger plan_activation_immutable before update or delete on plan_activation
    for each row execute function reject_mutation();

-- Input: the inserted activation. Output ignored. Checked at COMMIT so ORM flush order
-- cannot matter; a missing committed origin aborts the whole canonical transaction.
create function require_committed_plan_activation() returns trigger language plpgsql as $$
begin
    if not exists (select 1 from operation_journal where id = new.operation_id and campaign_id = new.campaign_id
            and committed_revision = new.committed_revision) then
        raise exception using errcode = 'check_violation', constraint = 'plan_activation_committed_origin',
            message = 'plan_activation_committed_origin: activation requires its committed command';
    end if;
    return null;
end;
$$;
create constraint trigger plan_activation_committed_origin after insert on plan_activation
    deferrable initially deferred for each row execute function require_committed_plan_activation();
