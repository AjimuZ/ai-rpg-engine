/** Exercise routed previews and native forms; failures identify broken user journeys. */
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';
import { routes } from './app.routes';

/** Return a routed screen for a URL; routing/bootstrap errors reject the promise. */
async function open(url: string) {
  TestBed.configureTestingModule({ providers: [provideRouter(routes)] });
  return RouterTestingHarness.create(url);
}

describe('Preview screens', /** Register user-facing route checks; assertions fail normally. */ () => {
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
    expect(root.querySelector<HTMLButtonElement>('.composer button')?.disabled).toBe(true);
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
