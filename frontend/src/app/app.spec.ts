/** Exercise routed previews and native forms; failures identify broken user journeys. */
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it, vi } from 'vitest';
import { routes } from './app.routes';
import { PreviewState } from './preview/preview-state';

/** Return a routed screen for a URL; routing/bootstrap errors reject the promise. */
async function open(url: string) {
  TestBed.configureTestingModule({ providers: [provideRouter(routes)] });
  return RouterTestingHarness.create(url);
}

describe('Preview screens', /** Register user-facing route checks; assertions fail normally. */ () => {
  it('switches layouts without losing reading position and omits all artwork in text mode', /** Preference changes affect rendering only and reject unsupported selections. */ async () => {
    const harness = await open('/adventures/mosslight/story');
    let root = harness.routeNativeElement!;
    expect(root.querySelector('.story.text-focused')).toBeNull();
    root.querySelector<HTMLButtonElement>('.reading-controls button')!.click();
    await harness.fixture.whenStable();
    expect(root.querySelector('.portrait')).toBeTruthy();
    await harness.navigateByUrl('/settings');
    const select = harness.routeNativeElement!.querySelector<HTMLSelectElement>('#reading-layout')!;
    select.value = 'text-focused';
    select.dispatchEvent(new Event('change', { bubbles: true }));
    await harness.fixture.whenStable();
    TestBed.inject(PreviewState).setReadingLayout('unsupported');
    await harness.navigateByUrl('/adventures/mosslight/story');
    root = harness.routeNativeElement!;
    expect(root.querySelector('.story.text-focused')).toBeTruthy();
    expect(root.querySelector('img')).toBeNull();
    expect(root.querySelector('.scene-description')?.textContent).toContain('winding trail');
    expect(root.querySelector('.speaker')?.textContent).toContain('Trail keeper');
    expect(root.querySelectorAll('.chronicle li')).toHaveLength(2);
    TestBed.inject(PreviewState).setReadingLayout('visual-novel');
    await harness.fixture.whenStable();
    expect(root.querySelector('.portrait')).toBeTruthy();
  });

  it('keeps attribution after portrait failure and replaces scoped identities', /** Missing media cannot hide text or leak another adventure's speaker. */ async () => {
    const harness = await open('/adventures/mosslight/story');
    const root = harness.routeNativeElement!;
    root.querySelector<HTMLButtonElement>('.reading-controls button')!.click();
    await harness.fixture.whenStable();
    expect(root.querySelector('.portrait')?.getAttribute('src')).toBe('/portraits/keeper.svg');
    root.querySelector('.portrait')!.dispatchEvent(new Event('error'));
    await harness.fixture.whenStable();
    expect(root.querySelector('.portrait-fallback')).toBeTruthy();
    expect(root.querySelector('.speaker')?.textContent).toContain('Trail keeper');
    await harness.navigateByUrl('/adventures/relay/story');
    expect(root.querySelector('.portrait')).toBeNull();
    root.querySelector<HTMLButtonElement>('.reading-controls button')!.click();
    await harness.fixture.whenStable();
    expect(root.querySelector('.portrait')?.getAttribute('src')).toBe('/portraits/attendant.svg');
    expect(root.querySelector('.speaker')?.textContent).not.toContain('Trail keeper');
  });

  it('handles absent portraits, unknown labels and empty supplied passages', /** Public labels are literal and no fallback invents player words or negative positions. */ async () => {
    const harness = await open('/library');
    const state = TestBed.inject(PreviewState);
    const original = state.find('mosslight')!;
    const find = vi.spyOn(state, 'find').mockReturnValue({ ...original, speakers: {}, passages: [
      { speaker: 'character', speakerId: 'unknown', name: ' ', text: '<b>Public words</b>' },
      { speaker: 'player', name: 'Ignored label', text: 'Explicit submitted words.' },
    ] });
    await harness.navigateByUrl('/adventures/mosslight/story');
    const root = harness.routeNativeElement!;
    expect(root.querySelector('.speaker')?.textContent).toContain('Unknown character');
    expect(root.querySelector('.portrait-fallback')).toBeTruthy();
    expect(root.querySelector('.passage b')).toBeNull();
    expect(root.querySelector('.chronicle')?.textContent).not.toContain('Explicit submitted words.');
    root.querySelector<HTMLButtonElement>('.reading-controls button')!.click();
    await harness.fixture.whenStable();
    expect(root.querySelector('.speaker')?.textContent).toContain('You');
    expect(root.querySelector('.speaker')?.textContent).toContain('Player');
    expect(root.querySelector('.portrait-frame')).toBeNull();
    find.mockReturnValue({ ...original, passages: [] });
    await harness.navigateByUrl('/library');
    await harness.navigateByUrl('/adventures/relay/story');
    state.advance('relay');
    expect(state.position('relay')).toBe(0);
    expect(harness.routeNativeElement!.textContent).toContain('No passages are available.');
    expect(harness.routeNativeElement!.querySelector('.reading-controls')).toBeNull();
  });

  it('keeps content with no optional modules or definitions usable', /** Empty public metadata must not invent resource meters or optional panels. */ async () => {
    const harness = await open('/library');
    const state = TestBed.inject(PreviewState);
    const original = state.find('mosslight')!;
    vi.spyOn(state, 'find').mockReturnValue({ ...original, content: { attributes: [], resources: [] } });
    await harness.navigateByUrl('/adventures/mosslight/sheet');
    const root = harness.routeNativeElement!;
    expect(root.querySelector('h1')?.textContent).toBe('Character');
    expect(root.textContent).toContain('No resource definitions');
    expect(root.querySelector('[data-resource]')).toBeNull();
    expect(root.querySelector('a[href$="/guide"], a[href$="/creative"]')).toBeNull();
    await harness.navigateByUrl('/adventures/mosslight/guide');
    expect(root.querySelector('h1')?.textContent).toBe('Panel unavailable');
    await harness.navigateByUrl('/adventures/mosslight/creative');
    expect(root.querySelector('h1')?.textContent).toBe('Panel unavailable');
  });

  it('uses neutral fallbacks and renders metadata as literal text', /** Missing names and markup cannot create empty headings or executable DOM. */ async () => {
    const harness = await open('/library');
    const state = TestBed.inject(PreviewState);
    const original = state.find('relay')!;
    vi.spyOn(state, 'find').mockReturnValue({ ...original, title: '<em>Relay</em>', content: {
      terms: { sheet: '  ', inventory: '<b>Cargo</b>' }, attributes: [],
      resources: [{ id: 'reserve', name: ' ' }, { id: 'second', name: '<img src=x>' }],
      modules: { guide: { title: ' ', description: '<script>untrusted()</script>' },
        creativeAbilities: { description: 'Public text only.' } },
    } });
    await harness.navigateByUrl('/adventures/relay/sheet');
    const root = harness.routeNativeElement!;
    expect(root.querySelector('h1')?.textContent).toBe('Character');
    expect(root.textContent).toContain('reserve');
    expect(root.textContent).toContain('<img src=x>');
    expect(root.textContent).toContain('No attribute definitions');
    expect(root.querySelector('nav')?.textContent).toContain('<b>Cargo</b>');
    expect(root.querySelector('b, em, img, script')).toBeNull();
    await harness.navigateByUrl('/adventures/relay/guide');
    expect(root.querySelector('h1')?.textContent).toBe('Guide');
    expect(root.textContent).toContain('<script>untrusted()</script>');
    expect(root.querySelector('script')).toBeNull();
    await harness.navigateByUrl('/adventures/relay/creative');
    expect(root.querySelector('h1')?.textContent).toBe('Creative abilities');
  });

  it('binds terminology and resource definitions to the scoped content', /** Reused screens must replace labels without inventing balances. */ async () => {
    const harness = await open('/adventures/mosslight/sheet');
    const root = harness.routeNativeElement!;
    expect(root.textContent).toContain('Awareness');
    expect(root.textContent).toContain('Vigor');
    expect(root.querySelector('nav')?.textContent).toContain('Trail guide');
    expect(root.querySelector('nav')?.textContent).not.toContain('Field techniques');
    await harness.navigateByUrl('/adventures/relay/sheet');
    expect(root.querySelector('h1')?.textContent).toBe('Crew profile');
    expect(root.textContent).toContain('Logic');
    expect(root.textContent).toContain('Charge');
    expect(root.textContent).not.toContain('Vigor');
    expect(root.textContent).not.toContain('Awareness');
    expect(root.querySelector('nav')?.textContent).not.toContain('Trail guide');
    expect(root.querySelector('nav')?.textContent).toContain('Field techniques');
    expect(root.querySelector('[data-resource="charge"]')?.textContent).toContain('Not connected');
    await harness.navigateByUrl('/adventures/relay/inventory');
    expect(root.querySelector('h1')?.textContent).toBe('Cargo');
    await harness.navigateByUrl('/adventures/relay/journal');
    expect(root.querySelector('h1')?.textContent).toBe('Mission log');
    await harness.navigateByUrl('/adventures/relay/create');
    expect(root.querySelector('label')?.textContent).toBe('Specialist name');
  });

  it('renders configured optional panels and refuses disabled direct routes', /** Visibility is a metadata capability, including after route reuse. */ async () => {
    const harness = await open('/adventures/mosslight/guide');
    const root = harness.routeNativeElement!;
    expect(root.querySelector('h1')?.textContent).toBe('Trail guide');
    await harness.navigateByUrl('/adventures/relay/guide');
    expect(root.querySelector('h1')?.textContent).toBe('Panel unavailable');
    expect(root.textContent).not.toContain('Trail guide');
    await harness.navigateByUrl('/adventures/relay/creative');
    expect(root.querySelector('h1')?.textContent).toBe('Field techniques');
    expect(root.querySelector('button')?.disabled).toBe(true);
    await harness.navigateByUrl('/adventures/mosslight/creative');
    expect(root.querySelector('h1')?.textContent).toBe('Panel unavailable');
  });

  it('lists adventure workspaces separately from installed packs', /** Require both neutral previews and explicit scope. */ async () => {
    const harness = await open('/library');
    const text = harness.routeNativeElement?.textContent;
    expect(text).toContain('Mosslight Trail');
    expect(text).toContain('Silent Relay');
    expect(text).toContain('Installed packs');
    expect(text).toContain('Preview');
  });

  it('rejects blank names and previews a valid character without saving', /** Submit native forms; invalid input must not produce a preview. */ async () => {
    const harness = await open('/adventures/mosslight/create');
    const root = harness.routeNativeElement!;
    const input = root.querySelector<HTMLInputElement>('#character-name')!;
    input.value = '   ';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await harness.fixture.whenStable();
    root.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await harness.fixture.whenStable();
    expect(root.querySelector('[role="status"]')).toBeNull();
    expect(root.textContent).toContain('Enter a name');
    input.value = 'River';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await harness.fixture.whenStable();
    root.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await harness.fixture.whenStable();
    expect(root.querySelector('[role="status"]')?.textContent).toContain('River');
    expect(root.textContent).toContain('No campaign has been created');
  });

  it('opens every scoped feature and handles unknown adventures', /** Unknown identity must not select a default. */ async () => {
    const harness = await open('/library');
    for (const id of ['mosslight', 'relay']) {
      for (const page of ['story', 'sheet', 'inventory', 'journal', 'create']) {
        await harness.navigateByUrl(`/adventures/${id}/${page}`);
        expect(harness.routeNativeElement?.querySelector('h1')).toBeTruthy();
      }
    }
    await harness.navigateByUrl('/adventures/missing/story');
    expect(harness.routeNativeElement?.textContent).toContain('Adventure unavailable');
    await harness.navigateByUrl('/settings');
    expect(harness.routeNativeElement?.textContent).toContain('Reading');
  });

  it('attributes passages and reveals only supplied text', /** Continue is bounded and never enables Send or reveals future text in history. */ async () => {
    const harness = await open('/adventures/mosslight/story');
    const root = harness.routeNativeElement!;
    expect(root.querySelector('.speaker')?.textContent).toContain('Narrator');
    expect(root.querySelector('.chronicle')?.textContent).not.toContain('Trail keeper');
    root.querySelector<HTMLButtonElement>('.reading-controls button')!.click();
    await harness.fixture.whenStable();
    expect(root.querySelector('.speaker')?.textContent).toContain('Trail keeper');
    expect(root.querySelector('.speaker')?.textContent).toContain('Character');
    expect(root.querySelector<HTMLButtonElement>('.reading-controls button')?.disabled).toBe(true);
    expect(root.querySelector<HTMLButtonElement>('.composer button[data-send]')?.disabled).toBe(true);
    await harness.navigateByUrl('/adventures/relay/story');
    expect(root.querySelector('.speaker')?.textContent).toContain('Narrator');
    expect(root.querySelector('.chronicle')?.textContent).not.toContain('Trail keeper');
  });

  it('escapes character names and clears the form when adventure scope changes', /** Markup is literal text and a reused route cannot leak the previous name. */ async () => {
    const harness = await open('/adventures/mosslight/create');
    const root = harness.routeNativeElement!;
    const input = root.querySelector<HTMLInputElement>('#character-name')!;
    input.value = '<b>River</b>';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await harness.fixture.whenStable();
    root.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await harness.fixture.whenStable();
    expect(root.querySelector('[role="status"]')?.textContent).toContain('<b>River</b>');
    expect(root.querySelector('[role="status"] b')).toBeNull();
    await harness.navigateByUrl('/adventures/relay/create');
    expect(root.querySelector<HTMLInputElement>('#character-name')?.value).toBe('');
    expect(root.querySelector('[role="status"]')).toBeNull();
    await harness.navigateByUrl('/adventures/mosslight/create');
    expect(root.querySelector<HTMLInputElement>('#character-name')?.value).toBe('');
    expect(root.querySelector('[role="status"]')).toBeNull();
    expect(root.querySelector('[role="alert"]')).toBeNull();
  });

  it('validates trimmed name boundaries consistently with the service', /** Accept padding but reject an actual overlong name at both form and summary boundaries. */ async () => {
    const harness = await open('/adventures/relay/create');
    const root = harness.routeNativeElement!;
    const input = root.querySelector<HTMLInputElement>('#character-name')!;
    for (const length of [60, 61]) {
      input.value = `  ${'x'.repeat(length)}  `;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await harness.fixture.whenStable();
      root.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      await harness.fixture.whenStable();
      expect(!!root.querySelector('[role="status"]')).toBe(length === 60);
      expect(!!root.querySelector('[role="alert"]')).toBe(length === 61);
    }
  });

  it('applies reading settings across navigation and rejects a tampered option', /** Native selection updates shared presentation state without a campaign connection. */ async () => {
    const harness = await open('/settings');
    const select = harness.routeNativeElement!.querySelector<HTMLSelectElement>('#text-size')!;
    select.value = '24';
    select.dispatchEvent(new Event('change', { bubbles: true }));
    await harness.fixture.whenStable();
    const option = document.createElement('option');
    option.value = '99';
    select.append(option);
    select.value = '99';
    select.dispatchEvent(new Event('change', { bubbles: true }));
    await harness.fixture.whenStable();
    await harness.navigateByUrl('/adventures/relay/story');
    expect(harness.routeNativeElement!.querySelector<HTMLElement>('.story')!.style.getPropertyValue('--reading-size')).toBe('24px');
  });

  it('falls back to public scene text when an image fails', /** A failed asset leaves the passage and controls readable. */ async () => {
    const harness = await open('/adventures/mosslight/story');
    const root = harness.routeNativeElement!;
    root.querySelector('.scene img')!.dispatchEvent(new Event('error'));
    await harness.fixture.whenStable();
    expect(root.querySelector('.scene-fallback')?.textContent).toContain('winding trail');
    expect(root.querySelector('.passage')?.textContent).toContain('quiet grove');
    await harness.navigateByUrl('/adventures/relay/story');
    expect(root.querySelector('.scene img')).toBeTruthy();
  });
});
