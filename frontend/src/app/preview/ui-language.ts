/** Validate device language preferences and coordinate explicit compiled-variant reloads; campaign operation/cursor records are verified, never submitted. */
import { DOCUMENT } from '@angular/common';
import { inject, Injectable, InjectionToken, signal } from '@angular/core';
import { DraftStorage, StoryInputState } from './story-input-state';
import { PreviewState } from './preview-state';
import { CampaignSession } from '../campaign/campaign-session';

/** Supported UI variants; protocol identities and user text never pass through translation. */
export type UiLocale = 'en' | 'it';

/** Normalize supported BCP 47 preferences; unsupported or malformed tags return undefined. */
export function uiLocale(value: string): UiLocale | undefined {
  return value.length <= 64 && /^(en|it)(?:-[a-z0-9]{1,8})*$/i.test(value) ? value.slice(0, 2).toLowerCase() as UiLocale : undefined;
}

/** Browser port separates persistence and full-page navigation from deterministic reload checks. */
export interface LanguageBrowser {
  readonly base: string;
  readonly href: string;
  readonly device: DraftStorage | null;
  readonly tab: DraftStorage | null;
  /** Replace the current page with an origin-relative localized URL; browser failures may throw. */
  navigate(path: string): void;
}

/** Resolve browser-only capabilities; storage policy denial is represented by a null port. */
export const LANGUAGE_BROWSER = new InjectionToken<LanguageBrowser>('Language browser', {
  providedIn: 'root', factory: /** Capture this document's trusted base; never derive navigation from persisted URLs. */ () => {
    const document = inject(DOCUMENT);
    let device: Storage | null = null;
    let tab: Storage | null = null;
    try { device = globalThis.localStorage; } catch { /* Denied preferences block reload with a visible notice. */ }
    try { tab = globalThis.sessionStorage; } catch { /* Missing focus/reading persistence must block reload. */ }
    return { base: document.baseURI,
      /** Read the live route at the explicit switch so later Settings query/hash changes survive. */
      get href() { return document.location.href; }, device, tab,
      navigate: /** Explicit same-origin navigation performs Angular's required translation refresh. */ (path) => document.location.assign(path) };
  },
});

/** Own a saved UI preference and safe local reload; failure leaves the current language and drafts available. */
@Injectable({ providedIn: 'root' })
export class UiLanguage {
  private readonly browser = inject(LANGUAGE_BROWSER);
  private readonly reading = inject(PreviewState);
  private readonly input = inject(StoryInputState);
  private readonly campaigns = inject(CampaignSession);
  readonly current: UiLocale;
  readonly available: boolean;
  readonly notice = signal<'none' | 'unsupported' | 'storage' | 'unsafe'>('none');
  readonly preferred = signal<UiLocale>('en');

  /** Read only allowlisted locale values; unsupported saved preferences fall back visibly to English. */
  constructor() {
    const base = new URL(this.browser.base);
    const location = new URL(this.browser.href);
    const match = /^\/(en|it)\/$/.exec(base.pathname);
    this.available = base.origin === location.origin && !!match && location.pathname.startsWith(base.pathname);
    this.current = match?.[1] === 'it' ? 'it' : 'en';
    try {
      if (!this.browser.device) throw new Error('Storage unavailable');
      const raw = this.browser.device.getItem('engine.ui-locale.v1');
      if (raw !== null) {
        const locale = uiLocale(raw);
        this.preferred.set(locale ?? 'en');
        if (!locale) this.notice.set('unsupported');
      }
    } catch { this.notice.set('storage'); }
  }

  /** Consume a one-use focus marker for this compiled locale; storage failures preserve normal route focus. */
  restoreFocus(): boolean {
    try {
      if (this.browser.tab?.getItem('engine.ui-language-focus.v1') !== this.current) return false;
      this.browser.tab.setItem('engine.ui-language-focus.v1', '');
      return true;
    } catch { this.notice.set('storage'); return false; }
  }

  /** Persist a supported preference and reload the equivalent route; return false on invalid, unsafe or unsaved state. */
  change(raw: string): boolean {
    const locale = uiLocale(raw);
    if (!locale || !this.available) return false;
    if (!this.input.prepareReload(/** Real campaigns reload only with a verified operation/cursor record. */ (scope) => this.campaigns.verified(scope))) { this.notice.set('unsafe'); return false; }
    if (!this.reading.prepareReload()) { this.notice.set('storage'); return false; }
    try {
      if (!this.browser.device || !this.browser.tab) throw new Error('Storage unavailable');
      this.browser.tab.setItem('engine.ui-language-focus.v1', locale);
      this.browser.device.setItem('engine.ui-locale.v1', locale);
      if (this.browser.device.getItem('engine.ui-locale.v1') !== locale ||
        this.browser.tab.getItem('engine.ui-language-focus.v1') !== locale) throw new Error('Storage verification failed');
      this.preferred.set(locale);
      this.notice.set('none');
      if (locale === this.current) { this.browser.tab.setItem('engine.ui-language-focus.v1', ''); return true; }
      const location = new URL(this.browser.href);
      // The current trusted locale prefix is replaced; query/hash are opaque and cannot become a redirect origin.
      const path = '/' + locale + location.pathname.slice(3) + location.search + location.hash;
      this.input.invalidateForReload();
      this.browser.navigate(path);
      return true;
    } catch {
      // A failed attempt must not leave a focus request for an unrelated later visit to Settings.
      try { this.browser.tab?.setItem('engine.ui-language-focus.v1', ''); } catch { /* Preserve the visible failure if storage is denied. */ }
      this.notice.set('storage');
      return false;
    }
  }
}
