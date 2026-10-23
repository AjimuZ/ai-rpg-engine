# ADR 0012 — Independent adventure workspaces

- Status: Accepted design requirement; implementation pending
- Date: 23 October 2026

The owner distinguishes personalized parallel adventures from save slots. Introduce an adventure workspace above campaigns/playthroughs, with separate specifications, admitted content and managed media directories. Keep PostgreSQL canonical; do not introduce competing file saves.

Every campaign, private memory/thread, image job and generated asset belongs to one adventure/campaign scope. Equal display names do not imply equal identities. Share only public engine assets or explicitly exported/imported content, and apply owner-wide generation limits. Library/switching and backup/restore preserve these identities.

Extend P03 foundations, P05 library, P06 switching, P09 memory, P16 restore and P17 media. Directory/API contracts and QA-33 are in [adventure workspaces](../25-adventure-workspaces.md). Existing transaction, audience and pack-version rules remain. No runtime implementation is claimed.
