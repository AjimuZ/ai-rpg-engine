/** Preview-only routes; adventure identity is explicit and never inferred from display names. */
import { Routes } from '@angular/router';
import { Library } from './preview/library';
import { Workspace } from './preview/workspace';
import { Creation } from './preview/creation';
import { Story } from './preview/story';
import { Sheet, Inventory, Journal } from './preview/panels';
import { Settings } from './preview/settings';

/** Route screens and browser titles; unmatched paths return to the local library. */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'library' },
  { path: 'library', component: Library, title: 'Library | Adventure engine' },
  { path: 'settings', component: Settings, title: 'Settings | Adventure engine' },
  { path: 'adventures/:adventureId', component: Workspace, children: [
    { path: '', pathMatch: 'full', redirectTo: 'story' },
    { path: 'story', component: Story, title: 'Story | Adventure engine' },
    { path: 'create', component: Creation, title: 'New playthrough | Adventure engine' },
    { path: 'sheet', component: Sheet, title: 'Character | Adventure engine' },
    { path: 'inventory', component: Inventory, title: 'Inventory | Adventure engine' },
    { path: 'journal', component: Journal, title: 'Journal | Adventure engine' },
  ] },
  { path: '**', redirectTo: 'library' },
];
