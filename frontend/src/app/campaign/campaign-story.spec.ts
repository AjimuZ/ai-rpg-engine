/**
 * Live story of the playthrough screen (P17.3-B): layouts, scene and portrait states, attributed reading and history, quick replies,
 * and the explicit Send with its retry identity. The narration source is scripted: operation bodies carrying a published
 * `presentation`, served by the simulated campaign server, so no narration runtime is involved.
 */
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { routes } from '../app.routes';
import { LIBRARY_FETCH } from '../library/saved-library-client';
import { PreviewState } from '../preview/preview-state';
import { STORY_DRAFT_STORAGE } from '../preview/story-input-state';
import { ADVENTURE, CAMPAIGN, CampaignServer, frame, json, MemoryStore, narratedOperation, OP1, OP2, operation, SPEAKER, syncProvider } from './fixtures';

const HASH_SCENE = 'a'.repeat(64);
const HASH_PORTRAIT = 'b'.repeat(64);

/** Wait until the routed screen satisfies a predicate. */
async function settle(harness: RouterTestingHarness, ready: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 80 && !ready(); attempt++) {
    await new Promise((accept) => setTimeout(accept, 0));
    await harness.fixture.whenStable();
  }
  expect(ready()).toBe(true);
}

describe('Live story of the playthrough', /** Register integrated checks against scripted narration. */ () => {
  let server: CampaignServer;
  let drafts: MemoryStore;
  let sync: MemoryStore;
  beforeEach(/** Fresh server, router, storages and reading preferences per test. */ () => {
    sessionStorage.clear();
    server = new CampaignServer();
    drafts = new MemoryStore();
    sync = new MemoryStore();
    server.operations.set(OP1, narratedOperation(OP1));
    // The record a reload leaves: the narrated operation is known by identity, its narration is read again.
    sync.setItem(`engine.campaign-sync.v1:["${ADVENTURE}","${CAMPAIGN}"]`, JSON.stringify({ version: 1, cursor: '0:1',
      operations: [{ operationId: OP1, commandType: 'WAIT', status: 'DELIVERED', operationVersion: 6 }] }));
    TestBed.configureTestingModule({ providers: [provideRouter(routes), { provide: LIBRARY_FETCH, useValue: server.fetch },
      { provide: STORY_DRAFT_STORAGE, useValue: drafts }, syncProvider(sync)] });
  });
  afterEach(/** Destroying the fixture aborts the long-poll follower. */ () => TestBed.resetTestingModule());

  const withArt = (): void => {
    const row = (category: string, subject: string, sha256: string) => ({ category, subject, appearanceRevision: 1, sha256, mediaType: 'image/png', width: 64, height: 64, credit: 'Sample art' });
    server.artworkBody = { scope: { adventureId: ADVENTURE, campaignId: CAMPAIGN }, artwork: [row('scene', 'trailhead', HASH_SCENE), row('portrait', SPEAKER, HASH_PORTRAIT)] };
  };
  async function open(ready: (root: HTMLElement) => boolean = (root) => root.querySelector('[data-live-story]') !== null): Promise<{ harness: RouterTestingHarness; root: HTMLElement }> {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(`/saved/${ADVENTURE}?playthrough=${CAMPAIGN}`);
    const root = harness.routeNativeElement as HTMLElement;
    await settle(harness, () => ready(root));
    return { harness, root };
  }
  const commands = () => server.calls.filter((url) => url.includes('/commands'));

  it('shows the visual-novel layout with scene, portrait, attribution, read-back and chronicle, and sends nothing', /** Default layout. */ async () => {
    withArt();
    const { harness, root } = await open((r) => r.querySelector('.scene img') !== null);
    const story = root.querySelector('[data-live-story]')!;
    expect(story.getAttribute('data-layout')).toBe('visual-novel');
    // A picture that has not loaded yet is `loading`; reading never waits for it.
    expect(story.getAttribute('data-scene')).toBe('loading');
    expect(root.querySelector('.scene img')!.getAttribute('src')).toBe(`/api/v1/campaigns/${CAMPAIGN}/assets/${HASH_SCENE}?adventureId=${ADVENTURE}`);
    expect(root.querySelector('[data-scene-label]')!.textContent).toBe('Lantern Shelter');
    expect(root.querySelector('.portrait')!.getAttribute('src')).toContain(HASH_PORTRAIT);
    expect(root.querySelector('.portrait')!.getAttribute('alt')).toBe('');
    const speaker = root.querySelector('.speaker')!.textContent!;
    expect(speaker).toContain('Trail keeper');
    expect(speaker).toContain('Character');
    expect(root.querySelector('.passage')!.textContent).toBe('Will you come with me?');
    const before = root.querySelector('[data-previous]')!;
    expect(before.textContent).toContain('Narrator');
    expect(before.textContent).toContain('The grove is quiet.');
    expect(root.querySelector('.dialogue')!.contains(before)).toBe(false);
    expect([...root.querySelectorAll('[data-chronicle] li')].map((item) => item.textContent!.trim().split(':')[0])).toEqual(['Narrator (Narration)', 'Trail keeper (Character)']);
    // A scene image that fails keeps a text label and a visible state, and the story stays readable.
    (root.querySelector('.scene img') as HTMLImageElement).dispatchEvent(new Event('error'));
    await settle(harness, () => story.getAttribute('data-scene') === 'failed');
    expect(root.querySelector('[data-scene-fallback]')!.textContent).toContain('Scene illustration unavailable. Lantern Shelter');
    (root.querySelector('.portrait') as HTMLImageElement).dispatchEvent(new Event('error'));
    await settle(harness, () => root.querySelector('.portrait-fallback') !== null);
    expect(root.querySelector('.portrait-fallback')!.textContent!.trim()).toBe('T');
    expect(root.querySelector('.passage')!.textContent).toBe('Will you come with me?');
    expect(commands()).toEqual([]);
  });

  it('shows a text fallback and the state none when the scene has no registered image', /** Missing art never blocks reading. */ async () => {
    const { root } = await open();
    expect(root.querySelector('[data-live-story]')!.getAttribute('data-scene')).toBe('none');
    expect(root.querySelector('.scene img')).toBeNull();
    expect(root.querySelector('[data-scene-fallback]')!.textContent).toContain('Lantern Shelter');
    expect(root.querySelector('.passage')!.textContent).toBe('Will you come with me?');
  });

  it('shows the text-focused layout without any image element or asset request and keeps the choice', /** No artwork at all. */ async () => {
    withArt();
    TestBed.inject(PreviewState).setReadingLayout('text-focused');
    const { harness, root } = await open();
    const story = root.querySelector('[data-live-story]')!;
    expect(story.getAttribute('data-layout')).toBe('text-focused');
    expect(story.getAttribute('data-scene')).toBe('hidden');
    expect(story.classList.contains('text-focused')).toBe(true);
    expect(story.querySelector('img, .scene, .portrait-frame')).toBeNull();
    // The promise covers the whole screen: the panels' gallery and item icons draw no image either.
    expect(root.querySelector('img')).toBeNull();
    expect(server.calls.some((url) => url.includes('/assets/'))).toBe(false);
    expect(root.querySelector('[data-previous]')!.textContent).toContain('Narration');
    // Switching on the screen applies at once and persists with the other reading preferences of the tab.
    const select = root.querySelector('#live-layout') as HTMLSelectElement;
    select.value = 'visual-novel';
    select.dispatchEvent(new Event('change'));
    await settle(harness, () => story.getAttribute('data-layout') === 'visual-novel');
    expect(JSON.parse(sessionStorage.getItem('engine.preview-reading.v1')!).layout).toBe('visual-novel');
    expect(story.querySelector('.scene')).not.toBeNull();
  });

  it('fills an empty draft from a quick reply without sending, and disables the chips while a draft exists', /** Chips never submit. */ async () => {
    const { harness, root } = await open((r) => r.querySelector('[data-suggestion]') !== null);
    const chips = [...root.querySelectorAll('[data-suggestion]')] as HTMLButtonElement[];
    expect(chips.map((chip) => chip.textContent!.trim())).toEqual(['Speech: Agree', 'Question: Ask about the grove', 'Action: Look from here']);
    const send = root.querySelector('[data-send]') as HTMLButtonElement;
    expect(send.disabled).toBe(true);
    chips[1].click();
    const field = root.querySelector('#campaign-input') as HTMLTextAreaElement;
    await settle(harness, () => field.value === 'What do you know about the grove?');
    expect(drafts.values.get(`engine.story-draft.v1:["${ADVENTURE}","${CAMPAIGN}"]`)).toContain('What do you know about the grove?');
    await settle(harness, () => (root.querySelector('[data-suggestion]') as HTMLButtonElement).disabled && !send.disabled);
    // A second choice cannot replace the draft, and nothing was sent.
    (root.querySelectorAll('[data-suggestion]')[0] as HTMLButtonElement).click();
    expect(field.value).toBe('What do you know about the grove?');
    expect(root.querySelector('[data-recipient]')!.textContent).toContain('words to Trail keeper');
    expect(commands()).toEqual([]);
    // Dismissing a chip keeps the draft.
    (root.querySelector('[data-dismiss]') as HTMLButtonElement).click();
    await settle(harness, () => root.querySelectorAll('[data-suggestion]').length === 2);
    expect(field.value).toBe('What do you know about the grove?');
    expect(commands()).toEqual([]);
  });

  it('sends the draft as SPEAK to the last speaker only when Send is pressed, and then empties the draft', /** Explicit action. */ async () => {
    const { harness, root } = await open((r) => r.querySelector('[data-suggestion]') !== null);
    (root.querySelector('[data-suggestion]') as HTMLButtonElement).click();
    const send = root.querySelector('[data-send]') as HTMLButtonElement;
    await settle(harness, () => !send.disabled);
    expect(server.commands).toEqual([]);
    send.click();
    await settle(harness, () => server.commands.length === 1);
    expect(server.commands[0].body).toMatchObject({ expectedRevision: 2, commandType: 'SPEAK', payload: { recipientActorId: SPEAKER, text: 'I will come with you.' } });
    expect(server.commands[0].csrf).toBe('token');
    await settle(harness, () => (root.querySelector('#campaign-input') as HTMLTextAreaElement).value === '');
    expect(drafts.values.get(`engine.story-draft.v1:["${ADVENTURE}","${CAMPAIGN}"]`)).not.toContain('come with you');
  });

  it('repeats a lost Send with the same request identity and keeps the draft until the server holds it', /** Idempotent retry. */ async () => {
    const { harness, root } = await open((r) => r.querySelector('[data-suggestion]') !== null);
    (root.querySelector('[data-suggestion]') as HTMLButtonElement).click();
    const send = root.querySelector('[data-send]') as HTMLButtonElement;
    await settle(harness, () => !send.disabled);
    server.commandReply = () => { throw new TypeError('network'); };
    send.click();
    await settle(harness, () => (root.querySelector('[data-send-note]')?.textContent ?? '').includes('The reply was lost'));
    expect((root.querySelector('#campaign-input') as HTMLTextAreaElement).value).toBe('I will come with you.');
    expect(send.disabled).toBe(false);
    server.commandReply = () => json(operation(OP2, 'COMMITTED', 1, true), 202);
    send.click();
    await settle(harness, () => server.commands.length === 2);
    expect(server.commands[1].body['requestId']).toBe(server.commands[0].body['requestId']);
    expect(server.commands[1].body).toEqual(server.commands[0].body);
    await settle(harness, () => (root.querySelector('#campaign-input') as HTMLTextAreaElement).value === '');
  });

  it('keeps the draft and says why after a refusal, and uses a new identity for the next try', /** A refusal stores nothing. */ async () => {
    const { harness, root } = await open((r) => r.querySelector('[data-suggestion]') !== null);
    (root.querySelector('[data-suggestion]') as HTMLButtonElement).click();
    const send = root.querySelector('[data-send]') as HTMLButtonElement;
    await settle(harness, () => !send.disabled);
    server.commandReply = () => json({ code: 'COMMAND_STALE_REVISION' }, 409);
    send.click();
    await settle(harness, () => (root.querySelector('[data-send-note]')?.textContent ?? '').includes('The world has changed'));
    expect((root.querySelector('#campaign-input') as HTMLTextAreaElement).value).toBe('I will come with you.');
    expect(root.textContent).not.toContain('COMMAND_STALE_REVISION');
    server.commandReply = () => json(operation(OP2, 'COMMITTED', 1, true), 202);
    await settle(harness, () => !(root.querySelector('[data-send]') as HTMLButtonElement).disabled);
    (root.querySelector('[data-send]') as HTMLButtonElement).click();
    await settle(harness, () => server.commands.length === 2);
    expect(server.commands[1].body['requestId']).not.toBe(server.commands[0].body['requestId']);
  });

  it('withdraws the replies when a later action is under way, and offers none when the narrator spoke last', /** Currency and recipient. */ async () => {
    server.streams = [[frame('0:2', 'operation.accepted', { operationId: OP2, commandType: 'WAIT', status: 'ACCEPTED', operationVersion: 0 })]];
    const { root } = await open((r) => r.querySelector('[data-suggestion]') === null && r.querySelector('[data-operation-id]') !== null && r.querySelectorAll('[data-operation-id]').length === 2);
    expect(root.querySelector('.quick-replies')).toBeNull();
    // The composer itself stays usable without replies.
    expect((root.querySelector('#campaign-input') as HTMLTextAreaElement).disabled).toBe(false);
  });

  it('has no recipient and keeps Send disabled when the narrator spoke last', /** The narrator cannot be answered. */ async () => {
    server.operations.set(OP1, narratedOperation(OP1, { speech: false, suggestions: false }));
    const { harness, root } = await open();
    const field = root.querySelector('#campaign-input') as HTMLTextAreaElement;
    field.value = 'Hello';
    field.dispatchEvent(new Event('input'));
    await harness.fixture.whenStable();
    expect((root.querySelector('[data-send]') as HTMLButtonElement).disabled).toBe(true);
    expect(root.querySelector('[data-recipient]')).toBeNull();
    expect(root.textContent).toContain('Sending actions is not connected yet');
    expect(commands()).toEqual([]);
  });
});
