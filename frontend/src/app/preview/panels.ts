/** Content-bound shells expose public definitions, never invented canonical values or hidden lore. */
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { displayLabel } from './content-metadata';
import { WorkspaceContext } from './workspace';
import { UiItem, UiMeter } from './ui-kit';

/** Render scoped attribute/resource names with unavailable values; no saved state is accepted. */
@Component({
  selector: 'app-sheet', imports: [UiMeter], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<section class="panel"><h1>{{ context.term('sheet') }}</h1><p i18n="@@panels.noCharacter">No character is connected to this preview.</p>
    <h2>{{ context.term('attributes') }}</h2>
    @if (context.adventure()?.content?.attributes?.length) {
      <dl>@for (attribute of context.adventure()?.content?.attributes; track attribute.id) {
        <div><dt>{{ label(attribute.name, attribute.id) }}</dt><dd i18n="@@panels.disconnected">Not connected</dd></div>
      }</dl>
    } @else { <p i18n="@@panels.noAttributes">No attribute definitions to display.</p> }
    <h2>{{ context.term('resources') }}</h2>
    @if (context.adventure()?.content?.resources?.length) {
      <dl>@for (resource of context.adventure()?.content?.resources; track resource.id) {
        <div [attr.data-resource]="resource.id"><dt>{{ label(resource.name, resource.id) }}</dt><dd><app-ui-meter [label]="label(resource.name, resource.id)" /></dd></div>
      }</dl>
    } @else { <p i18n="@@panels.noResources">No resource definitions to display.</p> }
    <h2>{{ context.term('skills') }}</h2><p i18n="@@panels.values">Accepted character values will appear here.</p>
    <h2>{{ context.term('conditions') }}</h2><p i18n="@@panels.conditions">No current conditions to display.</p>
    <h2>{{ context.term('abilities') }}</h2><p i18n="@@panels.abilities">No available abilities to display.</p></section>`,
})
export class Sheet {
  readonly context = inject(WorkspaceContext);
  readonly label = displayLabel;
}

/** Render inventory/equipment placeholders; unavailable actions never imply ownership changes. */
@Component({
  selector: 'app-inventory', imports: [UiItem], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<section class="panel"><h1>{{ context.term('inventory') }}</h1><p i18n="@@panels.noInventory">No campaign inventory is connected.</p>
    <h2 i18n="@@panels.items">Carried items</h2><p i18n="@@panels.itemHelp">Known owned items will appear here with quantities and explicit rarity labels.</p><app-ui-item />
    <h2>{{ context.term('equipment') }}</h2><p i18n="@@panels.equipment">No equipment to inspect.</p><button disabled i18n="@@panels.equip">Equip item</button></section>`,
})
export class Inventory {
  readonly context = inject(WorkspaceContext);
}

/** Render journal sections without disclosing authored quests or hidden world facts. */
@Component({
  selector: 'app-journal', changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<section class="panel"><h1>{{ context.term('journal') }}</h1><p i18n="@@panels.noJournal">No campaign journal is connected.</p>
    <h2>{{ context.term('quests') }}</h2><p i18n="@@panels.objectives">Discovered objectives will appear here.</p>
    <h2 i18n="@@panels.discoveries">Discoveries and promises</h2><p i18n="@@panels.noDiscoveries">No recorded discoveries or promises.</p>
    <h2 i18n="@@panels.events">Event history</h2><p i18n="@@panels.eventHelp">Accepted campaign events will appear here.</p></section>`,
})
export class Journal {
  readonly context = inject(WorkspaceContext);
}

/** Render configured public panels; disabled/direct/unknown requests show an unavailable state. */
@Component({
  selector: 'app-content-panel', changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<section class="panel">
    @if (panel(); as current) {
      <h1>{{ current.title }}</h1><p>{{ current.description }}</p>
      <p i18n="@@panels.panelHelp">No campaign is connected. This panel previews content configuration only.</p>
      @if (creative()) { <button disabled i18n="@@panels.create">Create ability</button> }
    } @else { <h1 i18n="@@panels.missing">Panel unavailable</h1><p i18n="@@panels.disabled">This content does not enable the requested panel.</p> }
  </section>`,
})
export class ContentPanel {
  private readonly context = inject(WorkspaceContext);
  private readonly route = inject(ActivatedRoute);
  private readonly data = toSignal(this.route.data, { initialValue: this.route.snapshot.data });
  readonly creative = computed(/** Derive the fixed route capability, including after reuse. */ () => this.data()['panel'] === 'creativeAbilities');
  readonly panel = computed(/** Fail closed on unknown kinds; absent config disables even a direct URL. */ () => {
    const modules = this.context.adventure()?.content.modules;
    const kind: unknown = this.data()['panel'];
    const config = kind === 'guide' ? modules?.guide : kind === 'creativeAbilities' ? modules?.creativeAbilities : undefined;
    return config ? { title: displayLabel(config.title, kind === 'guide' ? $localize`:@@terms.guide:Guide` : $localize`:@@terms.creative:Creative abilities`), description: config.description } : undefined;
  });
}
