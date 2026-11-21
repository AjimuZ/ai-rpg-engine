# ADR 0048: The bundled sample offers one unchecked, free truce

- Status: Accepted (conservative choice within the owner decision of 20 November 2026 recorded in [ADR 0046](0046-automatic-first-start-provisioning-of-the-owner-and-a-sample-adventure.md)); implemented in P14.1-D (21 November 2026).
- Date: 21 November 2026
- Changes: the content of the bundled `sample:lantern-ford` pack. No rule, schema, migration or route changed.

## Context

The only packaged encounter, `ford-ambush`, declared no `actions`, so a packaged installation could end a fight by force or by escape but never by agreement. The negotiation rules already exist (docs/11 sections 7.1 and 13, QA-11, QA-13): a declared `negotiate` action addressed to hostiles proposes terms, a response only puts terms on the table, and only an explicit `ANSWER_OFFER` naming the offer makes the agreement. The pack format leaves open how hard the sample's negotiation should be.

## Decision

1. `ford-ambush` declares one action, `offer-passage` (social, intent `negotiate`, hostile recipients, the default interact cost of one action point), proposing the terms `shared-crossing` (kind `truce`, cost 0).
2. The action declares **no check and no counteroffer**. By the combat rules the raider's response is then certain (the proposed terms are on the table), so the same fight ends the same way for every player and for every automated run, and no dice are drawn for it.
3. Accepting costs nothing beyond the attempt, so the agreement is never blocked by an empty action pool.
4. The terms and action texts are neutral English, like the rest of the sample. Pack text has no locale overlay, so an Italian playthrough shows them with the reviewed fallback notice, as it does for every other sample text.
5. Only the sealed revision a first start creates holds the new action. An installation that already holds a sealed sample revision keeps it (ADR 0046, decision 5), and delivering a new revision to an existing workspace is not part of this decision.

## Alternatives considered

- A checked negotiation (attribute check against a difficulty) with a counteroffer: richer, but the response then depends on dice, and a journey or test could not rely on reaching an agreement. A later genre fixture can show a checked parley.
- Also declaring surrender terms (`recipientSurrenders`, `playerSurrenders`): they end a fight as a victory or defeat by another name and add nothing to the agreement path the task needs.
- A priced truce: rejected for the first sample, because a price makes the ending depend on action points left.

## Consequences

- A fresh packaged installation can end the ford fight by agreement, then settle it and return to exploration at the camp.
- The pack's `actions` array adds to the stored encounter definition; the content hash of the sample revision changes, which affects only fresh installations.
- Not covered: a checked or priced negotiation in the sample, an Italian-authored text for the terms, and the browser's combat panel offering the action (the page lists the declared actions it receives; this was not run live in this task).

## Verification

`FirstStartProvisioningTest#theProvisionedEncounterEndsInANegotiatedSettlement` drives the provisioned encounter from the engagement to the settlement with a retry of each lost response. See the [roadmap entry](../16-development-roadmap.md#18-p14--playable-mvp-validation) for the recorded result and what was not proven.
