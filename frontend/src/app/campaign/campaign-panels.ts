/**
 * Read-only panels and journal of one playthrough (P10.5, docs/13, "P10.5 panels and journal"): the world clock, the
 * character sheet with conditions, resources and abilities, the inventory with worn equipment, the
 * discovered quests with their objectives and outcome, what the player knows and has promised, and the
 * history of public events. Items carry a stable icon (P17.1-B): the campaign's artwork for the definition when it has
 * one, a neutral built-in icon otherwise, in a frame that follows the declared rarity tier and sits beside the written
 * tier name; the credits of the artwork in force are listed in their own section.
 *
 * The component only displays what the server decided at one committed revision. It never computes a
 * bonus, a restoration or a quest result, never mutates anything, and shows pack names only when the
 * server marked them public (otherwise the literal identifier). Text in another language than the
 * interface carries a `lang` attribute and the shared fallback notice.
 */
import { ChangeDetectionStrategy, Component, computed, inject, input, LOCALE_ID, output } from '@angular/core';
import { UiIcon } from '../preview/ui-kit';
import { ArtworkIndex, frameFor } from './campaign-artwork';
import { ItemArt } from './item-art';
import { JournalEntry, PanelClock, PanelQuest, PanelText, PlayPanels } from './campaign-protocol';
import {
  attributeMessage, clockMessage, conditionMessage, displayName, fallbackNotice, objectiveStateMessage, outcomeKindMessage,
  promiseDueMessage, promiseMessage, questStatusMessage, stanceMessage,
} from './campaign-messages';

/** Show the panels of one revision; the parent re-reads them whenever the world revision advances. */
@Component({
  selector: 'app-campaign-panels', imports: [ItemArt, UiIcon], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (panels(); as current) {
      <section class="play-panels" data-panels aria-labelledby="panels-title">
        <h3 id="panels-title" i18n="@@campaign.panels.title">Character, belongings and journal</h3>
        <p data-clock><strong i18n="@@campaign.panels.clockLabel">In-world time</strong>: {{ clock(current) }}</p>
        @if (!current.contentAvailable) {
          <p class="notice" role="note" data-content-missing i18n="@@campaign.panels.contentMissing">Adventure names and quests cannot be shown because the adventure content is unavailable. The numbers below are exact.</p>
        }

        @if (current.character; as hero) {
          <section aria-labelledby="sheet-title" data-sheet>
            <h4 id="sheet-title">{{ hero.name }}</h4>
            @if (hero.role) { <p class="muted">{{ hero.role }}</p> }
            @if (hero.location; as place) {
              <p data-place><strong i18n="@@campaign.panels.placeLabel">Location</strong>: <span [attr.lang]="place.name?.locale ?? null">{{ name(place.name, place.id) }}</span></p>
            }
            @if (hero.attributes.length > 0) {
              <h5 i18n="@@campaign.panels.attributes">Attributes</h5>
              <dl class="stats" data-attributes>
                @for (attribute of hero.attributes; track attribute.key) {
                  <div [attr.data-attribute]="attribute.key"><dt [attr.lang]="attribute.name?.locale ?? null">{{ name(attribute.name, attribute.key) }}</dt><dd>{{ attributeText(attribute) }}</dd></div>
                }
              </dl>
            }
            @if (hero.skills.length > 0) {
              <h5 i18n="@@campaign.panels.skills">Skills</h5>
              <dl class="stats" data-skills>
                @for (skill of hero.skills; track skill.key) {
                  <div><dt [attr.lang]="skill.name?.locale ?? null">{{ name(skill.name, skill.key) }}</dt><dd>{{ skill.rank }}</dd></div>
                }
              </dl>
            }
            @if (hero.derived.length > 0) {
              <h5 i18n="@@campaign.panels.derived">Derived statistics</h5>
              <dl class="stats" data-derived>
                @for (stat of hero.derived; track stat.key) { <div><dt>{{ stat.key }}</dt><dd>{{ stat.value }}</dd></div> }
              </dl>
            }
          </section>
        }

        <section aria-labelledby="resources-title" data-resources-panel>
          <h4 id="resources-title" i18n="@@campaign.panels.resources">Resources</h4>
          @if (current.resources.length === 0) { <p class="muted" i18n="@@campaign.panels.noResources">No resources to show.</p> }
          <ul>
            @for (resource of current.resources; track resource.key) {
              <li [attr.data-resource]="resource.key"><span [attr.lang]="resource.name?.locale ?? null">{{ name(resource.name, resource.key) }}</span>:
                {{ resource.current }} / {{ resource.maximum }}
                <meter [min]="resource.minimum" [max]="resource.maximum" [value]="resource.current" [attr.aria-label]="name(resource.name, resource.key)"></meter></li>
            }
          </ul>
        </section>

        <section aria-labelledby="conditions-title" data-conditions>
          <h4 id="conditions-title" i18n="@@campaign.panels.conditions">Conditions</h4>
          @if (current.conditions.length === 0) { <p class="muted" i18n="@@campaign.panels.noConditions">No active conditions.</p> }
          <ul>@for (condition of current.conditions; track condition.id) { <li [attr.data-condition]="condition.id">{{ conditionText(condition) }}</li> }</ul>
        </section>

        <section aria-labelledby="abilities-title" data-abilities>
          <h4 id="abilities-title" i18n="@@campaign.panels.abilities">Abilities</h4>
          @if (current.abilities.length === 0) { <p class="muted" i18n="@@campaign.panels.noAbilities">No abilities learned yet.</p> }
          <ul>@for (ability of current.abilities; track ability.id) { <li [attr.data-ability]="ability.id"><span [attr.lang]="ability.name?.locale ?? null">{{ name(ability.name, ability.id) }}</span> · {{ ability.rank }}</li> }</ul>
        </section>

        <section aria-labelledby="inventory-title" data-inventory>
          <h4 id="inventory-title" i18n="@@campaign.panels.inventory">Inventory</h4>
          @if (current.inventory.length === 0) { <p class="muted" i18n="@@campaign.panels.noItems">You carry nothing.</p> }
          <ul>
            @for (item of current.inventory; track item.itemId) {
              <li [attr.data-item]="item.definitionId" class="with-art">
                <app-item-art [src]="art(item.definitionId)" [frame]="frame(item.rarity)" [fallback]="icon(item.wornIn)" />
                <span [attr.lang]="item.name?.locale ?? null">{{ name(item.name, item.definitionId) }}</span> × {{ item.quantity }}
                @if (item.rarity) { <span class="muted" data-rarity> · <app-ui-icon name="rarity" /> <span [attr.lang]="item.rarityName?.locale ?? null">{{ name(item.rarityName, item.rarity) }}</span></span> }
                @if (item.wornIn) { <strong> · <ng-container i18n="@@campaign.panels.worn">Worn</ng-container> ({{ item.wornIn }})</strong> }
              </li>
            }
          </ul>
        </section>

        <section aria-labelledby="equipment-title" data-equipment>
          <h4 id="equipment-title" i18n="@@campaign.panels.equipment">Worn equipment</h4>
          @if (current.equipment.length === 0) { <p class="muted" i18n="@@campaign.panels.noEquipment">Nothing is worn.</p> }
          <ul>
            @for (worn of current.equipment; track worn.itemId) {
              <li [attr.data-slot]="worn.slot" class="with-art">
                <app-item-art [src]="art(worn.definitionId)" [frame]="frame(rarityOf(worn.itemId))" [fallback]="icon(worn.slot)" />
                <strong>{{ worn.slot }}</strong>: <span [attr.lang]="worn.name?.locale ?? null">{{ name(worn.name, worn.definitionId) }}</span>
                @if (rarityOf(worn.itemId); as tier) { <span class="muted" data-rarity> · <app-ui-icon name="rarity" /> <span [attr.lang]="tierName(worn.itemId)?.locale ?? null">{{ name(tierName(worn.itemId), tier) }}</span></span> }
              </li>
            }
          </ul>
        </section>

        <section aria-labelledby="quests-title" data-quests>
          <h4 id="quests-title" i18n="@@campaign.panels.quests">Quests</h4>
          @if (current.quests.length === 0) { <p class="muted" i18n="@@campaign.panels.noQuests">No quests discovered yet.</p> }
          <ul class="quest-list">
            @for (quest of current.quests; track quest.id) {
              <li [attr.data-quest]="quest.id" [attr.data-status]="quest.status">
                <p><strong [attr.lang]="quest.name?.locale ?? null">{{ name(quest.name, quest.id) }}</strong> · {{ statusText(quest.status) }}</p>
                @if (quest.summary; as summary) { <p [attr.lang]="summary.locale">{{ summary.text }}</p> }
                @if (quest.objectives.length > 0) {
                  <ul class="objectives">
                    @for (objective of quest.objectives; track objective.id) {
                      <li [attr.data-objective]="objective.id" [attr.data-satisfied]="objective.satisfied">
                        <span class="objective-state">{{ objectiveText(objective.satisfied, objective.optional) }}</span> ·
                        <span [attr.lang]="objective.description?.locale ?? null">{{ name(objective.description, objective.id) }}</span>
                      </li>
                    }
                  </ul>
                }
                @if (quest.outcome; as outcome) {
                  <p data-outcome>{{ outcomeText(outcome.kind) }}@if (outcome.description; as description) { · <span [attr.lang]="description.locale">{{ description.text }}</span> }</p>
                  @if (outcome.rewards.length > 0) {
                    <ul class="rewards" [attr.aria-label]="rewardsLabel">
                      @for (reward of outcome.rewards; track reward.itemId) { <li><span [attr.lang]="reward.name?.locale ?? null">{{ name(reward.name, reward.itemId) }}</span> × {{ reward.quantity }}</li> }
                    </ul>
                  }
                }
                @if (hasFallback(quest)) { <p class="notice" role="note" data-fallback>{{ fallback(quest) }}</p> }
              </li>
            }
          </ul>
        </section>

        <section aria-labelledby="knowledge-title" data-knowledge>
          <h4 id="knowledge-title" i18n="@@campaign.panels.knowledge">What you know</h4>
          @if (current.discoveries.length === 0) { <p class="muted" i18n="@@campaign.panels.noKnowledge">No recorded discoveries.</p> }
          <ul>
            @for (discovery of current.discoveries; track discovery.claimId) {
              <li [attr.data-stance]="discovery.stance"><span class="muted">{{ stance(discovery.stance) }}</span> · <span [attr.lang]="discovery.statementLocale">{{ discovery.statement }}</span></li>
            }
          </ul>
          @if (current.discoveriesTotal > current.discoveries.length) {
            <p class="muted" i18n="@@campaign.panels.moreKnowledge">Only the newest discoveries are listed.</p>
          }
        </section>

        <section aria-labelledby="promises-title" data-promises>
          <h4 id="promises-title" i18n="@@campaign.panels.promises">Promises and agreements</h4>
          @if (current.promises.length === 0) { <p class="muted" i18n="@@campaign.panels.noPromises">No open promises.</p> }
          <ul>
            @for (promise of current.promises; track promise.commitmentId) {
              <li [attr.data-direction]="promise.direction">
                <p><strong>{{ promiseText(promise) }}</strong></p>
                <p [attr.lang]="promise.termsLocale">{{ promise.terms }}</p>
                @if (due(promise); as when) { <p class="muted">{{ when }}</p> }
              </li>
            }
          </ul>
        </section>

        <section aria-labelledby="credits-title" data-credits>
          <h4 id="credits-title" i18n="@@campaign.panels.credits">Artwork credits</h4>
          @if (credits().length === 0) { <p class="muted" i18n="@@campaign.panels.noCredits">No artwork is credited here. Items without artwork show a built-in icon.</p> }
          <ul>@for (line of credits(); track line) { <li>{{ line }}</li> }</ul>
        </section>

        <section aria-labelledby="journal-title" data-journal>
          <h4 id="journal-title" i18n="@@campaign.panels.history">Event history</h4>
          @if (entries().length === 0) { <p class="muted" i18n="@@campaign.panels.noEvents">Nothing has happened yet.</p> }
          <ol class="journal-list" [attr.lang]="current.locale">
            @for (entry of entries(); track entry.sequence) {
              <li [attr.data-event]="entry.sequence"><span class="muted">{{ clock(entry) }}</span> · {{ entry.statement }}</li>
            }
          </ol>
          @if (more()) {
            <button type="button" data-older (click)="olderRequested.emit()" [disabled]="loadingOlder()" i18n="@@campaign.panels.older">Show older events</button>
          }
        </section>
      </section>
    }
  `,
})
export class CampaignPanels {
  /** The panels of the shown revision; null renders nothing. */
  readonly panels = input.required<PlayPanels | null>();
  /** Older journal entries already fetched, newest first, to follow the panels' own entries. */
  readonly older = input<readonly JournalEntry[]>([]);
  /** Cursor of the next older page; null once the oldest event is listed. */
  readonly nextBefore = input<number | null>(null);
  readonly loadingOlder = input(false);
  /** Artwork in force for the campaign; null (not read, or the read failed) shows every built-in icon and no credits. */
  readonly artwork = input<ArtworkIndex | null>(null);
  /** Emitted when the player asks for the next older page. */
  readonly olderRequested = output<void>();
  private readonly locale = inject(LOCALE_ID);
  readonly rewardsLabel = $localize`:@@campaign.panels.rewards:Reward`;
  readonly entries = computed(() => [...(this.panels()?.journal ?? []), ...this.older()]);
  readonly more = computed(() => this.nextBefore() !== null);
  readonly credits = computed(() => this.artwork()?.credits() ?? []);
  /** Carried items by instance, so a worn item shows the same admitted rarity as in the inventory. */
  private readonly carried = computed(() => new Map((this.panels()?.inventory ?? []).map((item) => [item.itemId, item])));

  name = displayName;
  statusText = questStatusMessage;
  outcomeText = outcomeKindMessage;
  objectiveText = objectiveStateMessage;
  stance = stanceMessage;
  promiseText = promiseMessage;

  /** Hash URL of the item definition's stable artwork, or null so the built-in icon is shown. */
  art(definitionId: string): string | null { return this.artwork()?.imageUrl('item', definitionId) ?? null; }

  /** Frame of a declared rarity tier; the tier's name is always written beside it. */
  frame = frameFor;

  /** Declared rarity tier of a carried item instance, or null when it has none. */
  rarityOf(itemId: string): string | null { return this.carried().get(itemId)?.rarity ?? null; }

  /** Pack name of a carried item's rarity tier, or null. */
  tierName(itemId: string): PanelText | null { return this.carried().get(itemId)?.rarityName ?? null; }

  /** Neutral built-in icon of an item without artwork: a weapon glyph for a hand slot, the bag glyph otherwise. */
  icon(slot: string | null): string { return slot !== null && /hand|weapon/i.test(slot) ? 'weapon' : 'inventory'; }

  /** Localized clock reading of the panels or of one journal entry. */
  clock(source: { readonly clock: PanelClock }): string { return clockMessage(source.clock, this.locale); }

  /** Localized attribute value with its base when a condition changed it. */
  attributeText(attribute: Parameters<typeof attributeMessage>[0]): string { return attributeMessage(attribute, this.locale); }

  /** Localized condition line. */
  conditionText(condition: Parameters<typeof conditionMessage>[0]): string { return conditionMessage(condition, this.locale); }

  /** Localized due line of a promise, or null without a deadline. */
  due(promise: Parameters<typeof promiseDueMessage>[0]): string | null { return promiseDueMessage(promise, this.locale); }

  /** Whether any text of a quest is shown in another language than the panels' narrative language. */
  hasFallback(quest: PanelQuest): boolean { return this.fallbackLocale(quest) !== null; }

  /** Fallback disclosure for a quest whose text came from the source language. */
  fallback(quest: PanelQuest): string {
    const locale = this.fallbackLocale(quest);
    return locale === null ? '' : fallbackNotice(locale);
  }

  /** The source language of the first fallback text of a quest, or null when none fell back. */
  private fallbackLocale(quest: PanelQuest): 'en' | 'it' | null {
    const texts: (PanelText | null)[] = [quest.name, quest.summary, quest.outcome?.description ?? null, ...quest.objectives.map((o) => o.description)];
    return texts.find((text) => text?.fallback)?.locale ?? null;
  }
}
