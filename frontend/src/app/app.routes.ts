/** Preview-only routes; adventure identity is explicit and never inferred from display names. */
import { Routes } from '@angular/router';
import { Library } from './preview/library';
import { Workspace } from './preview/workspace';
import { Creation } from './preview/creation';
import { Story } from './preview/story';
import { Sheet, Inventory, Journal, ContentPanel } from './preview/panels';
import { Settings } from './preview/settings';
import { GraphicKit } from './preview/ui-kit';

/** Route screens and browser titles; unmatched paths return to the local library. */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'library' },
  { path: 'library', component: Library, title: $localize`:@@shell.libraryTitle:Library | Adventure engine` },
  { path: 'settings', component: Settings, title: $localize`:@@shell.settingsTitle:Settings | Adventure engine` },
  { path: 'interface-kit', component: GraphicKit, title: $localize`:@@shell.kitTitle:Interface kit | Adventure engine` },
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
