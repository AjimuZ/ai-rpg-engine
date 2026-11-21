/** Keyboard contract of the shell's skip link: it moves focus and never changes the current screen. */
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { App } from './app';

describe('Application shell', /** Register shell checks; assertions fail normally. */ () => {
  it('focuses the main content from the skip link without following its anchor', /** The anchor must be cancelled because the document base would turn "#main" into a navigation away from the screen. */ async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const root = fixture.nativeElement as HTMLElement;
    const click = new MouseEvent('click', { bubbles: true, cancelable: true });
    root.querySelector<HTMLAnchorElement>('.skip-link')!.dispatchEvent(click);
    expect(click.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(root.querySelector('main#main'));
  });
});
