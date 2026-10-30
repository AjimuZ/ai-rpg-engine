-- P05.7-B.1.1: durable admitted presentation events, delivered through the V11 public stream
-- (docs/13 section 4, docs/23). A row exists only after PresentationEvents.acceptAll admitted the
-- event, so it holds typed references and never a raw draft. None of these rows is world state:
-- writing them never advances state_revision, game_turn, world time, the event sequence or a resource.

-- One row per cue, keyed by campaign, operation and ordinal. The key makes a repeated insert a
-- no-op and the ordinal range bounds an operation to the 16 events of PresentationEvents. The
-- foreign key proves the operation committed at exactly this revision. Checks repeat the shape of
-- PresentationEvent, so a stored row always renders.
create table presentation_event (
    campaign_id        uuid        not null,
    operation_id       uuid        not null,
    ordinal            integer     not null check (ordinal between 0 and 15),
    committed_revision bigint      not null check (committed_revision > 0),
    kind               text        not null check (kind in ('SCENE', 'POSE', 'LINE_AUDIO')),
    scene_id           text        check (scene_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    actor_id           uuid,
    pose_id            text        check (pose_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    expression_id      text        check (expression_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    line_id            text        check (line_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    asset_id           text        check (asset_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    created_at         timestamptz not null,
    primary key (campaign_id, operation_id, ordinal),
    constraint presentation_event_commit_fk foreign key (campaign_id, operation_id, committed_revision)
        references operation_journal (campaign_id, id, committed_revision) on delete restrict,
    constraint presentation_event_actor_fk foreign key (campaign_id, actor_id)
        references actor (campaign_id, id) on delete restrict,
    constraint presentation_event_fields_ck check (case kind
        when 'SCENE' then scene_id is not null and actor_id is null and pose_id is null and expression_id is null
            and line_id is null
        when 'POSE' then scene_id is null and actor_id is not null and expression_id is not null and line_id is null
            and asset_id is null
        else scene_id is null and pose_id is null and expression_id is null and line_id is not null
            and asset_id is not null end)
);
-- Retention removes stream messages only; a cue stays recorded, so inserting it again after its
-- message expired still changes nothing.
create trigger presentation_event_immutable before update or delete on presentation_event
    for each row execute function reject_mutation();

-- One more message type in the same log. A presentation message names its cue; every other type
-- names none, and a cue is announced at most once.
alter table campaign_stream_message
    add column presentation_ordinal integer,
    drop constraint campaign_stream_message_message_type_check,
    add constraint campaign_stream_message_message_type_check check (message_type in ('operation.accepted',
        'operation.progress', 'operation.clarification', 'operation.rejected', 'operation.cancelled',
        'operation.failed', 'state.committed', 'narrative.ready', 'narrative.pending', 'presentation.event')),
    add constraint campaign_stream_message_presentation_ck check (
        (message_type = 'presentation.event') = (presentation_ordinal is not null)),
    add constraint campaign_stream_message_presentation_fk foreign key (campaign_id, operation_id, presentation_ordinal)
        references presentation_event (campaign_id, operation_id, ordinal) on delete restrict;
create unique index campaign_stream_message_presentation_uk
    on campaign_stream_message (campaign_id, operation_id, presentation_ordinal)
    where presentation_ordinal is not null;

-- Input: inserted cue. Output: ignored (AFTER trigger). An insert dropped by ON CONFLICT DO NOTHING
-- fires no AFTER ROW trigger, so a repeat allocates no sequence. The head upsert and the retention
-- of the newest 1000 messages are those of the V11 journal trigger; writers hold the campaign lock
-- first, so this adds no lock-order edge. The message copies the journal's current status and
-- version, which keeps the operation's sequence of announced versions non-decreasing.
create function append_presentation_stream_message() returns trigger language plpgsql as $$
declare
    next_sequence bigint;
begin
    insert into campaign_stream_head as h (campaign_id, last_sequence) values (new.campaign_id, 1)
        on conflict (campaign_id) do update set last_sequence = h.last_sequence + 1
        returning last_sequence into next_sequence;
    insert into campaign_stream_message (campaign_id, sequence, campaign_generation, operation_id,
            operation_version, message_type, status, state_revision, created_at, presentation_ordinal)
        select new.campaign_id, next_sequence, c.recovery_generation, new.operation_id, o.operation_version,
            'presentation.event', o.status, new.committed_revision, clock_timestamp(), new.ordinal
        from campaign c join operation_journal o on o.campaign_id = c.id and o.id = new.operation_id
        where c.id = new.campaign_id;
    delete from campaign_stream_message where campaign_id = new.campaign_id and sequence <= next_sequence - 1000;
    return null;
end;
$$;
create trigger presentation_event_stream after insert on presentation_event
    for each row execute function append_presentation_stream_message();
