/**
 * The music panel (P17.2-B): explicit opt-in, current-track credits and the searchable credits list, rendered against a
 * fake audio element. jsdom only: no sound, no real host and no screen-reader or layout check.
 */
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { MUSIC_AUDIO, MUSIC_STORAGE, MUSIC_TICK, MusicAudio } from './music-player';
import { MusicPanel } from './music-panel';

/** Let promise callbacks of a started track run. */
const flush = (): Promise<void> => new Promise((done) => setTimeout(done, 0));

/** Render the panel with a recording audio factory. */
function render(mood: 'exploration' | 'tension'): { root: HTMLElement; urls: string[]; refresh: () => Promise<void> } {
  const urls: string[] = [];
  TestBed.configureTestingModule({ providers: [
    { provide: MUSIC_AUDIO, useValue: (url: string): MusicAudio => {
      urls.push(url);
      return { loop: false, volume: 1, onerror: null, play: () => Promise.resolve(), pause: () => undefined };
    } },
    { provide: MUSIC_TICK, useValue: () => () => undefined },
    { provide: MUSIC_STORAGE, useValue: null },
  ] });
  const fixture = TestBed.createComponent(MusicPanel);
  fixture.componentRef.setInput('scope', '["adventure","campaign"]');
  fixture.componentRef.setInput('mood', mood);
  fixture.detectChanges();
  const root = fixture.nativeElement as HTMLElement;
  return { root, urls, refresh: async () => { await flush(); fixture.detectChanges(); } };
}

describe('music panel', /** Register the rendered behavior. */ () => {
  it('offers music off, loads nothing and shows no track until the player clicks', async () => {
    const { root, urls, refresh } = render('exploration');
    await refresh();
    expect(urls).toHaveLength(0);
    expect(root.querySelector('[data-music-status]')?.textContent).toContain('Music is off.');
    expect(root.querySelector('[data-music-track]')).toBeNull();
    (root.querySelector('[data-music-on]') as HTMLButtonElement).click();
    await refresh();
    expect(urls).toHaveLength(1);
    expect(root.querySelector('[data-music-title]')?.textContent).toBe('Wholesome');
    const license = root.querySelector('[data-music-license]') as HTMLAnchorElement;
    expect(license.href).toBe('https://creativecommons.org/licenses/by/4.0/');
    expect(root.querySelector('[data-music-pause]')).not.toBeNull();
    // The current track shows its complete credit, and the visit's credits and the separate effects volume appear.
    expect(root.querySelector('[data-music-notices]')?.textContent).toContain('Attribution required');
    expect(root.querySelector('[data-music-modifications]')?.textContent).toContain('None');
    expect(root.querySelector('[data-music-verified]')?.textContent).toBe('2026-10-23');
    expect((root.querySelector('[data-music-used-credits]') as HTMLTextAreaElement).value).toContain('"Wholesome" — Kevin MacLeod');
    expect(root.querySelector('[data-music-effects-volume]')).not.toBeNull();
    (root.querySelector('[data-music-off]') as HTMLButtonElement).click();
    await refresh();
    expect(root.querySelector('[data-music-track]')).toBeNull();
    expect(root.querySelector('[data-music-status]')?.textContent).toContain('Music is off.');
    // Turning music off silences it but keeps the credits of what already played in this visit.
    expect(root.querySelector('[data-music-used-credits]')).not.toBeNull();
  });

  it('lists complete credits for every reviewed track and filters them', async () => {
    const { root, refresh } = render('exploration');
    await refresh();
    expect(root.querySelectorAll('[data-music-credit]')).toHaveLength(2);
    const filter = root.querySelector('[data-music-filter]') as HTMLInputElement;
    filter.value = 'attribution 3.0';
    filter.dispatchEvent(new Event('input'));
    await refresh();
    const items = [...root.querySelectorAll('[data-music-credit]')];
    expect(items.map((item) => item.getAttribute('data-music-credit'))).toEqual(['darkest-child']);
    expect(items[0].textContent).toContain('Kevin MacLeod');
    expect(items[0].textContent).toContain('Creative Commons Attribution 3.0 Unported');
    filter.value = 'no such credit';
    filter.dispatchEvent(new Event('input'));
    await refresh();
    expect(root.querySelector('[data-music-none]')).not.toBeNull();
  });
});
