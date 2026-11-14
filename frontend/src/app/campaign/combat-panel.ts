/**
 * Read-only combat panel (P12.1-B, docs/13 "P12.1-B combat panel"): renders the server's player-safe combat projection of one
 * committed revision — the phase and round, the initiative order, the acting participant's action points, the zones with their
 * passages and escape zones, each participant's side, standing, effects and known defenses, the player's own fighting values,
 * the objectives with their status, the player's legal candidates with costs, targets and disabled reasons, and the verdict that
 * waits for settlement.
 *
 * The component only displays what the server decided. It never computes a cost, a legality, a defense or an outcome, never
 * submits anything, and shows participant, zone, attack and effect identifiers literally because frozen fights carry no display
 * names. A defense of another participant is shown only as the server's last successful assessment, labelled with the round and
 * revision it was learned at. Sections are native `details` elements and the zone map is a keyboard-focusable scroll region, so
 * the panel works with the keyboard alone and reflows to a single column on a narrow screen.
 */
import { ChangeDetectionStrategy, Component, computed, inject, input, LOCALE_ID } from '@angular/core';
import { CombatEncounter, CombatParticipant, CombatView } from './campaign-protocol';
import {
  abilityMessage, actingMessage, capacityMessage, checkMessage, combatLabels, coverMessage, crossingMessage, disabledMessage,
  focusCostMessage, hasChoices, initiativeMessage, learnedMessage, maxTargetsMessage, moveMessage, objectiveMessage,
  objectiveStatusMessage, offerMessage, participantName, phaseMessage, priceMessage, reactionPolicyMessage, recipientsMessage,
  roundMessage, sceneMessage, sideMessage, socialMessage, speechMessage, standingMessage, strikeMessage, targetsMessage,
  termsMessage, verdictMessage, withdrawMessage,
} from './combat-messages';

/** Show the combat projection of one revision; renders nothing outside a fight. */
@Component({
  selector: 'app-combat-panel', changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host { display: block; }
    .combat-panel { margin-block: 1.5rem; }
    details { margin-block: .75rem; border: 1px solid var(--line, currentColor); border-radius: .4rem; padding: .25rem .75rem .5rem; }
    details h4 { font-size: 1rem; margin: .75rem 0 .25rem; }
    summary { cursor: pointer; font-weight: 700; padding-block: .4rem; }
    summary:focus-visible, .scroll:focus-visible { outline: 3px solid var(--focus, Highlight); outline-offset: 2px; }
    ul, ol { margin: .25rem 0; padding-left: 1.25rem; }
    li { overflow-wrap: anywhere; margin-block: .25rem; }
    .scroll { overflow-x: auto; max-width: 100%; }
    dl.values { display: grid; grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr)); gap: .5rem 1rem; margin: .5rem 0; }
    dl.values div { min-width: 0; }
    dt { color: var(--muted, inherit); font-size: .9rem; }
    dd { margin: 0; overflow-wrap: anywhere; }
    .acting { font-weight: 700; }
    .reason { color: var(--muted, inherit); }
    [data-disabled] { opacity: .85; }
    meter { width: 100%; max-width: 16rem; }
    @media (max-width: 30rem) { details { padding-inline: .5rem; } dl.values { grid-template-columns: 1fr 1fr; } }
  `,
  template: `
    @if (fight(); as f) {
      <section class="combat-panel" data-combat aria-labelledby="combat-title">
        <h3 id="combat-title">{{ t.title }}</h3>
        <p role="status" data-phase>{{ phase(f) }}</p>
        <p data-round>{{ round(f) }}</p>
        @if (f.acting !== null && f.actionPoints !== null) {
          <p data-acting class="acting">{{ acting(f) }}</p>
        }

        @if (f.outcome; as verdict) {
          <section aria-labelledby="combat-verdict-title" data-verdict>
            <h4 id="combat-verdict-title">{{ t.verdict }}</h4>
            <p><strong data-verdict-kind>{{ verdictText(verdict.outcome) }}</strong>@if (verdict.roundCap) { · {{ t.roundCapNote }} }</p>
            <p class="muted">{{ t.verdictHelp }}</p>
          </section>
        }

        <details open data-initiative>
          <summary>{{ t.initiative }}</summary>
          @if (f.initiative.length === 0) { <p class="muted">{{ t.noInitiative }}</p> }
          <ol>
            @for (place of f.initiative; track place.participantId) {
              <li [attr.data-participant]="place.participantId" [attr.aria-current]="place.participantId === f.acting ? 'true' : null"
                [class.acting]="place.participantId === f.acting">{{ name(place.participantId) }} · {{ initiativeText(place.initiative) }}</li>
            }
          </ol>
        </details>

        <details open data-zones>
          <summary>{{ t.zones }}</summary>
          <div class="scroll" role="region" tabindex="0" [attr.aria-label]="t.mapRegion">
            <ul>
              @for (zone of f.zones; track zone.id) {
                <li [attr.data-zone]="zone.id"><strong>{{ zone.id }}</strong> · {{ coverText(zone.cover) }}
                  @if (zone.obscured) { · {{ t.obscured }} } · {{ zone.capacity === 0 ? t.unlimited : capacityText(zone.capacity) }}
                  @if (occupants(f, zone.id); as inside) { · {{ inside }} }</li>
              }
            </ul>
            <ul data-passages>
              @for (edge of f.edges; track $index) {
                <li [attr.data-edge]="edge.from + '>' + edge.to">{{ edge.from }} → {{ edge.to }} ·
                  {{ edge.blocked ? t.blocked : edge.difficult ? t.difficult : t.passable }}@if (edge.cost !== null) { · {{ crossingText(edge.cost) }} }</li>
              }
            </ul>
          </div>
          <h4>{{ t.escape }}</h4>
          @if (f.escapeZones.length === 0) { <p class="muted">{{ t.noEscape }}</p> }
          <ul data-escape>@for (zone of f.escapeZones; track zone) { <li>{{ zone }}</li> }</ul>
        </details>

        <details open data-participants>
          <summary>{{ t.participants }}</summary>
          <ul>
            @for (who of f.participants; track who.participantId) {
              <li [attr.data-participant]="who.participantId" [attr.data-side]="who.side" [attr.data-status]="who.status">
                <strong>{{ name(who.participantId) }}</strong> · {{ sideText(who.side) }} · {{ standingText(who.status) }} · {{ who.zone }}
                <br>{{ t.effects }}: @if (who.effects.length === 0) { {{ t.noEffects }} }
                @for (effect of who.effects; track effect.effectId; let last = $last) { <span [attr.data-effect]="effect.effectId">{{ effect.effectId }} ×{{ n(effect.stacks) }}</span>@if (!last) {, } }
                @if (who.side !== 'player') {
                  <br><span data-known>{{ t.known }}:
                  @if (who.known; as known) {
                    {{ t.health }} {{ n(known.health) }} / {{ n(known.healthMax) }} · {{ t.guard }} {{ n(known.guard) }} · {{ t.resistance }} {{ n(known.resistance) }} · {{ t.armor }} {{ n(known.armor) }}.
                    <span class="muted">{{ learned(known) }}</span>
                  } @else { {{ t.notAssessed }} }</span>
                }
              </li>
            }
          </ul>
        </details>

        <details open data-yours>
          <summary>{{ t.yourValues }}</summary>
          <dl class="values">
            <div><dt>{{ t.health }}</dt><dd data-health>{{ n(f.player.health) }} / {{ n(f.player.healthMax) }}
              <meter [min]="0" [max]="f.player.healthMax" [value]="f.player.health" [attr.aria-label]="t.health"></meter></dd></div>
            <div><dt>{{ t.focus }}</dt><dd data-focus>{{ n(f.player.focus) }} / {{ n(f.player.focusMax) }}</dd></div>
            <div><dt>{{ t.guard }}</dt><dd data-guard>{{ n(f.player.guard) }}</dd></div>
            <div><dt>{{ t.resistance }}</dt><dd data-resistance>{{ n(f.player.resistance) }}</dd></div>
            <div><dt>{{ t.armor }}</dt><dd data-armor>{{ n(f.player.armor) }}</dd></div>
            <div><dt>{{ t.stance }}</dt><dd data-guard-stance>{{ f.player.guarding ? t.stanceHeld : t.stanceNone }}</dd></div>
            <div><dt>{{ t.reactionsLeft }}</dt><dd data-reactions-left>{{ n(f.player.reactionsLeft) }}</dd></div>
          </dl>
          <p class="muted">{{ t.sheetNote }}</p>
        </details>

        <details open data-objectives>
          <summary>{{ t.objectives }}</summary>
          @if (f.objectives.length === 0) { <p class="muted">{{ t.noObjectives }}</p> }
          <ul>
            @for (objective of f.objectives; track objective.objectiveId) {
              <li [attr.data-objective]="objective.objectiveId" [attr.data-status]="objective.status">{{ objectiveText(objective) }} ·
                <strong>{{ objectiveStatusText(objective.status) }}</strong></li>
            }
          </ul>
        </details>

        <details open data-options>
          <summary>{{ t.options }}</summary>
          @if (f.playerDecision && f.options; as o) {
            @if (o.offer; as offer) {
              <section data-offer aria-labelledby="combat-offer-title">
                <h4 id="combat-offer-title">{{ t.offer }}</h4>
                <p>{{ offerText(offer.terms.termsId, offer.recipientId) }} {{ offer.canAccept ? t.canAccept : t.cannotAccept }}</p>
              </section>
            }
            @if (o.moves.length > 0) {
              <h4>{{ t.movement }}</h4>
              <ul data-moves>@for (move of o.moves; track move.zone) { <li>{{ moveText(move.zone) }} · {{ priceText(move.cost) }}</li> }</ul>
            }
            @if (o.strikes.length > 0) {
              <h4>{{ t.attacks }}</h4>
              <ul data-strikes>
                @for (strike of o.strikes; track strike.attackId) {
                  <li [attr.data-attack]="strike.attackId" [attr.data-disabled]="strike.disabled">{{ strikeText(strike.attackId) }} · {{ priceText(strike.cost) }}
                    @if (strike.targets.length > 0) { · {{ targetsText(strike.targets) }} }
                    @if (strike.disabled; as why) { · <span class="reason">{{ reasonText(why) }}</span> }</li>
                }
              </ul>
            }
            @if (o.abilities.length > 0) {
              <h4>{{ t.abilities }}</h4>
              <ul data-abilities>
                @for (ability of o.abilities; track ability.abilityId) {
                  <li [attr.data-ability]="ability.abilityId" [attr.data-disabled]="ability.disabled">{{ abilityText(ability.abilityId) }} · {{ priceText(ability.cost) }}
                    @if (ability.focus > 0) { · {{ focusText(ability.focus) }} }
                    @if (ability.targets.length > 0) { · {{ targetsText(ability.targets) }} · {{ maxTargetsText(ability.maxTargets) }} }
                    @if (ability.disabled; as why) { · <span class="reason">{{ reasonText(why) }}</span> }</li>
                }
              </ul>
            }
            @if (o.actions.length > 0) {
              <h4>{{ t.declared }}</h4>
              <ul data-actions>
                @for (action of o.actions; track action.actionId) {
                  <li [attr.data-action]="action.actionId">{{ action.kind === 'social' ? socialText(action.actionId) : sceneText(action.actionId) }} · {{ priceText(action.cost) }}
                    @if (action.targets.length > 0) { · {{ targetsText(action.targets) }} }
                    @if (action.check; as check) { · {{ checkText(check) }} }
                    @if (action.terms; as terms) { · {{ termsText(terms) }} }</li>
                }
              </ul>
            }
            <h4>{{ t.other }}</h4>
            <ul data-other>
              @if (o.guard; as guard) {
                <li data-guard-option [attr.data-disabled]="guard.available ? null : 'UNAVAILABLE'">{{ t.guardOption }} · {{ priceText(guard.cost) }}@if (!guard.available) { · <span class="reason">{{ t.unavailable }}</span> }</li>
              }
              @if (o.useItem; as item) {
                <li data-item-option [attr.data-disabled]="item.available ? null : 'UNAVAILABLE'">{{ t.itemOption }} · {{ priceText(item.cost) }}@if (!item.available) { · <span class="reason">{{ t.unavailable }}</span> }</li>
              }
              @if (o.ready; as ready) {
                <li data-ready-option [attr.data-disabled]="ready.available ? null : 'UNAVAILABLE'">{{ t.readyOption }} · {{ priceText(ready.cost) }}@if (!ready.available) { · <span class="reason">{{ t.unavailable }}</span> }</li>
              }
              @if (o.assess; as assess) {
                <li data-assess-option>{{ t.assessOption }} · {{ priceText(assess.cost) }}@if (assess.targets.length > 0) { · {{ targetsText(assess.targets) }} } @else { · <span class="reason">{{ t.unavailable }}</span> }</li>
              }
              @for (withdrawal of o.withdraw; track withdrawal.zone) {
                <li [attr.data-withdraw]="withdrawal.zone">{{ withdrawText(withdrawal.zone) }} · {{ priceText(withdrawal.cost) }}</li>
              }
              <li data-speech-option>{{ speechText(o.speechRemaining) }}@if (o.speechRecipients.length > 0) { · {{ recipientsText(o.speechRecipients) }} }</li>
              @if (o.endActivation) { <li data-end-option>{{ t.endOption }}</li> }
            </ul>
            @if (o.reactions.length > 0) {
              <h4>{{ t.reactions }}</h4>
              <ul data-reactions>
                @for (reaction of o.reactions; track reaction.reactionId) {
                  <li [attr.data-reaction]="reaction.reactionId">{{ reaction.reactionId }} · {{ policyText(reaction) }}</li>
                }
              </ul>
            }
            @if (!choices(o)) { <p class="muted">{{ t.noDecision }}</p> }
          } @else { <p class="muted" data-no-decision>{{ t.noDecision }}</p> }
        </details>
        <p class="muted">{{ t.readOnly }}</p>
      </section>
    }
  `,
})
export class CombatPanel {
  /** The projection of the shown revision; null or a view without a fight renders nothing. */
  readonly view = input.required<CombatView | null>();
  private readonly locale = inject(LOCALE_ID);
  private readonly numbers = new Intl.NumberFormat(this.locale, { maximumFractionDigits: 0 });
  readonly t = combatLabels();
  readonly fight = computed(() => this.view()?.encounter ?? null);

  name = participantName;
  sideText = sideMessage;
  standingText = standingMessage;
  coverText = coverMessage;
  verdictText = verdictMessage;
  objectiveStatusText = objectiveStatusMessage;
  reasonText = disabledMessage;
  choices = hasChoices;
  moveText = moveMessage;
  withdrawText = withdrawMessage;
  strikeText = strikeMessage;
  abilityText = abilityMessage;
  socialText = socialMessage;
  sceneText = sceneMessage;

  /** Format an exact server number with the compiled locale's digits. */
  n(value: number): string { return this.numbers.format(value); }

  phase(fight: CombatEncounter): string { return phaseMessage(fight.phase, fight.playerDecision); }
  round(fight: CombatEncounter): string { return roundMessage(this.n(fight.round), this.n(fight.roundCap), fight.round > 0); }
  acting(fight: CombatEncounter): string {
    return actingMessage(participantName(fight.acting ?? '?'), this.n(fight.actionPoints ?? 0), this.n(fight.actionPointsPerActivation));
  }
  initiativeText(value: number): string { return initiativeMessage(this.n(value)); }
  capacityText(limit: number): string { return capacityMessage(this.n(limit)); }
  crossingText(cost: number): string { return crossingMessage(this.n(cost)); }
  priceText(cost: number): string { return priceMessage(this.n(cost)); }
  focusText(focus: number): string { return focusCostMessage(this.n(focus)); }
  maxTargetsText(most: number): string { return maxTargetsMessage(this.n(most)); }
  speechText(left: number): string { return speechMessage(this.n(left)); }
  targetsText(targets: readonly string[]): string { return targetsMessage(targets.map(participantName).join(', ')); }
  recipientsText(recipients: readonly string[]): string { return recipientsMessage(recipients.map(participantName).join(', ')); }
  learned(known: NonNullable<CombatParticipant['known']>): string { return learnedMessage(this.n(known.round), this.n(known.stateRevision)); }
  objectiveText(objective: CombatEncounter['objectives'][number]): string { return objectiveMessage(objective, this.n(objective.rounds ?? 0)); }
  checkText(check: { attribute: string; skill: string | null; rank: number }): string { return checkMessage(check.attribute, check.skill, this.n(check.rank)); }
  termsText(terms: { termsId: string; kind: string; cost: number }): string { return termsMessage(terms.termsId, terms.kind, this.n(terms.cost)); }
  offerText(terms: string, recipient: string): string { return offerMessage(terms, participantName(recipient)); }
  policyText(reaction: Parameters<typeof reactionPolicyMessage>[0]): string { return reactionPolicyMessage(reaction, this.n(reaction.value)); }

  /** The participants standing in one zone, by name, or null when nobody is. */
  occupants(fight: CombatEncounter, zone: string): string | null {
    const inside = fight.participants.filter((who) => who.zone === zone && who.status === 'ACTIVE').map((who) => participantName(who.participantId));
    return inside.length === 0 ? null : inside.join(', ');
  }
}
