/** Typed public presentation data for local previews; excludes hidden lore and campaign values. */

/** Closed vocabulary: content replaces text, never route identities or template instructions. */
export type ContentTerm = 'story' | 'sheet' | 'inventory' | 'journal' | 'characterName'
  | 'attributes' | 'resources' | 'skills' | 'abilities' | 'conditions' | 'equipment' | 'quests';

/** Public definition identity and display name; deliberately excludes current values. */
export interface NamedDefinition {
  readonly id: string;
  readonly name: string;
}

/** Presence enables a supported optional panel; text never acts as a command or URL. */
export interface OptionalPanelMetadata {
  readonly title?: string;
  readonly description: string;
}

/** Public projection, separate from canonical state and the compiler's authored-pack schema. */
export interface ContentMetadata {
  readonly terms?: Readonly<Partial<Record<ContentTerm, string>>>;
  readonly attributes: readonly NamedDefinition[];
  readonly resources: readonly NamedDefinition[];
  readonly modules?: {
    readonly guide?: OptionalPanelMetadata;
    readonly creativeAbilities?: OptionalPanelMetadata;
  };
}

/** Neutral English fallbacks; language selection and catalog resolution belong to P05.6. */
const defaults: Readonly<Record<ContentTerm, string>> = {
  story: 'Story', sheet: 'Character', inventory: 'Inventory', journal: 'Journal',
  characterName: 'Character name', attributes: 'Attributes', resources: 'Resources',
  skills: 'Skills', abilities: 'Abilities', conditions: 'Conditions', equipment: 'Equipment', quests: 'Known quests',
};

/** Resolve optional text using a fallback for absent/blank values; never interpret markup. */
export function displayLabel(value: string | undefined, fallback: string): string {
  return value?.trim() || fallback;
}

/** Resolve a closed term for a scoped projection; missing metadata uses neutral wording. */
export function contentTerm(metadata: ContentMetadata | undefined, term: ContentTerm): string {
  return displayLabel(metadata?.terms?.[term], defaults[term]);
}
