-- P21.3-B: an AI session can now end because the owner changed the assignment of a slot it ran
-- under, and the next session of that line records the same cause. The value is diagnostics only,
-- like the rest of ai_session_record (V17): no row changes, and old rows keep their values.
alter table ai_session_record drop constraint ai_session_record_opened_cause_check;
alter table ai_session_record add constraint ai_session_record_opened_cause_check
    check (opened_cause in ('FIRST', 'EXPLICIT', 'SUPERSEDED', 'CAMPAIGN', 'INSTALLATION', 'CAPACITY', 'TURN_CEILING',
                            'CONTEXT_PRESSURE', 'UNRELIABLE', 'OWNER', 'RESTART', 'SLOT_CHANGED'));
alter table ai_session_record drop constraint ai_session_record_retired_reason_check;
alter table ai_session_record add constraint ai_session_record_retired_reason_check
    check (retired_reason in ('EXPLICIT', 'SUPERSEDED', 'CAMPAIGN', 'INSTALLATION', 'CAPACITY', 'TURN_CEILING',
                              'CONTEXT_PRESSURE', 'UNRELIABLE', 'OWNER', 'RESTART', 'SLOT_CHANGED'));
