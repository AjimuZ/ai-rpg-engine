/** Preview and saved-library routes; adventure identity is explicit and never inferred from display names. */
import { Routes, UrlMatchResult, UrlSegment } from '@angular/router';
import { Library } from './preview/library';
import { Workspace } from './preview/workspace';
import { Creation } from './preview/creation';
import { Story } from './preview/story';
import { Sheet, Inventory, Journal, ContentPanel } from './preview/panels';
import { Settings } from './preview/settings';
import { GraphicKit } from './preview/ui-kit';
import { SavedAdventureView } from './library/saved-library';
import { PlaythroughCreation } from './library/playthrough-creation';
import { isUuid } from './library/saved-library-client';

/**
 * Match `adventures/<canonical uuid>/create`, the creation screen of a saved adventure (P14.1-C). Preview
 * identities are never UUIDs, so their workspace keeps its own `create` preview.
 */
export function savedCreation(segments: UrlSegment[]): UrlMatchResult | null {
  return segments.length === 3 && segments[0].path === 'adventures' && isUuid(segments[1].path) && segments[2].path === 'create'
    ? { consumed: segments, posParams: { adventureId: segments[1] } } : null;
}

/** Route screens and browser titles; unmatched paths return to the local library. */
export const routes: Routes = [
  { matcher: savedCreation, component: PlaythroughCreation, title: $localize`:@@shell.createTitle:New playthrough | Adventure engine` },
  { path: '', pathMatch: 'full', redirectTo: 'library' },
  { path: 'library', component: Library, title: $localize`:@@shell.libraryTitle:Library | Adventure engine` },
  { path: 'settings', component: Settings, title: $localize`:@@shell.settingsTitle:Settings | Adventure engine` },
  { path: 'interface-kit', component: GraphicKit, title: $localize`:@@shell.kitTitle:Interface kit | Adventure engine` },
  // Saved adventures are addressed by server UUID only; the playthrough is an optional query parameter.
  { path: 'saved/:adventureId', component: SavedAdventureView, title: $localize`:@@shell.savedTitle:Saved adventure | Adventure engine` },
  { path: 'adventures/:adventureId', component: Workspace, children: [
    { path: '', pathMatch: 'full', redirectTo: 'story' },
    { path: 'story', component: Story, title: $localize`:@@shell.storyTitle:Story | Adventure engine` },
    { path: 'create', component: Creation, title: $localize`:@@shell.createTitle:New playthrough | Adventure engine` },
    { path: 'sheet', component: Sheet, title: $localize`:@@shell.sheetTitle:Character | Adventure engine` },
    { path: 'inventory', component: Inventory, title: $localize`:@@shell.inventoryTitle:Inventory | Adventure engine` },
    { path: 'journal', component: Journal, title: $localize`:@@shell.journalTitle:Journal | Adventure engine` },
    { path: 'guide', component: ContentPanel, data: { panel: 'guide' }, title: $localize`:@@shell.guideTitle:Guide | Adventure engine` },
    { path: 'creative', component: ContentPanel, data: { panel: 'creativeAbilities' }, title: $localize`:@@shell.creativeTitle:Creative abilities | Adventure engine` },
  ] },
  { path: '**', redirectTo: 'library' },
];
