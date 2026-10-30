/** Cue display: scene, pose/expression and line cues as text, opt-in keyboard-operable audio, reduced motion. */
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CampaignCues, CUE_AUDIO, CueAudio } from './campaign-cues';
import { PresentationCue } from './campaign-protocol';
import { OP1 } from './fixtures';

function cue(ordinal: number, kind: PresentationCue['kind'], fields: Partial<PresentationCue> = {}): PresentationCue {
  return { deliveryId: `0:${ordinal + 1}:${OP1}:${ordinal}`, kind, ordinal, sceneId: null, actorId: null, poseId: null,
    expressionId: null, lineId: null, assetId: null, ...fields };
}

const SCENE = cue(0, 'SCENE', { sceneId: 'harbour' });
const POSE = cue(1, 'POSE', { poseId: 'guarded', expressionId: 'wary', actorId: OP1 });
const LINE = cue(2, 'LINE_AUDIO', { lineId: 'greeting', assetId: 'line-greeting' });

/** A controllable stand-in for an audio element. */
class FakeAudio implements CueAudio {
  onended: ((event: Event) => void) | null = null;
  onerror: ((event: Event | string) => void) | null = null;
  plays = 0;
  pauses = 0;
  rejectPlay = false;
  constructor(readonly path: string) {}
  play(): Promise<void> { this.plays++; return this.rejectPlay ? Promise.reject(new Error('blocked')) : Promise.resolve(); }
  pause(): void { this.pauses++; }
}

/** Mount the component with a recording player factory. */
function mount(cues: readonly PresentationCue[], options: { rejectPlay?: boolean; throwOnCreate?: boolean } = {}) {
  const created: FakeAudio[] = [];
  TestBed.configureTestingModule({ providers: [{ provide: CUE_AUDIO, useValue: (path: string) => {
    if (options.throwOnCreate) throw new Error('no audio');
    const player = new FakeAudio(path);
    player.rejectPlay = options.rejectPlay ?? false;
    created.push(player);
    return player;
  } }] });
  const fixture: ComponentFixture<CampaignCues> = TestBed.createComponent(CampaignCues);
  fixture.componentRef.setInput('cues', cues);
  fixture.detectChanges();
  return { fixture, root: fixture.nativeElement as HTMLElement, created };
}

describe('Campaign cues', /** Register the display, audio and motion checks. */ () => {
  afterEach(/** Restore globals and destroy the fixture. */ () => { vi.unstubAllGlobals(); TestBed.resetTestingModule(); });

  it('shows scene, pose with expression and line cues as labelled text in the given order', async () => {
    const { root } = mount([SCENE, POSE, LINE]);
    const items = [...root.querySelectorAll('li')];
    expect(items.map((item) => item.getAttribute('data-cue-kind'))).toEqual(['SCENE', 'POSE', 'LINE_AUDIO']);
    expect(items[0].textContent).toContain('Scene');
    expect(items[0].querySelector('[data-scene]')!.textContent).toBe('harbour');
    expect(items[1].querySelector('[data-pose]')!.textContent).toBe('guarded');
    expect(items[1].querySelector('[data-expression]')!.textContent).toBe('wary');
    expect(items[1].getAttribute('data-actor-id')).toBe(OP1);
    expect(items[2].querySelector('[data-line]')!.textContent).toBe('greeting');
    expect(items[2].getAttribute('data-delivery-id')).toBe(LINE.deliveryId);
  });

  it('renders nothing when there are no cues, so the text account stands alone', () => {
    const { root } = mount([]);
    expect(root.querySelector('[data-cues]')).toBeNull();
    expect(root.textContent!.trim()).toBe('');
  });

  it('names a cue that carries no reference instead of leaving it empty, and a line without an asset has no audio control', () => {
    const { root } = mount([cue(0, 'SCENE'), cue(1, 'POSE'), cue(2, 'LINE_AUDIO', { lineId: 'quiet' })]);
    expect(root.querySelector('[data-scene]')!.textContent).toBe('(not named)');
    expect(root.querySelector('[data-pose]')!.textContent).toBe('(not named)');
    expect(root.querySelector('[data-expression]')).toBeNull();
    expect(root.querySelector('[data-audio-toggle]')).toBeNull();
    expect(root.querySelector('[data-audio-none]')!.textContent).toContain('No audio for this line.');
  });

  it('keeps the words of a line apart, because template whitespace alone would be collapsed', () => {
    const { root } = mount([cue(0, 'LINE_AUDIO', { lineId: 'quiet' }), cue(1, 'LINE_AUDIO', { lineId: 'greeting', assetId: 'line-greeting' })]);
    const [silent, voiced] = [...root.querySelectorAll('li')].map((item) => item.textContent!.replace(/\s+/g, ' ').trim());
    expect(silent).toBe('Spoken line: quiet No audio for this line.');
    expect(voiced).toBe('Spoken line: greeting Play line audio');
  });

  it('never creates a player, fetches or plays before the player asks', () => {
    const { root, created } = mount([LINE]);
    expect(created).toHaveLength(0);
    expect(root.querySelector('audio')).toBeNull();
    expect(root.querySelector('[data-audio-toggle]')!.textContent).toContain('Play line audio');
  });

  it('plays only after an activation, from the same-origin asset path, and stops on a second one', async () => {
    const { fixture, root, created } = mount([LINE]);
    const button = root.querySelector('[data-audio-toggle]') as HTMLButtonElement;
    button.click();
    fixture.detectChanges();
    expect(created.map((player) => [player.path, player.plays])).toEqual([['/api/v1/assets/line-greeting', 1]]);
    expect(button.textContent).toContain('Stop line audio');
    button.click();
    fixture.detectChanges();
    expect(created[0].pauses).toBe(1);
    expect(created[0].plays).toBe(1);
    expect(button.textContent).toContain('Play line audio');
  });

  it('is a native, enabled, focusable button, which the browser activates with Enter and Space', () => {
    const { root } = mount([LINE]);
    const button = root.querySelector('[data-audio-toggle]') as HTMLButtonElement;
    document.body.appendChild(root);
    button.focus();
    expect(document.activeElement).toBe(button);
    expect(button.tagName).toBe('BUTTON');
    expect(button.type).toBe('button');
    expect(button.disabled).toBe(false);
    expect(button.tabIndex).toBeGreaterThanOrEqual(0);
    root.remove();
  });

  it('plays one line at a time', () => {
    const second = cue(3, 'LINE_AUDIO', { lineId: 'farewell', assetId: 'line-farewell' });
    const { fixture, root, created } = mount([LINE, second]);
    const [first, next] = [...root.querySelectorAll('[data-audio-toggle]')] as HTMLButtonElement[];
    first.click();
    fixture.detectChanges();
    next.click();
    fixture.detectChanges();
    expect(created.map((player) => player.pauses)).toEqual([1, 0]);
    expect(first.textContent).toContain('Play line audio');
    expect(next.textContent).toContain('Stop line audio');
  });

  it('returns to idle when the line ends and can be played again', () => {
    const { fixture, root, created } = mount([LINE]);
    const button = root.querySelector('[data-audio-toggle]') as HTMLButtonElement;
    button.click();
    created[0].onended!(new Event('ended'));
    fixture.detectChanges();
    expect(button.textContent).toContain('Play line audio');
    expect(root.querySelector('[data-audio-status]')!.textContent).toBe('');
    button.click();
    expect(created).toHaveLength(2);
  });

  it('keeps the text unchanged and says so when the asset fails to load or play', async () => {
    const { fixture, root, created } = mount([SCENE, LINE]);
    const textBefore = root.querySelector('[data-scene]')!.textContent;
    const button = root.querySelector('[data-audio-toggle]') as HTMLButtonElement;
    button.click();
    created[0].onerror!('error');
    fixture.detectChanges();
    expect(root.querySelector('[data-audio-status]')!.textContent).toContain('Audio is not available for this line.');
    expect(root.querySelector('[data-scene]')!.textContent).toBe(textBefore);
    expect(root.querySelector('[data-line]')!.textContent).toBe('greeting');
    expect(button.textContent).toContain('Play line audio');
  });

  it('reports a refused playback and a player that cannot be created the same way', async () => {
    const refused = mount([LINE], { rejectPlay: true });
    (refused.root.querySelector('[data-audio-toggle]') as HTMLButtonElement).click();
    await refused.fixture.whenStable();
    refused.fixture.detectChanges();
    expect(refused.root.querySelector('[data-audio-status]')!.textContent).toContain('not available');
    TestBed.resetTestingModule();

    const broken = mount([LINE], { throwOnCreate: true });
    (broken.root.querySelector('[data-audio-toggle]') as HTMLButtonElement).click();
    broken.fixture.detectChanges();
    expect(broken.root.querySelector('[data-audio-status]')!.textContent).toContain('not available');
    expect(broken.root.querySelector('[data-line]')!.textContent).toBe('greeting');
  });

  it('silences a playing line when the view is destroyed', () => {
    const { fixture, root, created } = mount([LINE]);
    (root.querySelector('[data-audio-toggle]') as HTMLButtonElement).click();
    fixture.destroy();
    expect(created[0].pauses).toBe(1);
  });

  it('disables cue transitions when reduced motion is requested or cannot be determined', () => {
    // jsdom has no matchMedia: the preference is unknown, which counts as reduced motion.
    expect(mount([SCENE]).root.querySelector('[data-cues]')!.getAttribute('data-motion')).toBe('reduced');
    TestBed.resetTestingModule();

    vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener: () => undefined, removeEventListener: () => undefined }));
    expect(mount([SCENE]).root.querySelector('[data-cues]')!.getAttribute('data-motion')).toBe('reduced');
  });

  it('allows the entrance transition only when the browser says motion is allowed, and follows a change', () => {
    let listener: (() => void) | undefined;
    const query = { matches: false, addEventListener: (_: string, fn: () => void) => { listener = fn; }, removeEventListener: () => undefined };
    vi.stubGlobal('matchMedia', () => query);
    const { fixture, root } = mount([SCENE]);
    expect(root.querySelector('[data-cues]')!.getAttribute('data-motion')).toBe('allowed');
    query.matches = true;
    listener!();
    fixture.detectChanges();
    expect(root.querySelector('[data-cues]')!.getAttribute('data-motion')).toBe('reduced');
  });

  it('ships a stylesheet that animates only under allowed motion and switches animation off for the media query', () => {
    mount([SCENE]);
    const css = [...document.querySelectorAll('style')].map((style) => style.textContent ?? '').join('\n');
    expect(css).toMatch(/\[data-motion=['"]?allowed['"]?\][^{]*\{[^}]*animation:[^;}]*cue-in/);
    expect(css).toMatch(/@media \(prefers-reduced-motion: reduce\)[\s\S]*animation:\s*none[\s\S]*transition:\s*none/);
  });
});
