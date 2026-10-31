/**
 * Settings → Models panel checks (P21.3-D): slot rendering, class badges, probe results, fallback
 * editing, the held class change and its acknowledgement, failure sentences, and keyboard-reachable
 * native controls. A simulated owner server replaces `fetch`.
 *
 * What this does not establish: it is jsdom, so focus order, focus rings and real key handling are
 * not exercised; the browser journey recorded in the roadmap covers those.
 */
import { formatDate } from '@angular/common';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { LIBRARY_FETCH } from '../library/saved-library-client';
import { AT, ModelsServer } from './models-fixtures';
import { ModelsPanel } from './models-panel';

const WHEN = normalize(formatDate(AT, 'medium', 'en-US'));

/** Collapse whitespace runs so template indentation does not affect comparisons. */
function normalize(value: string | null | undefined): string {
  return (value ?? '').replace(/\s+/g, ' ').trim();
}

/** Wait in real time until a predicate holds; the bounded loop fails a stuck screen. */
async function until(fixture: ComponentFixture<unknown>, ready: () => boolean, label: string): Promise<void> {
  for (let attempt = 0; attempt < 100 && !ready(); attempt++) {
    await new Promise<void>((accept) => setTimeout(accept, 0));
    fixture.detectChanges();
    await fixture.whenStable();
  }
  expect(ready(), label).toBe(true);
}

/** Type into a native text input the way a keyboard user does: set the value, then raise `input`. */
function type(input: Element | null, value: string): void {
  const field = input as HTMLInputElement;
  field.value = value;
  field.dispatchEvent(new Event('input', { bubbles: true }));
}

/** Choose an option of a native select and raise `change`. */
function choose(select: Element | null, value: string): void {
  const field = select as HTMLSelectElement;
  field.value = value;
  field.dispatchEvent(new Event('change', { bubbles: true }));
}

describe('Models panel', /** Register panel checks. */ () => {
  let server: ModelsServer;
  let fixture: ComponentFixture<ModelsPanel>;
  let root: HTMLElement;

  /** The element of one slot. */
  const slot = (id: string): HTMLElement => root.querySelector(`[data-slot="${id}"]`) as HTMLElement;
  /** Normalized text of the first match inside one slot. */
  const text = (id: string, selector: string): string => normalize(slot(id).querySelector(selector)?.textContent);
  /** Normalized text of every match inside one slot, joined by spaces as a reader announces separate items. */
  const items = (id: string, selector: string): string => Array.from(slot(id).querySelectorAll(selector), (item) => normalize(item.textContent)).join(' ');
  /** A form control of a slot by its field suffix, for example `light` + `model`. */
  const field = (id: string, name: string): HTMLElement => slot(id).querySelector(`#model-${id.replace('.', '-')}-${name}`) as HTMLElement;
  /** Let pending requests settle and render. */
  const settle = async (ready: () => boolean, label: string): Promise<void> => until(fixture, ready, label);

  beforeEach(/** Each test mounts the panel against a fresh simulated server. */ async () => {
    server = new ModelsServer();
    TestBed.configureTestingModule({ providers: [{ provide: LIBRARY_FETCH, useValue: server.fetch }] });
    fixture = TestBed.createComponent(ModelsPanel);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
    await settle(() => root.querySelectorAll('[data-slot]').length === 4, 'four slots');
  });

  afterEach(/** Destroying the fixture aborts requests still in flight. */ () => TestBed.resetTestingModule());

  it('lists the four slots with badges, fallbacks and probe results', /** Reading the status. */ () => {
    expect(root.querySelector('#models-title')?.textContent).toBe('Models');
    expect(Array.from(root.querySelectorAll('[data-slot] h3'), (heading) => heading.textContent)).toEqual(['Light text', 'Standard text', 'Deliberate text', 'Images']);

    expect(slot('text.light').querySelector('[data-class]')?.getAttribute('data-class-id')).toBe('local');
    expect(items('text.light', '[data-badge] li')).toBe('Free; uses your own hardware Prompts stay on this machine');
    expect(text('text.light', '[data-probe]')).toBe(`Ready · Checked ${WHEN} in 42 ms.`);
    expect(text('text.light', '[data-source]')).toBe('Saved in settings');

    expect(slot('text.standard').querySelector('[data-class]')?.getAttribute('data-class-id')).toBe('subscription');
    expect(items('text.standard', '[data-badge] li')).toBe('Counts against your plan limits Prompts leave this machine for the runtime vendor');
    expect(text('text.standard', '[data-fallbacks]')).toBe('claude · model-two · low');
    expect(text('text.standard', '[data-acknowledged]')).toBe(`You acknowledged the class Subscription on ${WHEN}.`);
    expect(text('text.standard', '[data-probe]')).toBe(`Not ready · This model is not on your approved list. Checked ${WHEN} in 7 ms.`);

    // An unassigned slot shows no class, no probe and the empty form, never a made-up default model.
    expect(text('text.deliberate', '[data-source]')).toBe('Not assigned');
    expect(slot('text.deliberate').querySelector('[data-class], [data-probe], [data-probe-button]')).toBeNull();
    expect((field('text.deliberate', 'model') as HTMLInputElement).value).toBe('');
    expect(Array.from(field('text.deliberate', 'backend').querySelectorAll('option'), (option) => option.value)).toEqual(['codex', 'claude', 'local', 'openai-compatible', 'anthropic-api']);
    expect(Array.from(field('image', 'backend').querySelectorAll('option'), (option) => option.value)).toEqual(['local-image', 'a1111-http', 'openai-images']);
    expect(field('image', 'effort')).toBeNull();
  });

  it('asks for names only: no key, address or secret field exists', /** The API publishes identifiers only. */ () => {
    expect(root.querySelector('input[type="password"], input[type="url"], input[type="file"], textarea')).toBeNull();
    const inputs = Array.from(root.querySelectorAll('input'), (input) => input.id.replace(/^model-[a-z]+(-[a-z]+)?-/, ''));
    expect(inputs.every((name) => /^(model|effort|fb-\d-model|fb-\d-effort)$/.test(name)), inputs.join()).toBe(true);
  });

  it('saves an edited assignment, sends only identifiers and shows the stored slot', /** The ordinary save. */ async () => {
    type(field('text.light', 'model'), 'gemma-4-large');
    expect(text('text.light', '[data-notice]')).toBe('');
    (slot('text.light').querySelector('[data-save]') as HTMLButtonElement).click();
    await settle(() => text('text.light', '[data-notice]') === 'The assignment was saved.', 'saved notice');
    const put = server.of('PUT', '/api/v1/settings/models');
    expect(put).toHaveLength(1);
    expect(JSON.parse(put[0].body!)).toEqual({ slot: 'text.light', backend: 'local', model: 'gemma-4-large', effort: 'low' });
    expect(put[0].headers['X-CSRF-Token']).toBeDefined();
    expect(items('text.light', '.model-facts dt, .model-facts dd')).toBe('Backend local Model gemma-4-large Effort low');
    // Saving a different model discards the result that described the old one.
    expect(text('text.light', '[data-probe]')).toBe('Not checked yet');
  });

  it('explains an incomplete draft without contacting the server', /** Client-side completeness. */ async () => {
    type(field('text.light', 'model'), '  ');
    (slot('text.light').querySelector('[data-save]') as HTMLButtonElement).click();
    await settle(() => text('text.light', '[data-notice]') !== '', 'incomplete notice');
    expect(text('text.light', '[data-notice]')).toBe('Fill in the model, the effort and every fallback. A custom endpoint also needs its class.');
    expect(server.of('PUT', '/api/v1/settings/models')).toHaveLength(0);
  });

  it('shows the sentence for a refused assignment and never the raw code', /** Fixed codes only. */ async () => {
    type(field('text.light', 'model'), 'not valid!');
    (slot('text.light').querySelector('[data-save]') as HTMLButtonElement).click();
    await settle(() => text('text.light', '[data-notice]') !== '' && !text('text.light', '[data-notice]').startsWith('Saving'), 'refusal');
    expect(text('text.light', '[data-notice]')).toBe('The model or the effort is not a valid identifier. Nothing was saved.');
    expect(slot('text.light').querySelector('[data-notice]')?.classList.contains('error')).toBe(true);
    expect(root.textContent).not.toContain('SLOT_MODEL_INVALID');
    expect(text('text.light', '.model-facts')).toContain('gemma-4');
  });

  it('holds a class change until it is acknowledged, then saves it with the named class', /** The 409 flow. */ async () => {
    choose(field('text.light', 'backend'), 'anthropic-api');
    type(field('text.light', 'model'), 'model-nine');
    (slot('text.light').querySelector('[data-save]') as HTMLButtonElement).click();
    await settle(() => slot('text.light').querySelector('[data-acknowledge]') !== null, 'acknowledgement');

    const block = slot('text.light').querySelector('[data-acknowledge]') as HTMLElement;
    expect(normalize(block.querySelector('p')?.textContent)).toBe('This slot would change from the class Local to the class Metered.');
    expect(Array.from(block.querySelectorAll('li'), (item) => normalize(item.textContent))).toEqual(['Billed per use by the vendor', 'Prompts leave this machine for the API vendor']);
    expect(text('text.light', '[data-notice]')).toBe('This change needs your acknowledgement. Nothing was saved yet.');
    // Focus lands on the explanation, so a repeated Enter cannot confirm by accident.
    await settle(() => document.activeElement === block.querySelector('h4'), 'focus on the explanation');
    // Nothing was stored and the slot still shows the old assignment.
    expect(server.slots[0]['backend']).toBe('local');
    expect(text('text.light', '.model-facts')).toContain('local');
    expect(server.of('PUT', '/api/v1/settings/models')).toHaveLength(1);

    (block.querySelector('[data-confirm]') as HTMLButtonElement).click();
    await settle(() => slot('text.light').querySelector('[data-acknowledge]') === null && text('text.light', '[data-notice]') === 'The assignment was saved.', 'confirmed save');
    const puts = server.of('PUT', '/api/v1/settings/models');
    expect(puts).toHaveLength(2);
    expect(JSON.parse(puts[1].body!)).toEqual({ slot: 'text.light', backend: 'anthropic-api', model: 'model-nine', effort: 'low', acknowledgeClass: 'metered' });
    expect(slot('text.light').querySelector('[data-class]')?.getAttribute('data-class-id')).toBe('metered');
    expect(text('text.light', '[data-acknowledged]')).toBe(`You acknowledged the class Metered on ${WHEN}.`);
  });

  it('cancels a held class change without storing anything and returns focus to the save button', /** Cancelling is safe. */ async () => {
    choose(field('text.light', 'backend'), 'anthropic-api');
    type(field('text.light', 'model'), 'model-nine');
    (slot('text.light').querySelector('[data-save]') as HTMLButtonElement).click();
    await settle(() => slot('text.light').querySelector('[data-acknowledge]') !== null, 'acknowledgement');
    (slot('text.light').querySelector('[data-cancel]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(slot('text.light').querySelector('[data-acknowledge]')).toBeNull();
    expect(text('text.light', '[data-notice]')).toBe('The class change was cancelled. Nothing was saved.');
    expect(document.activeElement).toBe(slot('text.light').querySelector('[data-save]'));
    expect(server.slots[0]['backend']).toBe('local');
    expect(server.of('PUT', '/api/v1/settings/models')).toHaveLength(1);
  });

  it('withdraws a held class change as soon as the draft changes', /** An acknowledgement covers one exact request. */ async () => {
    choose(field('text.light', 'backend'), 'anthropic-api');
    type(field('text.light', 'model'), 'model-nine');
    (slot('text.light').querySelector('[data-save]') as HTMLButtonElement).click();
    await settle(() => slot('text.light').querySelector('[data-acknowledge]') !== null, 'acknowledgement');
    type(field('text.light', 'model'), 'model-ten');
    fixture.detectChanges();
    expect(slot('text.light').querySelector('[data-acknowledge]')).toBeNull();
    expect(server.of('PUT', '/api/v1/settings/models')).toHaveLength(1);
  });

  it('needs a declared class for a custom endpoint and sends it', /** The only backend whose class the owner declares. */ async () => {
    expect(field('text.deliberate', 'declared')).toBeNull();
    choose(field('text.deliberate', 'backend'), 'openai-compatible');
    fixture.detectChanges();
    expect(Array.from(field('text.deliberate', 'declared').querySelectorAll('option'), (option) => option.value)).toEqual(['', 'local', 'metered']);
    type(field('text.deliberate', 'model'), 'model-seven');
    type(field('text.deliberate', 'effort'), 'high');
    (slot('text.deliberate').querySelector('[data-save]') as HTMLButtonElement).click();
    await settle(() => text('text.deliberate', '[data-notice]') !== '', 'incomplete notice');
    expect(server.of('PUT', '/api/v1/settings/models')).toHaveLength(0);

    choose(field('text.deliberate', 'declared'), 'local');
    (slot('text.deliberate').querySelector('[data-save]') as HTMLButtonElement).click();
    await settle(() => text('text.deliberate', '[data-notice]') === 'The assignment was saved.', 'saved');
    expect(JSON.parse(server.of('PUT', '/api/v1/settings/models')[0].body!)).toEqual(
      { slot: 'text.deliberate', backend: 'openai-compatible', model: 'model-seven', effort: 'high', declaredClass: 'local' });
    expect(slot('text.deliberate').querySelector('[data-class]')?.getAttribute('data-class-id')).toBe('local');
    // Switching away from the custom endpoint drops the declaration.
    choose(field('text.deliberate', 'backend'), 'local');
    fixture.detectChanges();
    expect(field('text.deliberate', 'declared')).toBeNull();
  });

  it('edits fallbacks up to the server limit and shows a same-class refusal', /** Add, remove and cross-class. */ async () => {
    const add = () => slot('text.light').querySelector('[data-add-fallback]') as HTMLButtonElement | null;
    for (let count = 0; count < 4; count++) { add()!.click(); fixture.detectChanges(); }
    expect(slot('text.light').querySelectorAll('[data-fallback]')).toHaveLength(4);
    expect(add()).toBeNull();
    expect(Array.from(slot('text.light').querySelectorAll('[data-fallback] legend'), (legend) => legend.textContent)).toEqual(['Fallback 1', 'Fallback 2', 'Fallback 3', 'Fallback 4']);
    expect(Array.from(slot('text.light').querySelectorAll('[data-remove-fallback]'), (button) => button.textContent)[2]).toBe('Remove fallback 3');

    (slot('text.light').querySelectorAll('[data-remove-fallback]')[3] as HTMLButtonElement).click();
    (slot('text.light').querySelectorAll('[data-remove-fallback]')[2] as HTMLButtonElement).click();
    (slot('text.light').querySelectorAll('[data-remove-fallback]')[1] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(document.activeElement).toBe(slot('text.light').querySelector('[data-save]'));
    expect(add()).not.toBeNull();
    type(field('text.light', 'fb-0-model'), 'small-one');
    type(field('text.light', 'fb-0-effort'), 'low');
    (slot('text.light').querySelector('[data-save]') as HTMLButtonElement).click();
    await settle(() => text('text.light', '[data-notice]') === 'The assignment was saved.', 'saved with a fallback');
    expect(JSON.parse(server.of('PUT', '/api/v1/settings/models')[0].body!).fallbacks).toEqual([{ backend: 'local', model: 'small-one', effort: 'low' }]);
    expect(text('text.light', '[data-fallbacks]')).toBe('local · small-one · low');

    choose(field('text.light', 'fb-0-backend'), 'codex');
    (slot('text.light').querySelector('[data-save]') as HTMLButtonElement).click();
    await settle(() => text('text.light', '[data-notice]') === 'A fallback must stay in the same class as the main assignment. Nothing was saved.', 'crossing refused');
    expect(text('text.light', '[data-fallbacks]')).toBe('local · small-one · low');
  });

  it('probes the stored assignment and shows the stored result', /** Readiness checks. */ async () => {
    (slot('text.light').querySelector('[data-probe-button]') as HTMLButtonElement).click();
    await settle(() => text('text.light', '[data-notice]') === 'The check finished.', 'probe finished');
    expect(server.of('POST', '/api/v1/models/probe')).toHaveLength(1);
    expect(text('text.light', '[data-probe]')).toBe(`Unreachable · No adapter for this backend is installed in this engine. Checked ${WHEN} in 3 ms.`);
    expect(slot('text.light').querySelector('[data-probe]')?.getAttribute('data-probe-state')).toBe('unreachable');
    // The probe replaced the stored result only; an unsaved edit survives it.
    type(field('text.light', 'model'), 'edited-not-saved');
    (slot('text.light').querySelector('[data-probe-button]') as HTMLButtonElement).click();
    await settle(() => server.of('POST', '/api/v1/models/probe').length === 2 && text('text.light', '[data-notice]') === 'The check finished.', 'second probe');
    expect((field('text.light', 'model') as HTMLInputElement).value).toBe('edited-not-saved');
  });

  it('keeps focusable controls enabled while a request runs and ignores repeated activation', /** Keyboard users keep their place. */ async () => {
    let release!: () => void;
    server.gate = new Promise<void>((accept) => { release = accept; });
    const save = slot('text.light').querySelector('[data-save]') as HTMLButtonElement;
    save.focus();
    save.click();
    save.click();
    await settle(() => text('text.light', '[data-notice]') === 'Saving the assignment…', 'in flight');
    expect(save.getAttribute('aria-disabled')).toBe('true');
    expect(save.disabled).toBe(false);
    expect(document.activeElement).toBe(save);
    expect(server.of('PUT', '/api/v1/settings/models')).toHaveLength(1);
    release();
    await settle(() => text('text.light', '[data-notice]') === 'The assignment was saved.', 'released');
    expect(save.getAttribute('aria-disabled')).toBeNull();
  });

  it('uses native controls only, so the panel is operable by keyboard', /** Tab, Enter and Space come from the platform. */ () => {
    const interactive = Array.from(root.querySelectorAll('button, input, select, a, [tabindex], [role="button"], [onclick]'));
    expect(interactive.length).toBeGreaterThan(20);
    for (const element of interactive) {
      expect(['button', 'input', 'select', 'h4'], element.outerHTML.slice(0, 80)).toContain(element.localName);
      // Only the acknowledgement heading takes programmatic focus; nothing joins the tab order by tabindex.
      if (element.hasAttribute('tabindex')) expect(element.getAttribute('tabindex')).toBe('-1');
      if (element.localName === 'button') expect(['button', 'submit']).toContain((element as HTMLButtonElement).type);
    }
    expect(root.querySelector('[role="button"], [onclick]')).toBeNull();
    // Enter in a text field submits its form through the one submit button.
    for (const form of Array.from(root.querySelectorAll('form'))) {
      expect(form.querySelectorAll('button[type="submit"]')).toHaveLength(1);
      expect(form.querySelector('fieldset > legend')?.textContent).toBe('Change the assignment');
    }
    // Every field has a programmatic label.
    for (const control of Array.from(root.querySelectorAll('input, select'))) {
      expect(root.querySelector(`label[for="${control.id}"]`), control.id).not.toBeNull();
    }
    expect(root.querySelector('[role="status"][data-notice]')).not.toBeNull();
  });
});

describe('Models panel failures', /** Register failure checks. */ () => {
  afterEach(/** Destroying the fixture aborts requests still in flight. */ () => TestBed.resetTestingModule());

  /** Mount the panel and wait for the failure state. */
  async function failed(configure: (server: ModelsServer) => void): Promise<{ fixture: ComponentFixture<ModelsPanel>; server: ModelsServer }> {
    const server = new ModelsServer();
    configure(server);
    TestBed.configureTestingModule({ providers: [{ provide: LIBRARY_FETCH, useValue: server.fetch }] });
    const fixture = TestBed.createComponent(ModelsPanel);
    fixture.detectChanges();
    await until(fixture, () => (fixture.nativeElement as HTMLElement).querySelector('[data-retry]') !== null, 'failure');
    return { fixture, server };
  }

  it('explains an outage and loads the slots when the owner retries', /** A retry recovers. */ async () => {
    const { fixture, server } = await failed((fake) => { fake.outage = 503; });
    const root = fixture.nativeElement as HTMLElement;
    expect(normalize(root.querySelector('[data-models-state]')?.textContent)).toBe('Model settings could not be reached. Nothing was changed; try again.');
    expect(root.querySelector('[data-slot]')).toBeNull();
    server.outage = null;
    (root.querySelector('[data-retry]') as HTMLButtonElement).click();
    await until(fixture, () => root.querySelectorAll('[data-slot]').length === 4, 'recovered');
    expect(root.querySelector('[data-retry]')).toBeNull();
  });

  it('tells a non-owner session that model settings are for the owner', /** Owner-only, seen from the page. */ async () => {
    const { fixture } = await failed((fake) => { fake.denyNext = 2; });
    const root = fixture.nativeElement as HTMLElement;
    expect(normalize(root.querySelector('[data-models-state]')?.textContent))
      .toBe('This browser session is not allowed to use model settings. Reload the page; only the installation owner can open them.');
    expect(root.querySelector('[data-slot]')).toBeNull();
  });
});
