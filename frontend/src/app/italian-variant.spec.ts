/**
 * The Italian variant rendered with the reviewed catalog (P05.6, QA-35).
 *
 * Production builds inline `src/locale/messages.it.xlf` into the `it` bundle at compile time, and
 * jsdom cannot load that ES-module bundle. This suite therefore loads the same reviewed targets into
 * `$localize` at run time, before any application module is evaluated (all application code is
 * imported dynamically after `loadTranslations`), and renders the real routes with `LOCALE_ID` `it`:
 *
 * - every routed preview screen, the navigation shell and the saved playthrough show no English-only
 *   catalog text in visible text or accessible attributes, apart from content explicitly marked with
 *   another `lang`, and every control, link, image and landmark keeps a non-empty accessible name;
 * - the recorded real-server replies (`campaign/contract/campaign-reload-transcript.json`) render
 *   with the reviewed Italian statuses, receipts, narration and connection notices, receipt list
 *   label, composer label/placeholder/help and document title;
 * - a real `CAMPAIGN_SCOPE_UNAVAILABLE` reply and unknown codes produce the Italian sentences, never
 *   the raw code; Italian receipts carry exactly the committed numbers, with only digit grouping
 *   following the locale.
 *
 * What this does not establish: it is jsdom, not a browser, so layout, focus rings, contrast and
 * actual screen-reader output are not exercised, and it does not replace independent review of
 * the Italian wording. The serializer below mirrors Angular's `Xliff2TranslationParser` for the
 * inline elements the catalog uses (`ph`, `pc`, ICU text); a wrong placeholder name makes
 * `$localize` throw, so a serializer mismatch fails loudly instead of rendering English.
 */
import { registerLocaleData } from '@angular/common';
import italianLocaleData from '@angular/common/locales/it';
import { LOCALE_ID } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { clearTranslations, loadTranslations } from '@angular/localize';
import { provideRouter, Router } from '@angular/router';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import catalogXml from '../locale/messages.it.xlf' with { loader: 'text' };
import type { App } from './app';
import type { CampaignPlaythrough } from './campaign/campaign-view';
import type { PublicReceipt } from './campaign/campaign-protocol';
import {
  ADVENTURE, CAMPAIGN, EXCHANGES, FOREIGN_CAMPAIGN, MOVE, ROUTE, SYNC_KEY, TranscriptServer, WAIT,
} from './campaign/contract/transcript-server';

const XLIFF = 'urn:oasis:names:tc:xliff:document:2.0';

/** One reviewed catalog entry as `$localize` message strings (`{$NAME}` marks a placeholder). */
interface CatalogMessage {
  readonly source: string;
  readonly target: string;
}

/**
 * Serialize an XLIFF 2 `source`/`target` element as a `$localize` message string.
 *
 * `ph` becomes `{$equiv}`; `pc` becomes `{$equivStart}…{$equivEnd}` around its children; ICU text is
 * kept verbatim. Inside an ICU expression (brace depth above zero) placeholders are plain `{NAME}`
 * text instead, because Angular substitutes ICU interpolations itself rather than through
 * `$localize`; the parser used by the build renders them the same way. Any other inline element
 * and unbalanced ICU braces throw, so a catalog that starts using them cannot silently lose text.
 */
function serialize(element: Element): string {
  let text = '';
  let depth = 0;
  const placeholder = (name: string | null): string => depth > 0 ? `{${name}}` : `{$${name}}`;
  const visit = (parent: Element): void => {
    for (const node of Array.from(parent.childNodes)) {
      if (node.nodeType === node.TEXT_NODE) {
        const value = node.textContent ?? '';
        text += value;
        for (const character of value) depth += character === '{' ? 1 : character === '}' ? -1 : 0;
      } else if (node.nodeType === node.ELEMENT_NODE && (node as Element).localName === 'ph') {
        text += placeholder((node as Element).getAttribute('equiv'));
      } else if (node.nodeType === node.ELEMENT_NODE && (node as Element).localName === 'pc') {
        const container = node as Element;
        text += placeholder(container.getAttribute('equivStart'));
        visit(container);
        text += placeholder(container.getAttribute('equivEnd'));
      } else {
        throw new Error(`Unsupported XLIFF inline node ${node.nodeName}`);
      }
    }
  };
  visit(element);
  if (depth !== 0) throw new Error(`Unbalanced ICU braces in ${element.parentElement?.parentElement?.getAttribute('id')}`);
  return text;
}

/**
 * Parse the reviewed catalog into messages keyed by stable ID.
 *
 * @throws Error on malformed XML, a duplicate ID or a unit without exactly one source and target
 */
function readCatalog(xml: string): ReadonlyMap<string, CatalogMessage> {
  const document = new DOMParser().parseFromString(xml, 'application/xml');
  if (document.getElementsByTagName('parsererror').length > 0) throw new Error('Malformed Italian catalog');
  const messages = new Map<string, CatalogMessage>();
  for (const unit of Array.from(document.getElementsByTagNameNS(XLIFF, 'unit'))) {
    const id = unit.getAttribute('id') ?? '';
    const sources = unit.getElementsByTagNameNS(XLIFF, 'source');
    const targets = unit.getElementsByTagNameNS(XLIFF, 'target');
    if (!id || messages.has(id) || sources.length !== 1 || targets.length !== 1) throw new Error(`Invalid catalog unit ${id}`);
    messages.set(id, { source: serialize(sources[0]), target: serialize(targets[0]) });
  }
  return messages;
}

const CATALOG = readCatalog(catalogXml);
/** A `$localize` placeholder outside ICU expressions; only used with `replace`, which resets the global state. */
const PLACEHOLDER = /\{\$([^}]+)\}/g;
/** Splits a message string at placeholders and ICU braces, leaving only literal runs. */
const LITERAL_BOUNDARY = /\{\$[^}]+\}|[{}]/;

/** Collapse whitespace runs so template indentation does not affect comparisons. */
function normalize(value: string): string {
  return value.replace(/\s+/g, ' ').trim();
}

/** Normalized literal runs of a message string. */
function literals(value: string): string[] {
  return value.split(LITERAL_BOUNDARY).map(normalize).filter(Boolean);
}

/**
 * Return a catalog message with its placeholders substituted, as `$localize` renders it.
 *
 * @param id stable message ID
 * @param values substitution per placeholder name; a missing name throws
 * @param side `target` (Italian, default) or `source` (English)
 */
function message(id: string, values: Readonly<Record<string, string>> = {}, side: keyof CatalogMessage = 'target'): string {
  const entry = CATALOG.get(id);
  if (!entry) throw new Error(`Unknown message ${id}`);
  return normalize(entry[side].replace(PLACEHOLDER, (_, name: string) => {
    if (!Object.hasOwn(values, name)) throw new Error(`Missing placeholder ${name} for ${id}`);
    return values[name];
  }));
}

/** Literal runs of every Italian target; English text Italian shares (names, units, "px") is not a leftover. */
const ITALIAN_RUNS = new Set([...CATALOG.values()].flatMap((entry) => literals(entry.target)));
const ITALIAN_TEXT = [...ITALIAN_RUNS].join('\n');
/** English messages whose Italian target differs. */
const TRANSLATED = [...CATALOG.values()].filter((entry) => entry.source !== entry.target);

/** Whole English messages without placeholders whose Italian target differs; seeing one means an untranslated string. */
const ENGLISH_MESSAGES = new Set(TRANSLATED
  .filter((entry) => !entry.source.includes('{$'))
  .map((entry) => normalize(entry.source))
  .filter((value) => /\p{L}/u.test(value) && !ITALIAN_RUNS.has(value)));

/** English literal runs of two or more words that no Italian target contains. */
const ENGLISH_RUNS = [...new Set(TRANSLATED.flatMap((entry) => literals(entry.source))
  .filter((run) => run.split(' ').length >= 2 && /\p{L}{2}/u.test(run) && !ITALIAN_TEXT.includes(run)))];

/** Attributes whose values reach assistive technology or tooltips. */
const ACCESSIBLE_ATTRIBUTES = ['aria-label', 'aria-valuetext', 'aria-roledescription', 'aria-placeholder', 'title', 'placeholder', 'alt'];

/** A human-facing string found in the rendered DOM, with a short location for diagnostics. */
interface Found {
  readonly where: string;
  readonly value: string;
}

/** Language that applies to a node: its nearest explicit `lang`, or the variant language when none is set. */
function languageOf(node: Node): string {
  const element = node.nodeType === node.ELEMENT_NODE ? node as Element : node.parentElement;
  return element?.closest('[lang]')?.getAttribute('lang') ?? 'it';
}

/** Describe an element briefly (tag, id or first class) for failure messages. */
function where(element: Element | null): string {
  if (!element) return '?';
  return element.localName + (element.id ? `#${element.id}` : element.classList.length ? `.${element.classList[0]}` : '');
}

/**
 * Collect visible text nodes and accessible attribute values under a root that are declared to be in
 * the Italian variant's language. Text inside `style`/`script` and form-control values (user drafts)
 * are not interface strings.
 */
function interfaceStrings(root: Element): Found[] {
  const found: Found[] = [];
  const walker = root.ownerDocument.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
  for (let node: Node | null = walker.currentNode; node; node = walker.nextNode()) {
    if (node.nodeType === node.TEXT_NODE) {
      const value = normalize(node.textContent ?? '');
      const parent = node.parentElement;
      if (value && parent && !['style', 'script', 'textarea'].includes(parent.localName) && languageOf(node) === 'it') found.push({ where: where(parent), value });
    } else {
      const element = node as Element;
      for (const name of ACCESSIBLE_ATTRIBUTES) {
        const value = normalize(element.getAttribute(name) ?? '');
        if (value && languageOf(element) === 'it') found.push({ where: `${where(element)}[${name}]`, value });
      }
    }
  }
  return found;
}

/** Strings that are English catalog text the Italian variant should have replaced. */
function englishLeftovers(root: Element): string[] {
  return interfaceStrings(root)
    .filter(({ value }) => ENGLISH_MESSAGES.has(value) || ENGLISH_RUNS.some((run) => value.includes(run)))
    .map(({ where: location, value }) => `${location}: ${value}`);
}

/**
 * English function words that do not occur as whole words in Italian interface text. A string in
 * the Italian variant containing one is English that never went through the catalog (for example a
 * hard-coded label or server text missing its `lang`), whether or not the catalog knows it.
 */
const ENGLISH_WORDS = /(?:^|[^\p{L}])(?:the|and|your|you|this|that|with|from|not|is|are|was|of|to|for|has|have|been|will|can)(?=$|[^\p{L}])/iu;

/** Italian-language strings that read as English sentences or phrases; identifiers in `code` are exempt. */
function englishText(root: Element): string[] {
  return interfaceStrings(root)
    .filter(({ where: location, value }) => !location.startsWith('code') && ENGLISH_WORDS.test(value))
    .map(({ where: location, value }) => `${location}: ${value}`);
}

/** Text referenced by an `aria-labelledby`/`aria-describedby` list, or null when an ID is missing. */
function referencedText(element: Element, attribute: string): string | null {
  const ids = (element.getAttribute(attribute) ?? '').split(/\s+/).filter(Boolean);
  const parts = ids.map((id) => element.ownerDocument.getElementById(id));
  return parts.every((part) => part !== null) ? normalize(parts.map((part) => part!.textContent ?? '').join(' ')) : null;
}

/**
 * Accessible name approximating the accname rules this interface relies on: `aria-labelledby`,
 * `aria-label`, an associated or wrapping `label` for form controls, `alt` for images, then text.
 */
function accessibleName(element: Element): string {
  if (element.hasAttribute('aria-labelledby')) return referencedText(element, 'aria-labelledby') ?? '';
  const label = normalize(element.getAttribute('aria-label') ?? '');
  if (label) return label;
  if (['input', 'select', 'textarea'].includes(element.localName)) {
    const explicit = element.id ? element.ownerDocument.querySelector(`label[for="${element.id}"]`) : null;
    return normalize((explicit ?? element.closest('label'))?.textContent ?? '');
  }
  if (element.localName === 'img') return normalize(element.getAttribute('alt') ?? '');
  return normalize(element.textContent ?? '');
}

/**
 * Report controls, links and images that assistive technology could not name, empty translated
 * labels, broken ARIA ID references, alerts without text and `lang` values outside the allowlist.
 * Landmarks are not named from their content, and a single unlabeled landmark is valid, so only
 * labels that exist are checked for them.
 */
function accessibilityProblems(root: Element): string[] {
  const problems: string[] = [];
  for (const element of Array.from(root.querySelectorAll('a[href], button, input, select, textarea, [role="button"], [role="link"], [role="img"]'))) {
    if (element.closest('[aria-hidden="true"]')) continue;
    if (!accessibleName(element)) problems.push(`unnamed ${where(element)}`);
  }
  for (const element of Array.from(root.querySelectorAll('[aria-label]'))) {
    if (!normalize(element.getAttribute('aria-label')!)) problems.push(`empty aria-label on ${where(element)}`);
  }
  for (const image of Array.from(root.querySelectorAll('img'))) {
    // An empty alt is the explicit decorative marker; a missing alt leaves the file name to be read.
    if (!image.hasAttribute('alt') && !image.closest('[aria-hidden="true"]')) problems.push(`image without alt ${where(image)}`);
  }
  for (const svg of Array.from(root.querySelectorAll('svg'))) {
    if (!svg.closest('[aria-hidden="true"]') && svg.getAttribute('role') !== 'img') problems.push(`exposed decorative svg ${where(svg.parentElement)}`);
    if (svg.getAttribute('role') === 'img' && !accessibleName(svg)) problems.push(`unnamed svg ${where(svg.parentElement)}`);
  }
  for (const attribute of ['aria-labelledby', 'aria-describedby']) {
    for (const element of Array.from(root.querySelectorAll(`[${attribute}]`))) {
      if (referencedText(element, attribute) === null) problems.push(`${attribute} points to a missing ID on ${where(element)}`);
    }
  }
  for (const element of Array.from(root.querySelectorAll('[lang]'))) {
    if (!['en', 'it'].includes(element.getAttribute('lang') ?? '')) problems.push(`unsupported lang on ${where(element)}`);
  }
  for (const region of Array.from(root.querySelectorAll('[role="alert"]'))) {
    if (!normalize(region.textContent ?? '')) problems.push(`empty alert ${where(region)}`);
  }
  return problems;
}

/** Device/tab storage double that never denies (denial paths are covered by `ui-language.spec.ts`). */
class Memory {
  readonly values = new Map<string, string>();
  getItem(key: string): string | null { return this.values.get(key) ?? null; }
  setItem(key: string, value: string): void { this.values.set(key, value); }
}

/** Application modules, imported only after the Italian translations are loaded. */
async function application() {
  return {
    ...await import('./app'), ...await import('./app.routes'), ...await import('./campaign/campaign-messages'),
    ...await import('./campaign/campaign-view'), ...await import('./campaign/campaign-session'), ...await import('./campaign/campaign-protocol'),
    ...await import('./library/saved-library-client'), ...await import('./preview/story-input-state'),
    ...await import('./preview/ui-language'), fixtures: await import('./campaign/fixtures'),
  };
}
let modules: Awaited<ReturnType<typeof application>>;

/** Recorded adventure reply, reused to answer the library list the transcript does not contain. */
const RECORDED_ADVENTURE = EXCHANGES.find((entry) => entry.path === `/api/v1/adventures/${ADVENTURE}`)!.body;

/**
 * Configure the Italian variant with the replaying origin. The library list is answered with the
 * recorded adventure so the library cards render; every other request goes to the strict replay.
 */
function configure(server: TranscriptServer): void {
  const fetcher = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => String(input).startsWith('/api/v1/adventures?')
    ? Promise.resolve(new Response(`{"adventures":[${RECORDED_ADVENTURE}],"nextAfter":null}`, { headers: { 'Content-Type': 'application/json' } }))
    : server.fetch(input, init);
  const tab = new Memory();
  TestBed.configureTestingModule({ providers: [
    provideRouter(modules.routes), { provide: LOCALE_ID, useValue: 'it' }, { provide: modules.LIBRARY_FETCH, useValue: fetcher },
    { provide: modules.STORY_DRAFT_STORAGE, useValue: new Memory() }, { provide: modules.CAMPAIGN_SYNC_STORAGE, useValue: new Memory() },
    { provide: modules.LANGUAGE_BROWSER, useValue: { base: 'http://127.0.0.1:8080/it/', href: 'http://127.0.0.1:8080/it/settings',
      device: new Memory(), tab, navigate: () => undefined } },
  ] });
}

/** Wait in real time until a predicate holds; the replayed stream uses the view's real pauses. */
async function until(fixture: ComponentFixture<unknown>, ready: () => boolean, label: string): Promise<void> {
  for (let attempt = 0; attempt < 300 && !ready(); attempt++) {
    await new Promise((accept) => setTimeout(accept, 20));
    fixture.detectChanges();
    await fixture.whenStable();
  }
  expect(ready(), label).toBe(true);
}

/** Render the full shell and navigate it; returns the fixture whose element contains shell and route. */
async function shell(url: string): Promise<ComponentFixture<App>> {
  const fixture = TestBed.createComponent(modules.App);
  await TestBed.inject(Router).navigateByUrl(url);
  fixture.detectChanges();
  await fixture.whenStable();
  return fixture;
}

describe('Italian variant with the reviewed catalog', /** Register Italian rendering, wording and naming checks. */ () => {
  beforeAll(/** Translations must exist before any application module evaluates a `$localize` call. */ async () => {
    loadTranslations(Object.fromEntries([...CATALOG].map(([id, entry]) => [id, entry.target])));
    registerLocaleData(italianLocaleData);
    modules = await application();
  });
  afterAll(/** Leave the shared `$localize` without translations for any later file in this worker. */ () => clearTranslations());
  afterEach(/** Destroying fixtures aborts long polls. */ () => TestBed.resetTestingModule());

  it('loads every reviewed message and detects English leftovers', /** The detector itself must have something to find. */ () => {
    expect(CATALOG.size).toBe(412);
    expect(ENGLISH_MESSAGES.has('Send')).toBe(true);
    expect(ENGLISH_RUNS).toContain(message('campaign.view.composerHelp', {}, 'source'));
    // ICU branches are checked too: an untranslated plural would show "One passage".
    expect(ENGLISH_RUNS).toContain('One passage');
    // Uncatalogued English is caught by function words; Italian interface text is not.
    const probe = document.createElement('div');
    probe.innerHTML = '<p>Open the map</p><p lang="en">Read this aloud</p><p>Apri la mappa del sentiero</p><code>is-ready</code>';
    expect(englishText(probe)).toEqual(['p: Open the map']);
    expect([...CATALOG.values()].filter((entry) => ENGLISH_WORDS.test(normalize(entry.target.replace(PLACEHOLDER, ' ')))).length).toBe(0);
    // The accessibility rules report each defect class and accept the valid patterns this interface uses.
    const page = document.createElement('div');
    page.innerHTML = `<label for="named">Nome</label><input id="named"><textarea id="orphan"></textarea>
      <button aria-describedby="absent">Invia</button><button aria-label=" "></button><img src="a.svg"><img src="b.svg" alt="">
      <p lang="fr">Bonjour</p><svg></svg><span aria-hidden="true"><svg></svg></span><nav><a href="/">Libreria</a></nav>
      <p role="alert"></p>`;
    document.body.append(page);
    expect(accessibilityProblems(page)).toEqual(['unnamed textarea#orphan', 'unnamed button', 'empty aria-label on button',
      'image without alt img', 'exposed decorative svg div', 'aria-describedby points to a missing ID on button', 'unsupported lang on p',
      'empty alert p']);
    page.remove();
    expect($localize`:@@campaign.view.send:Send`).toBe(message('campaign.view.send'));
    expect($localize`:@@campaign.view.send:Send`).not.toBe('Send');
  });

  it('shows no English-only catalog text and names every control on each routed screen', /** Shell, previews and Settings. */ async () => {
    const screens = ['/library', '/settings', '/interface-kit', '/adventures/mosslight/story', '/adventures/mosslight/create',
      '/adventures/mosslight/sheet', '/adventures/mosslight/inventory', '/adventures/mosslight/journal', '/adventures/mosslight/guide',
      '/adventures/relay/story', '/adventures/relay/creative', '/adventures/unknown/story'];
    const report: Record<string, string[]> = {};
    for (const url of screens) {
      configure(new TranscriptServer());
      const fixture = await shell(url);
      if (url === '/library') {
        await until(fixture, () => fixture.nativeElement.querySelector('.saved-card') !== null, 'library cards');
        // Author metadata stays literal and is announced in the adventure's own language.
        expect(fixture.nativeElement.querySelector('.saved-card h3').getAttribute('lang')).toBe('en');
        expect(fixture.nativeElement.querySelector('.saved-card .badge').getAttribute('lang')).toBe('en');
      }
      const root = fixture.nativeElement as HTMLElement;
      const problems = [...englishLeftovers(root), ...englishText(root), ...accessibilityProblems(root)];
      if (problems.length > 0) report[url] = problems;
      expect(document.title, url).not.toMatch(/Adventure engine/);
      TestBed.resetTestingModule();
    }
    expect(report).toEqual({});
  });

  it('renders recorded real replies with the reviewed Italian statuses, receipts and notices', /** Same bytes as the contract spec. */ async () => {
    const server = new TranscriptServer();
    configure(server);
    const fixture = await shell(ROUTE);
    const root = fixture.nativeElement as HTMLElement;
    const sync = TestBed.inject(modules.CAMPAIGN_SYNC_STORAGE) as unknown as Memory;
    const cursor = () => (JSON.parse(sync.values.get(SYNC_KEY) ?? '{}') as { cursor?: string }).cursor;
    const text = (selector: string) => normalize(root.querySelector(selector)?.textContent ?? '');
    await until(fixture, () => cursor() === '0:0' && root.querySelector('.starting-situation .passage') !== null &&
      text('[data-connection]') === message('campaign.view.liveIdle'), 'first load');

    expect(document.title).toBe(message('shell.savedTitle'));
    expect(text('#starting-title')).toBe(message('campaign.view.startingTitle'));
    expect(root.querySelector('.starting-situation .passage')?.getAttribute('lang')).toBe('it');
    expect(root.querySelector('[data-fallback]')).toBeNull();
    expect(text('.selected-playthrough')).toBe(message('saved.selected', { INTERPOLATION: 'Lantern walk' }));
    expect(text('label[for="campaign-input"]')).toBe(message('campaign.view.inputLabel'));
    expect(root.querySelector('#campaign-input')?.getAttribute('placeholder')).toBe(message('story.draftPlaceholder'));
    expect(text('#campaign-composer-help')).toBe(message('campaign.view.composerHelp'));
    expect(text('[data-send]')).toBe(message('campaign.view.send'));
    expect((root.querySelector('[data-send]') as HTMLButtonElement).disabled).toBe(true);
    // P09.5 resume card: reviewed Italian labels around the server's Italian location name.
    expect(text('#resume-title')).toBe(message('campaign.resume.title'));
    expect(text('[data-location]')).toBe('Inizio del sentiero di Mosslight');
    expect(root.querySelector('[data-location]')?.getAttribute('lang')).toBe('it');
    expect(text('[data-decision]')).toBe(message('campaign.resume.decisionStart'));
    // P10.5 panels: reviewed Italian headings and clock around the server's own values and pack names.
    expect(text('#panels-title')).toBe(message('campaign.panels.title'));
    expect(text('[data-clock]')).toBe(`${message('campaign.panels.clockLabel')}: ${message('campaign.panels.clock', { day: '1', time: '00:00' })}`);
    expect(text('#conditions-title')).toBe(message('campaign.panels.conditions'));
    expect(text('[data-conditions] p')).toBe(message('campaign.panels.noConditions'));
    expect([...englishLeftovers(root), ...englishText(root)]).toEqual([]);
    expect(accessibilityProblems(root)).toEqual([]);

    // Another client's move commits and a second action stays in flight; the page follows the stream.
    server.advance('before-reload');
    const row = (id: string) => root.querySelector(`[data-operation-id="${id}"]`);
    await until(fixture, () => row(WAIT)?.getAttribute('data-status') === 'ACCEPTED' && cursor() === '0:6' &&
      root.querySelector('.starting-situation') === null, 'stream delivery and state re-read');
    expect(normalize(row(MOVE)!.querySelector('p')!.textContent!)).toBe(
      `${message('campaign.command.move')} · ${message('campaign.status.narrationPending')}`);
    expect(normalize(row(MOVE)!.querySelector('p.muted')!.textContent!)).toBe(message('campaign.narration.unavailable'));
    expect(row(MOVE)!.querySelector('.receipt')!.getAttribute('aria-label')).toBe(message('campaign.view.receiptLabel'));
    // The committed receipt (revision 1, move to grove, no world time, turn advanced) with Italian wording only.
    expect(Array.from(row(MOVE)!.querySelectorAll('.receipt li'), (item) => normalize(item.textContent!))).toEqual([
      message('campaign.receipt.revision', { revision: '1' }), message('campaign.receipt.moved', { destination: 'grove' }),
      message('campaign.receipt.elapsed', { seconds: '0' }), message('campaign.receipt.turnAdvanced')]);
    expect(normalize(row(WAIT)!.querySelector('p')!.textContent!)).toBe(
      `${message('campaign.command.wait')} · ${message('campaign.status.accepted')}`);
    expect(text('[data-connection]')).toBe(message('campaign.view.live'));
    expect(root.textContent).toContain(message('campaign.view.noSituation'));
    expect([...englishLeftovers(root), ...englishText(root)]).toEqual([]);
    expect(accessibilityProblems(root)).toEqual([]);
    expect(server.unrecorded).toEqual([]);
  });

  it('explains real and unknown error codes in Italian without showing the code', /** Recorded 404 and the generic fallback. */ async () => {
    const server = new TranscriptServer();
    server.advance('after-reload');
    configure(server);
    const fixture = TestBed.createComponent<CampaignPlaythrough>(modules.CampaignPlaythrough);
    fixture.componentRef.setInput('adventureId', ADVENTURE);
    fixture.componentRef.setInput('campaignId', FOREIGN_CAMPAIGN);
    const root = fixture.nativeElement as HTMLElement;
    await until(fixture, () => root.querySelector('[data-error]') !== null, 'recorded 404');
    expect(normalize(root.querySelector('[data-error]')!.textContent!)).toBe(message('campaign.error.scopeUnavailable'));
    expect(root.textContent).not.toContain('CAMPAIGN_SCOPE_UNAVAILABLE');
    expect(normalize(root.querySelector('[data-connection]')!.textContent!)).toBe(message('campaign.view.disconnected'));
    expect(englishLeftovers(root)).toEqual([]);
    expect(modules.campaignErrorMessage('NOT_A_REAL_CODE')).toBe(message('campaign.error.generic'));
    expect(modules.campaignErrorMessage('constructor')).toBe(message('campaign.error.generic'));
    expect(server.unrecorded).toEqual([]);

    // A playthrough that the adventure does not list is refused with the Italian notice.
    TestBed.resetTestingModule();
    configure(new TranscriptServer());
    const page = await shell(`/saved/${ADVENTURE}?playthrough=${FOREIGN_CAMPAIGN}`);
    await until(page, () => page.nativeElement.querySelector('[role="alert"]') !== null, 'foreign playthrough');
    expect(normalize(page.nativeElement.querySelector('[role="alert"]').textContent)).toBe(message('saved.unknownPlaythrough'));
    expect(page.nativeElement.querySelector(`[data-campaign-id="${CAMPAIGN}"]`)).not.toBeNull();
  });

  it('formats Italian receipts with the committed numbers and only locale digit grouping', /** Same mechanics as English. */ () => {
    // The simulated server's richer receipt (resources, a check and a draw), read through the real validator.
    const { fixtures } = modules;
    const receipt = modules.parseOperation(fixtures.operation(fixtures.OP1, 'COMMITTED', 5, true), fixtures.CAMPAIGN).receipt as PublicReceipt;
    const italian = modules.receiptLines(receipt, 'it');
    expect(italian).toEqual([
      message('campaign.receipt.revision', { revision: '2' }), message('campaign.receipt.elapsed', { seconds: '1500' }),
      message('campaign.receipt.turnAdvanced'), message('campaign.receipt.resource', { resource: 'vigor', before: '12.000', after: '11.000' }),
      message('campaign.receipt.check', { path: '/effects/0', kind: 'CHECK', outcome: message('campaign.receipt.succeeded') }),
      message('campaign.receipt.draw', { ordinal: '0', value: '17', sides: '20' })]);
    // The English source sentences with English grouping carry the same digit sequence line by line.
    const english = [
      message('campaign.receipt.revision', { revision: '2' }, 'source'), message('campaign.receipt.elapsed', { seconds: '1,500' }, 'source'),
      message('campaign.receipt.turnAdvanced', {}, 'source'),
      message('campaign.receipt.resource', { resource: 'vigor', before: '12,000', after: '11,000' }, 'source'),
      message('campaign.receipt.check', { path: '/effects/0', kind: 'CHECK', outcome: message('campaign.receipt.succeeded', {}, 'source') }, 'source'),
      message('campaign.receipt.draw', { ordinal: '0', value: '17', sides: '20' }, 'source')];
    const digits = (lines: string[]) => lines.map((line) => line.replace(/\D/g, ''));
    expect(digits(italian)).toEqual(digits(english));
    expect(italian.join('\n')).not.toMatch(/Saved as|Moved to|World time|Draw \d/);
    expect(CAMPAIGN).not.toBe(FOREIGN_CAMPAIGN);
  });
});
