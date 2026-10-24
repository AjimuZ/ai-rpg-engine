/** Inspectable feature shells show explicit empty states rather than invented canonical game data. */
import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Render character sections; no inputs or saved statistics are accepted at this stage. */
@Component({
  selector: 'app-sheet', changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<section class="panel"><h1>Character sheet</h1><p>No character is connected to this preview.</p>
    <h2>Attributes and skills</h2><p>Accepted character values will appear here.</p>
    <h2>Resources and conditions</h2><p>No current resources or conditions to display.</p>
    <h2>Abilities</h2><p>No available abilities to display.</p></section>`,
})
export class Sheet {}

/** Render inventory/equipment placeholders; unavailable actions never imply ownership changes. */
@Component({
  selector: 'app-inventory', changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<section class="panel"><h1>Inventory</h1><p>No campaign inventory is connected.</p>
    <h2>Carried items</h2><p>Known owned items will appear here with quantities and explicit rarity labels.</p>
    <h2>Equipment</h2><p>No equipment to inspect.</p><button disabled>Equip item</button></section>`,
})
export class Inventory {}

/** Render journal sections without disclosing authored quests or hidden world facts. */
@Component({
  selector: 'app-journal', changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<section class="panel"><h1>Journal</h1><p>No campaign journal is connected.</p>
    <h2>Known quests</h2><p>Discovered objectives will appear here.</p>
    <h2>Discoveries and promises</h2><p>No recorded discoveries or promises.</p>
    <h2>Event history</h2><p>Accepted campaign events will appear here.</p></section>`,
})
export class Journal {}
