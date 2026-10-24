/** Original scalable UI primitives: fixed geometry only; labels and values always remain live text. */
import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';

/** Original 24-unit line drawings; no URLs, markup or pack-provided path data are accepted. */
export const ICONS = {
  story: 'M4 4h6l2 2 2-2h6v16h-6l-2 2-2-2H4z M12 6v16',
  sheet: 'M8 8a4 4 0 1 0 8 0a4 4 0 1 0-8 0 M4 21v-3l4-3h8l4 3v3',
  inventory: 'M4 8h16v13H4z M8 8V4h8v4 M4 12h16 M10 12v3h4v-3',
  journal: 'M6 3h14v18H6z M3 7h6 M3 12h6 M3 17h6 M12 8h5 M12 12h5',
  guide: 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18 M9 9a3 3 0 0 1 6 0c0 2-3 2-3 5 M12 17h.01',
  creative: 'M12 2l2 7 7 3-7 2-2 8-2-8-7-2 7-3z',
  create: 'M12 4v16 M4 12h16',
  settings: 'M4 6h16 M4 12h16 M4 18h16 M8 3v6 M16 9v6 M10 15v6',
  send: 'M3 3l19 9-19 9 4-9z M7 12h15',
  continue: 'M9 4l8 8-8 8',
  close: 'M5 5l14 14 M19 5L5 19',
  saved: 'M4 3h13l3 3v15H4z M8 3v6h8V3 M8 21v-7h8v7',
  loading: 'M12 3a9 9 0 1 1-9 9 M3 3v6h6',
  error: 'M12 3L2 21h20z M12 9v5 M12 17h.01',
  health: 'M12 21L3 12a5 5 0 0 1 9-7a5 5 0 0 1 9 7z',
  focus: 'M12 2l9 10-9 10L3 12z M8 12h8',
  xp: 'M12 2l3 7 7 1-5 5 1 7-6-4-6 4 1-7-5-5 7-1z',
  weapon: 'M5 20L19 6V2h-4L2 15 M3 12l9 9 M2 22l4-4',
  supply: 'M8 3h8 M10 3v5L5 18v3h14v-3L14 8V3 M8 15h8',
  rarity: 'M12 2l10 10-10 10L2 12z M8 12l4-4 4 4-4 4z',
} as const;
export type IconName = keyof typeof ICONS;

/** Render an allowlisted decorative icon beside a required visible control/status label; unknown names use a diamond. */
@Component({
  selector: 'app-ui-icon', changeDetection: ChangeDetectionStrategy.OnPush,
  host: { 'aria-hidden': 'true' },
  template: `<svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" focusable="false" aria-hidden="true"><path [attr.d]="path()" /></svg>`,
})
export class UiIcon {
  readonly name = input.required<string>();
  readonly path = computed(/** Reject inherited keys as well as unknown strings; callers cannot inject SVG. */ () =>
    Object.hasOwn(ICONS, this.name()) ? ICONS[this.name() as IconName] : ICONS.rarity);
}

/** Render bounded finite resource values; missing/invalid data yields labeled unavailability rather than a false zero. */
@Component({
  selector: 'app-ui-meter', imports: [DecimalPipe], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="resource-meter"><span>{{ label() }}</span>
    @if (range(); as current) {
      <span>{{ current.value | number }} / {{ current.max | number }}</span>
      <div class="meter-track" role="meter" [attr.aria-label]="label()" aria-valuemin="0"
        [attr.aria-valuenow]="current.value" [attr.aria-valuemax]="current.max">
        <span class="meter-fill" [style.width.%]="current.percent"></span>
      </div>
    } @else { <span i18n="@@kit.disconnected">Not connected</span><div class="meter-track unavailable" aria-hidden="true"></div> }
  </div>`,
})
export class UiMeter {
  readonly label = input.required<string>();
  readonly value = input<number | null>(null);
  readonly max = input<number | null>(null);
  readonly range = computed(/** Clamp only valid numeric input; malformed projections must never produce NaN styles or ARIA. */ () => {
    const value = this.value();
    const max = this.max();
    if (value === null || max === null || !Number.isFinite(value) || !Number.isFinite(max) || max <= 0) return null;
    const bounded = Math.min(max, Math.max(0, value));
    return { value: bounded, max, percent: bounded / max * 100 };
  });
}

/** Render one fixed original item illustration or a textual empty slot; arbitrary image URLs are intentionally unsupported. */
@Component({
  selector: 'app-ui-item', imports: [UiIcon], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<figure class="item-frame" [class.item-empty]="!sample()">
    @if (sample()) {
      <svg viewBox="0 0 96 96" width="96" height="96" aria-hidden="true" focusable="false">
        <path d="M47 8h10v7l7 6v30L54 83H40L30 51V21l7-6V8z" fill="#202032" stroke="#e6c783" stroke-width="3" />
        <path d="M38 26h18v22L47 66l-9-18z" fill="#94d6de" stroke="#f0edf5" stroke-width="2" />
        <path d="M40 76h14 M38 15h18" fill="none" stroke="#e6c783" stroke-width="3" />
      </svg>
    } @else { <app-ui-icon name="inventory" /> }
    <figcaption>{{ label() }}<span class="rarity-label"><app-ui-icon name="rarity" />{{ rarity() }}</span></figcaption>
  </figure>`,
})
export class UiItem {
  readonly label = input($localize`:@@kit.emptySlot:Empty slot`);
  readonly rarity = input($localize`:@@kit.noItem:No item`);
  readonly sample = input(false);
}

/** Local review surface exercises the production theme without fabricating campaign balances or item ownership. */
@Component({
  selector: 'app-graphic-kit', imports: [UiIcon, UiMeter, UiItem], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<section class="panel graphic-kit"><h1 i18n="@@kit.title">Interface graphic kit</h1>
    <p i18n="@@kit.intro">Theme samples only. Values and items below are examples, independent of any playthrough.</p>
    <h2 i18n="@@kit.controls">Controls</h2><div class="kit-row">
      <button type="button" (click)="selected.set(!selected())" [attr.aria-pressed]="selected()"><app-ui-icon name="creative" />{{ selected() ? selectedLabel : selectLabel }}</button>
      <button type="button" disabled i18n="@@kit.unavailable">Unavailable</button><button type="button" disabled aria-busy="true" i18n="@@kit.loading"><app-ui-icon name="loading" />Loading</button>
    </div><div class="quick-replies"><button type="button" i18n="@@kit.reply">Reply sample</button></div>
    <label for="kit-field" i18n="@@kit.field">Sample field</label><input id="kit-field" placeholder="Write here" i18n-placeholder="@@kit.placeholder" />
    <label for="kit-error" i18n="@@kit.invalid">Invalid field</label><input id="kit-error" aria-invalid="true" aria-describedby="kit-error-help" value="Invalid sample" i18n-value="@@kit.invalidValue" />
    <p id="kit-error-help" class="error-text" i18n="@@kit.error">Enter a supported value.</p>
    <label for="kit-disabled" i18n="@@kit.disabled">Unavailable field</label><input id="kit-disabled" disabled value="Not connected" i18n-value="@@kit.disabledValue" />
    <label class="toggle" i18n="@@kit.preference"><input type="checkbox" />Sample preference</label>
    <h2 i18n="@@kit.bars">Bars</h2><app-ui-meter label="Health example" i18n-label="@@kit.health" [value]="18" [max]="24" />
    <app-ui-meter label="Focus example" i18n-label="@@kit.focus" [value]="3" [max]="8" /><app-ui-meter label="XP example" i18n-label="@@kit.xp" [value]="40" [max]="100" />
    <app-ui-meter label="Unavailable resource" i18n-label="@@kit.resource" />
    <label for="kit-progress" i18n="@@kit.loadingExample">Loading example</label><progress id="kit-progress" aria-label="Loading example" i18n-aria-label="@@kit.progressName"></progress>
    <label for="kit-determinate" i18n="@@kit.transfer">Transfer example: 60%</label><progress id="kit-determinate" value="60" max="100">60%</progress>
    <h2 i18n="@@kit.frames">Frames and fallback</h2><div class="kit-row">
      <app-ui-item label="Signal crystal example" i18n-label="@@kit.item" rarity="Uncommon example" i18n-rarity="@@kit.itemRarity" [sample]="true" /><app-ui-item />
      <div class="portrait-frame"><span class="portrait-fallback" role="img" aria-label="Portrait unavailable" i18n-aria-label="@@kit.portrait">?</span></div>
    </div><div class="dialogue"><div class="dialogue-text"><p class="speaker" i18n="@@kit.speaker">Speaker nameplate</p><p class="passage" i18n="@@kit.dialogue">Dialogue text stays selectable and resizes with reading preferences.</p></div></div>
    <details class="kit-help"><summary i18n="@@kit.rarity">About item rarity</summary><p i18n="@@kit.rarityHelp">Rarity is always written beside its symbol. Colour alone never conveys an item category or status.</p></details>
    <h2 i18n="@@kit.status">Status</h2><p class="kit-status" i18n="@@kit.saved"><app-ui-icon name="saved" />Saved example</p>
    <p class="kit-status" role="status" i18n="@@kit.loadingHelp"><app-ui-icon name="loading" />Loading example; text remains available.</p>
    <p class="kit-status error-text" i18n="@@kit.errorHelp"><app-ui-icon name="error" />Error example; keep your draft and retry when connected.</p>
    <h2 i18n="@@kit.icons">Icons</h2><ul class="icon-inventory">@for (name of names; track name) { <li><app-ui-icon [name]="name" /><code>{{ name }}</code></li> }</ul>
    <h2 i18n="@@kit.sources">Sources and rights</h2><p i18n="@@kit.rights">Original editable SVG and CSS geometry under the project MIT license. No external artwork or fonts. See Settings credits and the distributed UI source inventory.</p>
  </section>`,
})
export class GraphicKit {
  readonly selectedLabel = $localize`:@@kit.selected:Selected`;
  readonly selectLabel = $localize`:@@kit.select:Select sample`;
  readonly selected = signal(false);
  readonly names = Object.keys(ICONS);
}
