# ADR 0012 — Independent adventure workspaces

- Status: Accepted; library reads and optional authored-content folders implemented, full isolation pending
- Date: 23 October 2026

The owner distinguishes personalized parallel adventures from save slots. Introduce an adventure workspace above campaigns/playthroughs, with separate specifications, admitted content and managed media directories. Keep PostgreSQL canonical; do not introduce competing file saves.

Every campaign, private memory/thread, image job and generated asset belongs to one adventure/campaign scope. Equal display names do not imply equal identities. Share only public engine assets or explicitly exported/imported content, and apply owner-wide generation limits. Library/switching and backup/restore preserve these identities.

Extend P03 foundations, P05 library, P06 switching, P09 memory, P16 restore and P17 media. Directory/API contracts and QA-33 are in [adventure workspaces](../25-adventure-workspaces.md). Existing transaction, audience and pack-version rules remain.

P06.5 continuation (25 October 2026): retain ADR 0018's canonical database bytes
and add an opt-in, rebuildable private folder projection. Nest specification and
authored-media directories inside each revision so a single atomic rename publishes
both with their integrity manifest. This avoids a distributed commit or byte-store
migration while separating equal filenames by adventure/revision identity. The cost
is duplicated disk space and startup verification I/O; missing projections rebuild,
but altered published trees fail closed for offline operator repair. Files are never
served publicly or read back as content authority. This does not establish browser,
generated-media, memory or full QA-33 acceptance.
