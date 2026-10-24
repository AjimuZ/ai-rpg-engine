/** Local, public sample content and reading preferences; never an authoritative game projection. */
import { Inject, Injectable, InjectionToken, signal } from '@angular/core';
import { DraftStorage, QuickReply } from './story-input-state';
import { ContentMetadata } from './content-metadata';

/** A display-only passage; player text must come from an explicit player submission. */
export interface Passage {
  readonly speaker: 'narrator' | 'character' | 'player';
  readonly name: string;
  readonly text: string;
  readonly speakerId?: string;
  readonly suggestions?: readonly QuickReply[];
}

/** Public portrait identity within one preview; absent artwork leaves attribution usable. */
export interface PublicSpeaker {
  readonly name: string;
  readonly portrait?: string;
}

/** Small fixture preview contract; IDs are local examples, never registered workspace IDs. */
export interface AdventurePreview {
  readonly id: string;
  readonly title: string;
  readonly description: string;
  readonly scene: string;
  readonly sceneAlt: string;
  readonly location: string;
  readonly passages: readonly Passage[];
  readonly speakers: Readonly<Record<string, PublicSpeaker>>;
  readonly content: ContentMetadata;
}

/** Reviewed locale-specific public preview text; stable titles, IDs and artwork are unchanged.
 * Hand-authored public projections; fixture terms are mirrored, while registered pack APIs belong to P06. */
const previews: readonly AdventurePreview[] = [
  {
    id: 'mosslight', title: 'Mosslight Trail', description: $localize`:@@preview.woodlandDescription:A woodland exploration preview.`,
    scene: '/scenes/woodland.svg', sceneAlt: $localize`:@@preview.woodlandAlt:A winding trail crosses a quiet grove beneath layered green canopies.`,
    location: $localize`:@@preview.trailhead:Trailhead`, passages: [
      { speaker: 'narrator', name: $localize`:@@preview.narrator:Narrator`, text: $localize`:@@preview.grovePassage:The trail opens into a quiet grove. Pale light settles on the path ahead.`,
        suggestions: [{ id: 'observe', kind: 'action', text: $localize`:@@preview.observeGrove:Look around the grove.` }, { id: 'listen', kind: 'action', text: $localize`:@@preview.listenGrove:Listen to the sounds nearby.` }] },
      { speaker: 'character', speakerId: 'keeper', name: $localize`:@@preview.keeper:Trail keeper`, text: $localize`:@@preview.keeperPassage:The path continues through the trees. What would you like to know?`,
        suggestions: [{ id: 'ask', kind: 'question', text: $localize`:@@preview.askTrail:What lies along the trail?` }] },
    ],
    speakers: { keeper: { name: $localize`:@@preview.keeper:Trail keeper`, portrait: '/portraits/keeper.svg' } },
    content: {
      attributes: [{ id: 'awareness', name: $localize`:@@preview.awareness:Awareness` }],
      resources: [{ id: 'vigor', name: $localize`:@@preview.vigor:Vigor` }],
      // Preview-only configuration does not enable the authored-pack compiler's module runtime.
      modules: { guide: { title: $localize`:@@preview.guide:Trail guide`, description: $localize`:@@preview.guideDescription:A place for public exploration guidance.` } },
    },
  },
  {
    id: 'relay', title: 'Silent Relay', description: $localize`:@@preview.relayDescription:A science-fiction salvage preview.`,
    scene: '/scenes/station.svg', sceneAlt: $localize`:@@preview.stationAlt:A blue-lit station corridor leads from the docking ring toward a silent relay.`,
    location: $localize`:@@preview.dock:Dock`, passages: [
      { speaker: 'narrator', name: $localize`:@@preview.narrator:Narrator`, text: $localize`:@@preview.stationPassage:Beyond the docking ring, a line of blue lamps marks the station corridor.`,
        suggestions: [{ id: 'observe', kind: 'action', text: $localize`:@@preview.observeStation:Look along the station corridor.` }] },
      { speaker: 'character', speakerId: 'attendant', name: $localize`:@@preview.attendant:Dock attendant`, text: $localize`:@@preview.attendantPassage:The relay is quiet. Where would you like to begin your survey?`,
        suggestions: [{ id: 'ask', kind: 'question', text: $localize`:@@preview.askRelay:What is known about the relay?` }] },
    ],
    speakers: { attendant: { name: $localize`:@@preview.attendant:Dock attendant`, portrait: '/portraits/attendant.svg' } },
    content: {
      terms: { sheet: $localize`:@@preview.crew:Crew profile`, inventory: $localize`:@@preview.cargo:Cargo`, journal: $localize`:@@preview.missionLog:Mission log`, characterName: $localize`:@@preview.specialist:Specialist name`,
        abilities: $localize`:@@preview.techniques:Techniques`, equipment: $localize`:@@preview.equipment:Installed equipment`, quests: $localize`:@@preview.assignments:Known assignments` },
      attributes: [{ id: 'logic', name: $localize`:@@preview.logic:Logic` }],
      resources: [{ id: 'charge', name: $localize`:@@preview.charge:Charge` }],
      modules: { creativeAbilities: { title: $localize`:@@preview.fieldTechniques:Field techniques`, description: $localize`:@@preview.fieldDescription:A place to inspect supported custom techniques.` } },
    },
  },
];

/** Obtain tab-scoped storage; denied browser access falls back to memory. */
export const PREVIEW_READING_STORAGE = new InjectionToken<DraftStorage | null>('Preview reading storage', {
  providedIn: 'root', factory: /** Contain browser storage policy errors at the boundary. */ () => {
    try { return globalThis.sessionStorage ?? null; } catch { return null; }
  },
});

/** Own validated tab-scoped reading state; invalid persisted values use defaults without throwing. */
@Injectable({ providedIn: 'root' })
export class PreviewState {
  readonly adventures = previews;
  private readonly positions = signal<Readonly<Record<string, number>>>({});
  private readonly size = signal(18);
  readonly textSize = this.size.asReadonly();
  private readonly layout = signal<'visual-novel' | 'text-focused'>('visual-novel');
  readonly readingLayout = this.layout.asReadonly();
  private readonly saved = signal<'saved' | 'unavailable' | 'invalid'>('saved');
  readonly persistence = this.saved.asReadonly();

  /** Restore versioned local reading preferences and known passage IDs; unavailable storage keeps defaults. */
  constructor(@Inject(PREVIEW_READING_STORAGE) private readonly storage: DraftStorage | null = null) {
    let raw: string | null;
    try {
      if (!storage) throw new Error('Storage unavailable');
      raw = storage.getItem('engine.preview-reading.v1');
    } catch { this.saved.set('unavailable'); return; }
    if (raw === null) return;
    try {
      if (raw.length > 2000) { this.saved.set('invalid'); return; }
      const value = JSON.parse(raw);
      if (!value || value.version !== 1 || !Number.isInteger(value.size) || value.size < 15 || value.size > 24 ||
        !['visual-novel', 'text-focused'].includes(value.layout) || !value.positions ||
        typeof value.positions !== 'object' || Array.isArray(value.positions) ||
        Object.entries(value.positions).some(/** Unknown IDs or out-of-bounds positions cannot select arbitrary content. */ ([id, position]) => {
          const adventure = this.find(id);
          return !adventure || typeof position !== 'number' || !Number.isSafeInteger(position) || position < 0 || position >= adventure.passages.length;
        })) { this.saved.set('invalid'); return; }
      this.size.set(value.size);
      this.layout.set(value.layout);
      this.positions.set(value.positions);
    } catch { this.saved.set('invalid'); }
  }

  /** Persist the complete bounded reading snapshot; return false rather than reload away unsaved state. */
  prepareReload(): boolean {
    try {
      if (!this.storage) throw new Error('Storage unavailable');
      const snapshot = JSON.stringify({ version: 1, size: this.size(), layout: this.layout(), positions: this.positions() });
      this.storage.setItem('engine.preview-reading.v1', snapshot);
      if (this.storage.getItem('engine.preview-reading.v1') !== snapshot) throw new Error('Storage verification failed');
      this.saved.set('saved');
      return true;
    } catch { this.saved.set('unavailable'); return false; }
  }

  /** Accept a supported layout string; unknown values leave the tab preference unchanged. */
  setReadingLayout(value: string): void {
    if (value === 'visual-novel' || value === 'text-focused') { this.layout.set(value); this.prepareReload(); }
  }

  /** Resolve a local preview ID, returning undefined for unknown identities. */
  find(id: string): AdventurePreview | undefined {
    return this.adventures.find(/** Match identity, never title or path. */ (entry) => entry.id === id);
  }

  /** Read the local passage index for an ID; unseen IDs start at zero. */
  position(id: string): number {
    return this.positions()[id] ?? 0;
  }

  /** Reveal one already supplied passage; unknown IDs and the last passage are no-ops. */
  advance(id: string): void {
    const adventure = this.find(id);
    if (!adventure || adventure.passages.length === 0) return;
    const next = Math.min(this.position(id) + 1, adventure.passages.length - 1);
    this.positions.update(/** Preserve every other preview's independent position. */ (current) => ({ ...current, [id]: next }));
    this.prepareReload();
  }

  /** Validate a bounded name and return a local summary; never create or save a campaign. */
  previewCharacter(adventureId: string, rawName: string): { name: string; adventureId: string } | undefined {
    const name = rawName.trim();
    if (!this.find(adventureId) || name.length === 0 || name.length > 60) return undefined;
    return { name, adventureId };
  }

  /** Set a finite integral reading size from 15–24 px; ignore unsupported values. */
  setTextSize(value: number): void {
    if (Number.isInteger(value) && value >= 15 && value <= 24) { this.size.set(value); this.prepareReload(); }
  }
}
