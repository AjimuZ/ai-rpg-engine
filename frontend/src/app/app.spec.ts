/** Verify the rendered, zoneless shell without network access or adventure fixtures. */
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { App } from './app';

describe('Application shell', /** Register shell checks; assertion failures fail the suite. */ () => {
  it('renders a main landmark and neutral startup message', /** Render without inputs; fail on inaccessible or missing content. */ async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const main: HTMLElement | null = fixture.nativeElement.querySelector('main');
    expect(main?.querySelector('h1')?.textContent).toBe('Adventure engine');
    expect(main?.textContent).toContain('The application shell is ready.');
  });
});
