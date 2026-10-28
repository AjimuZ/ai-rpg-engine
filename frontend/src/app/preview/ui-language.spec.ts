/** Exercise bounded persisted locale/reload behavior; failures expose data loss or unsafe navigation. */
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { PreviewState } from './preview-state';
import { DraftStorage, StoryInputState } from './story-input-state';
import { CAMPAIGN_SYNC_STORAGE } from '../campaign/campaign-session';
import { LANGUAGE_BROWSER, LanguageBrowser, UiLanguage, uiLocale } from './ui-language';

/** In-memory storage port with configurable policy denial; get/set mirror browser failure behavior. */
class MemoryStorage implements DraftStorage {
  readonly values = new Map<string, string>();
  denied = false;
  /** Read exact stored bytes or null; simulate browser denial when requested. */
  getItem(key: string): string | null { if (this.denied) throw new Error('Denied'); return this.values.get(key) ?? null; }
  /** Replace exact stored bytes; simulate quota/policy failure without changing previous values. */
  setItem(key: string, value: string): void { if (this.denied) throw new Error('Denied'); this.values.set(key, value); }
}

/** Create isolated device/tab/draft ports and injectable services; setup failures propagate to the test. */
function setup(options: { base?: string; href?: string; saved?: string } = {}) {
  const device = new MemoryStorage();
  const tab = new MemoryStorage();
  const drafts = new MemoryStorage();
  const sync = new MemoryStorage();
  if (options.saved !== undefined) device.setItem('engine.ui-locale.v1', options.saved);
  const input = new StoryInputState(drafts);
  const reading = new PreviewState(tab);
  const navigate = vi.fn();
  const browser: LanguageBrowser = { base: options.base ?? 'https://example.test/en/',
    href: options.href ?? 'https://example.test/en/settings?from=story#language', device, tab, navigate };
  TestBed.configureTestingModule({ providers: [{ provide: LANGUAGE_BROWSER, useValue: browser },
    { provide: StoryInputState, useValue: input }, { provide: PreviewState, useValue: reading },
    { provide: CAMPAIGN_SYNC_STORAGE, useValue: sync }] });
  return { device, tab, drafts, sync, input, reading, navigate, language: TestBed.inject(UiLanguage) };
}

describe('Safe UI language reload', /** Register persisted-state and callback boundary checks. */ () => {
  it('normalizes supported tags and rejects unsupported or malformed saved preferences visibly', /** Stored values never become URL paths. */ () => {
    expect(uiLocale('IT-it')).toBe('it');
    expect(uiLocale('en-US')).toBe('en');
    for (const value of ['fr', 'en/../../it', 'it--IT', ' en', 'it-' + 'a'.repeat(100)]) expect(uiLocale(value)).toBeUndefined();
    const { language } = setup({ saved: '//untrusted.test/it' });
    expect(language.preferred()).toBe('en');
    expect(language.notice()).toBe('unsupported');
  });

  it('preserves two scopes, reading preferences, literal draft and selection while invalidating callbacks', /** Reload never advances reading or submits a command. */ () => {
    const { input, reading, language, navigate, tab, drafts, device } = setup();
    reading.advance('mosslight');
    reading.setTextSize(23);
    reading.setReadingLayout('text-focused');
    let epoch = input.activate({ adventureId: 'relay', campaignId: 'local-preview' });
    input.edit('Separate draft', 2, 4);
    epoch = input.activate({ adventureId: 'mosslight', campaignId: 'local-preview' });
    const projection = { adventureId: 'mosslight', campaignId: 'local-preview', revision: 0, presentationRevision: 1,
      suggestions: [{ id: 'ask', kind: 'question' as const, text: 'Old English reply' }] };
    input.accept(projection, epoch);
    input.setStatus('preview', epoch);
    const reply = input.suggestions()[0]!;
    input.edit('My <b>parole</b> 🌱', 3, 9);
    expect(language.change('it-IT')).toBe(true);
    expect(navigate).toHaveBeenCalledExactlyOnceWith('/it/settings?from=story#language');
    expect(device.getItem('engine.ui-locale.v1')).toBe('it');
    expect(tab.getItem('engine.ui-language-focus.v1')).toBe('it');
    expect(input.accept({ ...projection, revision: 100 }, epoch)).toBe(false);
    input.setStatus('error', epoch);
    expect(input.status()).toBe('loading');
    expect(input.choose(reply)).toBe(false);
    const restoredReading = new PreviewState(tab);
    expect([restoredReading.position('mosslight'), restoredReading.position('relay'), restoredReading.textSize(), restoredReading.readingLayout()])
      .toEqual([1, 0, 23, 'text-focused']);
    const restored = new StoryInputState(drafts);
    restored.activate({ adventureId: 'mosslight', campaignId: 'local-preview' });
    expect(restored.draft()).toEqual({ text: 'My <b>parole</b> 🌱', start: 3, end: 9 });
    restored.activate({ adventureId: 'relay', campaignId: 'local-preview' });
    expect(restored.draft()).toEqual({ text: 'Separate draft', start: 2, end: 4 });
  });

  it('blocks unresolved recovery even after detachment and refuses unverified campaign contexts', /** Settings navigation cannot erase a reconciliation obligation. */ () => {
    const { input, language, navigate, sync } = setup();
    let epoch = input.activate({ adventureId: 'mosslight', campaignId: 'local-preview' });
    input.setStatus('narration-pending', epoch);
    input.deactivate(epoch);
    expect(language.change('it')).toBe(false);
    expect(language.notice()).toBe('unsafe');
    epoch = input.activate({ adventureId: 'mosslight', campaignId: 'local-preview' });
    input.setStatus('preview', epoch);
    expect(input.prepareReload()).toBe(true);
    epoch = input.activate({ adventureId: 'mosslight', campaignId: 'real-campaign' });
    input.setStatus('ready', epoch);
    input.deactivate(epoch);
    // Without a verifiable operation/cursor record the real campaign still blocks, even after detachment.
    sync.denied = true;
    expect(language.change('it')).toBe(false);
    expect(language.notice()).toBe('unsafe');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('reloads a real campaign context only after its operation and cursor record verifies', /** Reconciliation replaces the blanket refusal. */ () => {
    const { input, language, navigate, sync, drafts } = setup();
    const scope = { adventureId: 'mosslight', campaignId: 'real-campaign' };
    const epoch = input.activate(scope);
    input.setStatus('narration-pending', epoch);
    input.edit('Open the shutters', 5, 8);
    expect(language.change('it')).toBe(true);
    expect(navigate).toHaveBeenCalledWith('/it/settings?from=story#language');
    // The draft bytes and the record survive; no operation or draft was submitted anywhere.
    expect(drafts.values.get('engine.story-draft.v1:["mosslight","real-campaign"]')).toContain('Open the shutters');
    expect([...sync.values.keys()]).toEqual(['engine.campaign-sync.v1:["mosslight","real-campaign"]']);
    // A required campaign restore blocks regardless of the record; reload invalidated the old callback epoch.
    input.setStatus('restore-required', input.activate(scope));
    navigate.mockClear();
    expect(language.change('it')).toBe(false);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('keeps drafts in memory when persistence fails and refuses conflicting later device writes', /** A reload cannot discard unsaved work in another detached scope. */ () => {
    const { input, language, drafts, navigate } = setup();
    const epoch = input.activate({ adventureId: 'relay', campaignId: 'local-preview' });
    drafts.denied = true;
    input.edit('Keep this text', 1, 4);
    input.deactivate(epoch);
    expect(language.change('it')).toBe(false);
    expect(input.draft().text).toBe('Keep this text');
    drafts.denied = false;
    input.activate({ adventureId: 'relay', campaignId: 'local-preview' });
    input.edit('Saved text');
    const key = [...drafts.values.keys()][0]!;
    const restored = new StoryInputState(drafts);
    const restoredEpoch = restored.activate({ adventureId: 'relay', campaignId: 'local-preview' });
    restored.deactivate(restoredEpoch);
    drafts.setItem(key, 'Another tab changed this draft');
    expect(restored.prepareReload()).toBe(false);
    expect(language.change('it')).toBe(false);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('blocks tab/device denial and failed navigation with translated-notice keys', /** Storage failures do not clear work or masquerade as a successful reload. */ () => {
    const { language, device, tab, navigate } = setup();
    tab.denied = true;
    expect(language.change('it')).toBe(false);
    tab.denied = false;
    device.denied = true;
    expect(language.change('it')).toBe(false);
    expect(tab.getItem('engine.ui-language-focus.v1')).toBe('');
    device.denied = false;
    navigate.mockImplementation(/** Simulate a browser navigation policy error. */ () => { throw new Error('Navigation denied'); });
    expect(language.change('it')).toBe(false);
    expect(language.notice()).toBe('storage');
  });

  it('consumes equivalent-control focus once and saves same-locale preferences without reloading', /** Repeated Settings visits must not steal focus. */ () => {
    const { language, tab, device, navigate } = setup({ saved: 'it-IT' });
    expect(language.preferred()).toBe('it');
    tab.setItem('engine.ui-language-focus.v1', 'it');
    expect(language.restoreFocus()).toBe(false);
    tab.setItem('engine.ui-language-focus.v1', 'en');
    expect(language.restoreFocus()).toBe(true);
    expect(language.restoreFocus()).toBe(false);
    expect(language.change('en')).toBe(true);
    expect(device.getItem('engine.ui-locale.v1')).toBe('en');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('does not offer reloads from an unlocalized or mismatched-origin base', /** Only the two compiled same-origin routes are navigable. */ () => {
    const { language, navigate } = setup({ base: 'https://other.test/it/' });
    expect(language.available).toBe(false);
    expect(language.change('it')).toBe(false);
    expect(language.change('//other.test/')).toBe(false);
    expect(navigate).not.toHaveBeenCalled();
  });
});

describe('Persisted preview reading', /** Register untrusted storage parsing checks. */ () => {
  it('reports denied storage separately from malformed saved state', /** Denial retains in-memory preferences and blocks reload. */ () => {
    const tab = new MemoryStorage();
    tab.denied = true;
    const state = new PreviewState(tab);
    expect(state.persistence()).toBe('unavailable');
    state.setTextSize(22);
    expect(state.textSize()).toBe(22);
    expect(state.prepareReload()).toBe(false);
  });
  it('rejects malformed and unknown passage/size/layout values without replacing stored bytes', /** Invalid snapshots use defaults and disclose invalid persistence. */ () => {
    const valid = { version: 1, size: 18, layout: 'visual-novel', positions: { mosslight: 1 } };
    for (const raw of ['{', 'x'.repeat(2001), JSON.stringify({ ...valid, version: 2 }), JSON.stringify({ ...valid, size: 25 }),
      JSON.stringify({ ...valid, layout: 'injected' }), JSON.stringify({ ...valid, positions: { missing: 0 } }),
      JSON.stringify({ ...valid, positions: { relay: 2 } }), JSON.stringify({ ...valid, positions: { relay: -1 } })]) {
      const tab = new MemoryStorage();
      tab.setItem('engine.preview-reading.v1', raw);
      const state = new PreviewState(tab);
      expect(state.persistence()).toBe('invalid');
      expect([state.position('mosslight'), state.textSize()]).toEqual([0, 18]);
      expect(tab.getItem('engine.preview-reading.v1')).toBe(raw);
    }
  });
});
