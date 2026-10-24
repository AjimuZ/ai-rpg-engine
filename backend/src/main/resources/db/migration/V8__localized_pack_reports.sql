-- P04.6: report version 2 adds privileged localization coverage to new import reports.
-- Input is the V7 schema, including sealed version-1 rows; output accepts both versions.
-- No stored bytes, reports, exports or revision identities are rewritten. Ordinary Flyway
-- transactions roll back this DDL on failure; unsupported report versions remain errors.
alter table content_revision_report
    drop constraint content_revision_report_report_version_check;
alter table content_revision_report
    add constraint content_revision_report_report_version_check
        check (report_version in (1, 2));
