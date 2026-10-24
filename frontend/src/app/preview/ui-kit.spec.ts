/** Verify safe presentation primitives and review-route isolation; failed assertions identify contract regressions. */
import { TestBed } from '@angular/core/testing';
import { LOCALE_ID } from '@angular/core';
import { registerLocaleData } from '@angular/common';
import italian from '@angular/common/locales/it';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it } from 'vitest';
import { routes } from '../app.routes';
import { ICONS, UiIcon, UiItem, UiMeter } from './ui-kit';

describe('Graphic kit', /** Register numeric, allowlist and live-label contracts. */ () => {
  it.each([['en-US', '1,234.5 / 2,500'], ['it-IT', '1.234,5 / 2.500']])('formats %s values without changing numeric accessibility data', /** Locale formatting affects live text only; finite protocol values and meter bounds stay numeric. */ (locale, expected) => {
    registerLocaleData(italian);
    TestBed.configureTestingModule({ providers: [{ provide: LOCALE_ID, useValue: locale }] });
    const fixture = TestBed.createComponent(UiMeter);
    fixture.componentRef.setInput('label', 'Resource');
    fixture.componentRef.setInput('value', 1234.5);
    fixture.componentRef.setInput('max', 2500);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.textContent).toContain(expected);
    expect(root.querySelector('[role=meter]')?.getAttribute('aria-valuenow')).toBe('1234.5');
    expect(root.querySelector('[role=meter]')?.getAttribute('aria-valuemax')).toBe('2500');
  });
  it('bounds numeric meters and replaces invalid data with unavailable text', /** Malformed public values cannot yield NaN styles, false zeros or invalid ARIA ranges. */ () => {
    const fixture = TestBed.createComponent(UiMeter);
    fixture.componentRef.setInput('label', 'Health');
    fixture.componentRef.setInput('max', 24);
    fixture.componentRef.setInput('value', 30);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector('[role=meter]')?.getAttribute('aria-valuenow')).toBe('24');
    expect(root.textContent).toContain('24 / 24');
    fixture.componentRef.setInput('value', -2);
    fixture.detectChanges();
    expect(root.querySelector('[role=meter]')?.getAttribute('aria-valuenow')).toBe('0');
    for (const [value, max] of [[NaN, 24], [Infinity, 24], [4, 0], [4, -1], [4, Infinity], [null, 24], [4, null]]) {
      fixture.componentRef.setInput('value', value);
      fixture.componentRef.setInput('max', max);
      fixture.detectChanges();
      expect(root.querySelector('[role=meter]')).toBeNull();
      expect(root.textContent).toContain('Not connected');
      expect(root.querySelector('.meter-fill')).toBeNull();
    }
  });

  it('uses only fixed icon paths including for inherited keys and hostile names', /** Names are untrusted strings and cannot inject SVG, URLs or inherited object members. */ () => {
    const fixture = TestBed.createComponent(UiIcon);
    for (const name of ['story', 'unknown', '__proto__', 'constructor', '<script>alert(1)</script>']) {
      fixture.componentRef.setInput('name', name);
      fixture.detectChanges();
      const root: HTMLElement = fixture.nativeElement;
      expect(root.getAttribute('aria-hidden')).toBe('true');
      expect(root.querySelector('svg')?.getAttribute('focusable')).toBe('false');
      expect(root.querySelector('path')?.getAttribute('d')).toBe(name === 'story' ? ICONS.story : ICONS.rarity);
      expect(root.querySelector('script, image, use')).toBeNull();
    }
  });

  it('keeps item labels literal and separates empty slots from illustrative art', /** Text fallback and rarity remain readable without implying ownership or loading media. */ () => {
    const fixture = TestBed.createComponent(UiItem);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.textContent).toContain('Empty slot');
    expect(root.querySelector('.item-empty')).toBeTruthy();
    fixture.componentRef.setInput('sample', true);
    fixture.componentRef.setInput('label', '<b>Crystal</b>');
    fixture.componentRef.setInput('rarity', 'Uncommon');
    fixture.detectChanges();
    expect(root.querySelector('figcaption')?.textContent).toContain('<b>Crystal</b>');
    expect(root.querySelector('b, img')).toBeNull();
    expect(root.querySelector('.rarity-label')?.textContent).toBe('Uncommon');
    expect(root.querySelector('.item-empty')).toBeNull();
  });

  it('exposes an isolated review route and distributes source/notice links in Settings', /** Review interactions change only local state and never create a connected campaign. */ async () => {
    TestBed.configureTestingModule({ providers: [provideRouter(routes)] });
    const harness = await RouterTestingHarness.create('/settings');
    expect(harness.routeNativeElement!.querySelector('a[href="/ui/LICENSE.txt"]')).toBeTruthy();
    await harness.navigateByUrl('/interface-kit');
    const root = harness.routeNativeElement!;
    expect(root.querySelectorAll('[role=meter]')).toHaveLength(3);
    expect(root.querySelectorAll('.icon-inventory li')).toHaveLength(Object.keys(ICONS).length);
    const toggle = root.querySelector<HTMLButtonElement>('button[aria-pressed]')!;
    toggle.click();
    await harness.fixture.whenStable();
    expect(toggle.getAttribute('aria-pressed')).toBe('true');
    expect(root.querySelector('button[aria-busy=true]')?.hasAttribute('disabled')).toBe(true);
    expect(root.querySelector('input[aria-invalid=true]')?.getAttribute('aria-describedby')).toBe('kit-error-help');
    await harness.navigateByUrl('/adventures/mosslight/sheet');
    expect(harness.routeNativeElement!.querySelector('[role=meter]')).toBeNull();
    expect(harness.routeNativeElement!.textContent).toContain('Not connected');
  });
});
