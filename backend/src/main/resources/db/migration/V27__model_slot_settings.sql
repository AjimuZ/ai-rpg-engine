-- P21.3-A: owner assignments of the four model slots (ADR 0021 decisions 2 and 8, docs/34
-- sections 4, 11 and 12). One row per slot the owner assigned through the settings API, and an
-- ordered list of configured fallbacks per slot. Settings are installation-wide and private
-- owner data: they are not campaign state, so writing them never advances state_revision,
-- game_turn, world time or the event sequence, and no row refers to a campaign or an owner row.
-- They hold identifiers only: no key, endpoint, account or prompt text is ever stored here.
--
-- backend_class is the billing and privacy class the assignment had when it was saved. Changing it
-- needs an acknowledgement of the new class, which is recorded in acknowledged_class and
-- acknowledged_at; the probe_* columns keep the last readiness result so it survives a restart.
create table inference_slot_setting (
    slot               text        primary key check (slot in ('text.light', 'text.standard', 'text.deliberate', 'image')),
    backend            text        not null check (backend ~ '^[a-z][a-z0-9-]{0,31}$'),
    model              text        not null check (model ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'),
    effort             text        not null check (effort ~ '^[a-z]{1,32}$'),
    -- Only an openai-compatible endpoint can be local or metered; every other backend has one class.
    declared_class     text        check (declared_class in ('local', 'metered')),
    backend_class      text        not null check (backend_class in ('local', 'subscription', 'metered')),
    acknowledged_class text        check (acknowledged_class in ('local', 'subscription', 'metered')),
    acknowledged_at    timestamptz,
    probe_state        text        not null default 'NOT_RUN' check (probe_state in ('NOT_RUN', 'READY', 'FAILED', 'UNREACHABLE')),
    probe_code         text        check (probe_code ~ '^[a-z][a-z0-9-]{0,63}$'),
    probe_at           timestamptz,
    probe_elapsed_ms   bigint      check (probe_elapsed_ms >= 0),
    row_version        bigint      not null default 0 check (row_version >= 0),
    updated_at         timestamptz not null,
    constraint inference_slot_setting_declared_ck
        check ((backend = 'openai-compatible') = (declared_class is not null)),
    constraint inference_slot_setting_image_ck
        check ((slot = 'image') = (backend in ('local-image', 'a1111-http', 'openai-images'))),
    constraint inference_slot_setting_image_effort_ck check (slot <> 'image' or effort = 'none'),
    constraint inference_slot_setting_ack_ck check ((acknowledged_class is null) = (acknowledged_at is null)),
    constraint inference_slot_setting_probe_ck check (
        (probe_state = 'NOT_RUN' and probe_code is null and probe_at is null and probe_elapsed_ms is null)
        or (probe_state <> 'NOT_RUN' and probe_at is not null and probe_elapsed_ms is not null))
);

-- Configured fallbacks, in the owner's order of preference. The service refuses an entry whose
-- class differs from the slot's class, and the class is never stored per entry: it is derived
-- from the backend (and the slot's declared class) every time, so no row can claim another class.
create table inference_slot_fallback (
    slot     text    not null references inference_slot_setting (slot) on delete cascade,
    position integer not null check (position between 0 and 3),
    backend  text    not null check (backend ~ '^[a-z][a-z0-9-]{0,31}$'),
    model    text    not null check (model ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$'),
    effort   text    not null check (effort ~ '^[a-z]{1,32}$'),
    primary key (slot, position),
    constraint inference_slot_fallback_image_effort_ck check (slot <> 'image' or effort = 'none')
);
