/** Exercise the persistent composer as a native keyboard-editable form without submission. */
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { routes } from './app.routes';
import { STORY_DRAFT_STORAGE, StoryInputState, StoryStatus } from './preview/story-input-state';
import { PreviewState } from './preview/preview-state';

describe('Story composer', /** Register player-agency regressions through real routed components. */ () => {
  beforeEach(/** Isolate durable drafts and tab-scoped reading positions across tests. */ () => { localStorage.clear(); sessionStorage.clear(); });
  it('renders every recovery status while preserving literal text and selection', /** Every protocol status uses reviewed local prose; none can submit or reinterpret a draft. */ async () => {
    TestBed.configureTestingModule({ providers: [provideRouter(routes)] });
    const harness = await RouterTestingHarness.create('/adventures/mosslight/story');
    const root = harness.routeNativeElement!;
    const state = TestBed.inject(StoryInputState);
    const epoch = state.activate({ adventureId: 'mosslight', campaignId: 'local-preview' });
    const draft = '<script>my words</script>';
    state.edit(draft, 3, 9);
    const statuses: Readonly<Record<StoryStatus, string>> = {
      loading: 'Loading current context', preview: 'Local preview', ready: 'Current context is available',
      disconnected: 'Connection unavailable', reconnecting: 'Operation status must be checked',
      error: 'could not be refreshed', 'stale-revision': 'The world has changed',
      clarification: 'Clarification is needed', cancelled: 'cancelled before commit',
      'narration-pending': 'Do not resend the action', 'restore-required': 'Campaign recovery is required',
    };
    for (const status of Object.keys(statuses) as StoryStatus[]) {
      state.setStatus(status, epoch);
      await harness.fixture.whenStable();
      expect(root.querySelector('.composer [role=status]')?.textContent).toContain(statuses[status]);
      expect(root.querySelector<HTMLTextAreaElement>('#player-input')!.value).toBe(draft);
      expect(state.draft()).toEqual({ text: draft, start: 3, end: 9 });
      expect(root.querySelector('script')).toBeNull();
      expect(root.querySelector<HTMLButtonElement>('[data-send]')!.disabled).toBe(true);
    }
  });
  it('edits suggestions without advancing reading and preserves authored words on scope changes', /** Native input events save drafts; chip activation never submits. */ async () => {
    TestBed.configureTestingModule({ providers: [provideRouter(routes)] });
    const harness = await RouterTestingHarness.create('/adventures/mosslight/story');
    const root = harness.routeNativeElement!;
    const field = root.querySelector<HTMLTextAreaElement>('#player-input')!;
    expect(field.disabled).toBe(false);
    root.querySelector<HTMLButtonElement>('[data-suggestion]')!.click();
    await harness.fixture.whenStable();
    expect(field.value).toContain('grove');
    expect(TestBed.inject(PreviewState).position('mosslight')).toBe(0);
    field.value = 'My edited words';
    field.dispatchEvent(new Event('input', { bubbles: true }));
    await harness.fixture.whenStable();
    expect(root.querySelector<HTMLButtonElement>('[data-suggestion]')!.disabled).toBe(true);
    root.querySelector<HTMLButtonElement>('[data-dismiss]')!.focus();
    root.querySelector<HTMLButtonElement>('[data-dismiss]')!.click();
    await harness.fixture.whenStable();
    expect(field.value).toBe('My edited words');
    expect(document.activeElement).toBe(field);
    expect(root.querySelector<HTMLButtonElement>('.composer button[data-send]')!.disabled).toBe(true);
    await harness.navigateByUrl('/adventures/relay/story');
    expect(root.querySelector<HTMLTextAreaElement>('#player-input')!.value).toBe('');
    await harness.navigateByUrl('/adventures/mosslight/story');
    expect(root.querySelector<HTMLTextAreaElement>('#player-input')!.value).toBe('My edited words');
  });
  it('keeps literal input editable with visible storage and refresh errors', /** Failed persistence/context refresh cannot remove the draft or last public context. */ async () => {
    TestBed.configureTestingModule({ providers: [provideRouter(routes), { provide: STORY_DRAFT_STORAGE, useValue: null }] });
    const harness = await RouterTestingHarness.create('/adventures/mosslight/story');
    const root = harness.routeNativeElement!;
    const state = TestBed.inject(StoryInputState);
    const epoch = state.activate({ adventureId: 'mosslight', campaignId: 'local-preview' });
    expect(state.accept({ adventureId: 'mosslight', campaignId: 'local-preview', revision: 1, presentationRevision: 0,
      suggestions: [{ id: 'literal', kind: 'speech', text: '<b>My own words</b>' }] }, epoch)).toBe(true);
    state.setStatus('preview', epoch);
    await harness.fixture.whenStable();
    expect(root.querySelector('[data-suggestion] b')).toBeNull();
    root.querySelector<HTMLButtonElement>('[data-suggestion]')!.click();
    state.setStatus('error', epoch);
    await harness.fixture.whenStable();
    expect(root.querySelector<HTMLTextAreaElement>('#player-input')!.value).toBe('<b>My own words</b>');
    expect(root.querySelector<HTMLTextAreaElement>('#player-input')!.disabled).toBe(false);
    expect(root.querySelector('#draft-status')?.textContent).toContain('this tab only');
    expect(root.querySelector('.composer [role="status"]')?.textContent).toContain('could not be refreshed');
    expect(state.projection()?.revision).toBe(1);
    expect(root.querySelector<HTMLButtonElement>('[data-send]')!.disabled).toBe(true);
  });

});
