/** Local, public sample content and reading preferences; never an authoritative game projection. */
import { Injectable, signal } from '@angular/core';
import { ContentMetadata } from './content-metadata';

/** A display-only passage; player text must come from an explicit player submission. */
export interface Passage {
  readonly speaker: 'narrator' | 'character' | 'player';
  readonly name: string;
  readonly text: string;
  readonly speakerId?: string;
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

/** Hand-authored public projections; fixture terms are mirrored, while registered pack APIs belong to P06. */
const previews: readonly AdventurePreview[] = [
  {
    id: 'mosslight', title: 'Mosslight Trail', description: 'A woodland exploration preview.',
    scene: '/scenes/woodland.svg', sceneAlt: 'A winding trail crosses a quiet grove beneath layered green canopies.',
    location: 'Trailhead', passages: [
      { speaker: 'narrator', name: 'Narrator', text: 'The trail opens into a quiet grove. Pale light settles on the path ahead.' },
      { speaker: 'character', speakerId: 'keeper', name: 'Trail keeper', text: 'The path continues through the trees. What would you like to know?' },
    ],
    speakers: { keeper: { name: 'Trail keeper', portrait: '/portraits/keeper.svg' } },
    content: {
      attributes: [{ id: 'awareness', name: 'Awareness' }],
      resources: [{ id: 'vigor', name: 'Vigor' }],
      // Preview-only configuration does not enable the authored-pack compiler's module runtime.
      modules: { guide: { title: 'Trail guide', description: 'A place for public exploration guidance.' } },
    },
  },
  {
    id: 'relay', title: 'Silent Relay', description: 'A science-fiction salvage preview.',
    scene: '/scenes/station.svg', sceneAlt: 'A blue-lit station corridor leads from the docking ring toward a silent relay.',
    location: 'Dock', passages: [
      { speaker: 'narrator', name: 'Narrator', text: 'Beyond the docking ring, a line of blue lamps marks the station corridor.' },
      { speaker: 'character', speakerId: 'attendant', name: 'Dock attendant', text: 'The relay is quiet. Where would you like to begin your survey?' },
    ],
    speakers: { attendant: { name: 'Dock attendant', portrait: '/portraits/attendant.svg' } },
    content: {
      terms: { sheet: 'Crew profile', inventory: 'Cargo', journal: 'Mission log', characterName: 'Specialist name',
        abilities: 'Techniques', equipment: 'Installed equipment', quests: 'Known assignments' },
      attributes: [{ id: 'logic', name: 'Logic' }],
      resources: [{ id: 'charge', name: 'Charge' }],
      modules: { creativeAbilities: { title: 'Field techniques', description: 'A place to inspect supported custom techniques.' } },
    },
  },
];

/** Own tab-lifetime preview state; accepts local IDs and rejects invalid values without throwing. */
@Injectable({ providedIn: 'root' })
export class PreviewState {
  readonly adventures = previews;
  private readonly positions = signal<Readonly<Record<string, number>>>({});
  private readonly size = signal(18);
  readonly textSize = this.size.asReadonly();
  private readonly layout = signal<'visual-novel' | 'text-focused'>('visual-novel');
  readonly readingLayout = this.layout.asReadonly();

  /** Accept a supported layout string; unknown values leave the tab preference unchanged. */
  setReadingLayout(value: string): void {
    if (value === 'visual-novel' || value === 'text-focused') this.layout.set(value);
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
  }

  /** Validate a bounded name and return a local summary; never create or save a campaign. */
  previewCharacter(adventureId: string, rawName: string): { name: string; adventureId: string } | undefined {
    const name = rawName.trim();
    if (!this.find(adventureId) || name.length === 0 || name.length > 60) return undefined;
    return { name, adventureId };
  }

  /** Set a finite integral reading size from 15–24 px; ignore unsupported values. */
  setTextSize(value: number): void {
    if (Number.isInteger(value) && value >= 15 && value <= 24) this.size.set(value);
  }
}
