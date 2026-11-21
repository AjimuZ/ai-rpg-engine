/**
 * QA-29 browser journey for the story screens. Part one (P17.3-A) covers the local preview story: visual-novel and text-focused
 * layouts, attributed reading and history, read-only Continue, quick replies that fill an editable draft without submitting, the
 * configurable reading tokens, and the no-image and keyboard paths. Part two (P17.3-B) covers the LIVE campaign screen against a
 * SCRIPTED narration source: the same loopback server answers the campaign API with fixed bodies (state, resume, panels, artwork,
 * a delivered operation carrying published narration, an empty stream) and records every command it receives, so the layouts, scene,
 * portrait and graphic states, attributed reading and history, quick replies, the explicit Send and the retry of a lost reply with
 * the same request identity are exercised with no narration runtime. It drives the unlocalized production build in an installed
 * Chrome through a private debugging pipe and a fresh profile, and serves only the build directory and that scripted API on loopback.
 *
 * Usage: `node tools/reading-journey.mjs [screenshot-directory]` after `npm run build`. Exit status is nonzero when any check fails.
 * What it does not establish: narration produced by a real signed-in runtime (QA-29 with live narration, owner gate P17.3-O), the
 * packaged installation's own engine, real screen-reader output, Firefox or Safari, or a human visual judgment beyond the screenshots.
 */
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { extname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../dist/engine-ui/browser/', import.meta.url));
const scratch = fileURLToPath(new URL('../../.local/scratch/', import.meta.url));
const shots = resolve(process.argv[2] ?? resolve(scratch, 'qa29'));
await mkdir(shots, { recursive: true });
await mkdir(scratch, { recursive: true });
const profile = await mkdtemp(resolve(scratch, 'qa29-profile-'));
const browserPath = process.env['BROWSER_PATH'] ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe';

// ---- Scripted narration source (P17.3-B) ---------------------------------------------------------------------------------------------
const ADV = '00000000-0000-4000-8000-00000000000a';
const CAMP = '00000000-0000-4000-8000-0000000000a1';
const OP = '00000000-0000-4000-8000-0000000000c1';
const OP_SENT = '00000000-0000-4000-8000-0000000000c2';
const KEEPER = '00000000-0000-4000-8000-0000000000d1';
const SYNC_KEY = `engine.campaign-sync.v1:${JSON.stringify([ADV, CAMP])}`;
const DRAFT_KEY = `engine.story-draft.v1:${JSON.stringify([ADV, CAMP])}`;
const SCENE_HASH = 'a'.repeat(64);
const PORTRAIT_HASH = 'b'.repeat(64);
/** A valid 1x1 PNG, enough for a real image load and decode. */
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');
/** What the scripted server does next: how it answers a command, and whether scene art is registered. */
const live = { commands: [], commandMode: 'ok', art: true };
const scope = { adventureId: ADV, campaignId: CAMP, contentRevisionId: OP_SENT, stateRevision: 2 };
const receipt = { stateRevision: 2, firstEventSequence: 3, lastEventSequence: 4, commandType: 'WAIT', elapsedSeconds: 60, turnAdvanced: true, resources: [], calculations: [], randomDraws: [] };
const operationBody = (id, status, version, extra = {}) => ({ operationId: id, campaignId: CAMP, commandType: id === OP ? 'WAIT' : 'SPEAK', status, baseRevision: 1,
  committedRevision: 2, operationVersion: version, replayed: false, createdAt: '2026-11-21T00:00:00Z', updatedAt: '2026-11-21T00:00:01Z', receipt, query: '/q', stream: '/s', ...extra });
const narrated = operationBody(OP, 'DELIVERED', 6, { presentation: { mode: 'NARRATED', reason: null, retriesRemaining: 0, narration: { locale: 'en', repaired: false,
  passages: [{ messageId: `${OP}:p1`, kind: 'NARRATION', speaker: null, text: 'The grove is quiet; a winding trail leads on.', locale: 'en' },
    { messageId: `${OP}:p2`, kind: 'CHARACTER_SPEECH', speaker: { actorId: KEEPER, name: 'Trail keeper' }, text: 'Will you come with me to the grove?', locale: 'en' }],
  suggestions: [{ suggestionId: `${OP}:s1`, kind: 'REPLY', label: 'Agree', draftText: 'I will come with you.', actionId: null },
    { suggestionId: `${OP}:s2`, kind: 'QUESTION', label: 'Ask about the grove', draftText: 'What do you know about the grove?', actionId: null },
    { suggestionId: `${OP}:s3`, kind: 'ACTION', label: 'Look from here', draftText: 'I look at the trail from here.', actionId: null }] } } });
const text = (value) => ({ text: value, locale: 'en', fallback: false });
const panels = { scope, locale: 'en', contentAvailable: true, clock: { seconds: 90000, day: 2, hour: 1, minute: 0, second: 0 },
  character: { actorId: OP, name: 'Mara', role: 'Surveyor', location: { id: 'trailhead', name: text('Lantern Shelter') }, attributes: [], skills: [], derived: [] },
  resources: [], conditions: [], abilities: [], inventory: [], equipment: [], quests: [], knowledge: { discoveriesTotal: 0, discoveries: [], promisesTotal: 0, promises: [] },
  journal: { entries: [], nextBefore: null } };
const apiBodies = {
  [`/api/v1/adventures/${ADV}`]: { adventureId: ADV, title: 'The Lantern', genre: 'Fantasy', sourceLocale: 'en', metadataRevision: 0, createdAt: '2026-11-21T00:00:00Z', updatedAt: '2026-11-21T00:00:00Z' },
  [`/api/v1/campaigns/${CAMP}/state`]: { scope, campaign: { campaignId: CAMP, adventureId: ADV, contentRevisionId: OP_SENT, title: 'Mara', mode: 'EXPLORATION', stateRevision: 2, gameTurn: 3,
    worldTimeSeconds: 0, eventSequence: 4, updatedAt: '2026-11-21T00:00:00Z', pendingDecisionAvailable: true }, player: {}, resources: [], inventory: [], abilities: [], knownFacts: [],
    quests: [], startingSituation: { text: 'A lantern glows at the trailhead.', locale: 'en', fallback: false }, pendingDecision: null },
  [`/api/v1/campaigns/${CAMP}/resume`]: { scope, title: 'Mara', mode: 'EXPLORATION', gameTurn: 3, worldTimeSeconds: 0, location: { id: 'trailhead', name: 'Lantern Shelter', nameLocale: 'en', nameFallback: false },
    presentActors: [{ actorId: KEEPER, name: 'Trail keeper' }], resources: [], encounter: null, pendingDecision: 'FREE_INPUT', awaitingNarrationTotal: 0, awaitingNarration: [],
    dependencies: { status: 'AVAILABLE', mutationPaused: false, issues: [] }, continuity: { checkpoint: 'VALID', publicSummary: 'NONE' } },
  [`/api/v1/campaigns/${CAMP}/panels`]: panels,
  [`/api/v1/campaigns/${CAMP}/encounter`]: { scope, mode: 'EXPLORATION', encounter: null, offers: [] },
  [`/api/v1/campaigns/${CAMP}/music`]: { scope: { adventureId: ADV, campaignId: CAMP }, policy: null, tracks: [] },
  [`/api/v1/campaigns/${CAMP}/operations/${OP}`]: narrated,
  '/api/v1/session': { csrfToken: 'token' },
};
/** Answer one API request from the script; unknown routes answer like the real server's closed scope. */
async function scripted(request, response, pathname) {
  const json = (body, status = 200) => response.writeHead(status, { 'Content-Type': 'application/json' }).end(JSON.stringify(body));
  if (request.method === 'POST' && pathname === `/api/v1/campaigns/${CAMP}/commands`) {
    let body = '';
    for await (const chunk of request) body += chunk;
    live.commands.push(JSON.parse(body));
    // A lost reply: the command arrived, the answer never does.
    // Headers arrive and the body is cut off, so the browser cannot replay the request by itself and the page sees a lost reply.
    if (live.commandMode === 'lose') {
      response.writeHead(202, { 'Content-Type': 'application/json', 'Content-Length': '500' });
      response.write('{"operationId":');
      setTimeout(() => request.socket.destroy(), 50);
      return;
    }
    json(operationBody(OP_SENT, 'COMMITTED', 1), 202);
    return;
  }
  if (pathname === '/api/v1/campaigns') return json({ campaigns: [apiBodies[`/api/v1/campaigns/${CAMP}/state`].campaign], nextAfter: null });
  if (pathname === `/api/v1/campaigns/${CAMP}/artwork`) {
    const row = (category, subject, sha256) => ({ category, subject, appearanceRevision: 1, sha256, mediaType: 'image/png', width: 1, height: 1, credit: 'Scripted sample art' });
    return json({ scope: { adventureId: ADV, campaignId: CAMP }, artwork: live.art ? [row('scene', 'trailhead', SCENE_HASH), row('portrait', KEEPER, PORTRAIT_HASH)] : [] });
  }
  if (pathname.startsWith(`/api/v1/campaigns/${CAMP}/assets/`)) return void response.writeHead(200, { 'Content-Type': 'image/png' }).end(PNG);
  if (pathname === `/api/v1/campaigns/${CAMP}/stream`) {
    return void response.writeHead(200, { 'Content-Type': 'text/event-stream' }).end(`id: 0:1\nevent: stream.ready\ndata: ${JSON.stringify({ campaignId: CAMP, stateRevision: 2 })}\n\n`);
  }
  if (apiBodies[pathname]) return json(apiBodies[pathname]);
  json({ code: 'COMMAND_SCOPE_UNAVAILABLE' }, 404);
}

/** Serve build files and the scripted API; extensionless paths get the single-page entry so routed URLs load directly. */
const server = createServer(async (request, response) => {
  try {
    const pathname = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
    if (pathname.startsWith('/api/')) { await scripted(request, response, pathname); return; }
    const requested = resolve(root, '.' + pathname);
    if (!requested.startsWith(root.endsWith(sep) ? root : root + sep)) { response.writeHead(403).end(); return; }
    const file = extname(requested) ? requested : resolve(root, 'index.html');
    const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };
    const data = await readFile(file);
    response.writeHead(200, { 'Content-Type': types[extname(file)] ?? 'application/octet-stream' }).end(data);
  } catch { response.writeHead(404).end(); }
});
await new Promise((accept, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', accept); });
const origin = `http://127.0.0.1:${server.address().port}`;

const browser = spawn(browserPath, ['--headless=new', '--no-first-run', '--no-default-browser-check', '--disable-background-networking',
  '--remote-debugging-pipe', `--user-data-dir=${profile}`, 'about:blank'], { windowsHide: true, stdio: ['ignore', 'ignore', 'ignore', 'pipe', 'pipe'] });
let sequence = 0;
let buffer = '';
let sessionId;
const pending = new Map();
const requests = [];
const exceptions = [];

browser.stdio[4].on('data', (chunk) => {
  buffer += chunk.toString();
  let boundary;
  while ((boundary = buffer.indexOf('\0')) !== -1) {
    const reply = JSON.parse(buffer.slice(0, boundary));
    buffer = buffer.slice(boundary + 1);
    if (reply.method === 'Network.requestWillBeSent') requests.push(reply.params.request);
    if (reply.method === 'Runtime.exceptionThrown') exceptions.push(reply.params.exceptionDetails.text);
    const waiter = pending.get(reply.id);
    if (waiter) {
      clearTimeout(waiter.timer);
      pending.delete(reply.id);
      if (reply.error) waiter.reject(new Error(JSON.stringify(reply.error))); else waiter.accept(reply.result);
    }
  }
});
browser.on('error', (error) => { for (const waiter of pending.values()) { clearTimeout(waiter.timer); waiter.reject(error); } pending.clear(); });

/** Send one bounded protocol command; a missing reply rejects after fifteen seconds. */
function command(method, params = {}, scoped = true) {
  return new Promise((accept, reject) => {
    const id = ++sequence;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)); }, 15000);
    pending.set(id, { accept, reject, timer });
    browser.stdio[3].write(JSON.stringify({ id, method, params, ...(scoped && sessionId ? { sessionId } : {}) }) + '\0');
  });
}

/** Evaluate page code and return its serializable value; a page exception fails the calling check. */
async function evaluate(expression) {
  const result = await command('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails));
  return result.result.value;
}

/** Poll a page predicate for five seconds. */
async function waitFor(expression) {
  for (let attempt = 0; attempt < 50; attempt++) {
    if (await evaluate(expression)) return;
    await new Promise((accept) => setTimeout(accept, 100));
  }
  throw new Error(`UI timeout: ${expression}`);
}

/** Dispatch one native key press to the focused element. */
async function press(key, code, virtualKey, text) {
  await command('Input.dispatchKeyEvent', { type: 'keyDown', key, code, windowsVirtualKeyCode: virtualKey, ...(text ? { text } : {}) });
  await command('Input.dispatchKeyEvent', { type: 'keyUp', key, code, windowsVirtualKeyCode: virtualKey });
}
const enter = () => press('Enter', 'Enter', 13, '\r');
const tab = () => press('Tab', 'Tab', 9);

/** Resize the emulated viewport. */
const viewport = (width, height, mobile) => command('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile });
/** Open a route directly after clearing the tab's reading preferences, unless `keep` is set. */
async function open(path, keep = false) {
  if (!keep) await evaluate(`sessionStorage.removeItem('engine.preview-reading.v1')`);
  await command('Page.navigate', { url: origin + path });
  await waitFor(`!!document.querySelector('#player-input')`);
}
/** Choose a value in a Settings select the way a user does, through its native change event. */
async function choose(id, value) {
  await evaluate(`(() => { const s = document.querySelector('#${id}'); s.value = ${JSON.stringify(value)}; s.dispatchEvent(new Event('change', { bubbles: true })); })()`);
}
/** Set preferences on the real Settings screen and come back to a story by a direct route (state persists in tab storage). */
async function configure(preferences) {
  await command('Page.navigate', { url: origin + '/settings' });
  await waitFor(`!!document.querySelector('#reading-layout')`);
  for (const [id, value] of Object.entries(preferences)) await choose(id, value);
}
/** Reset only the stored reading positions on the current origin, keeping layout, size and tokens. */
const rewind = () => evaluate(`(() => { const saved = JSON.parse(sessionStorage.getItem('engine.preview-reading.v1')); saved.positions = {}; sessionStorage.setItem('engine.preview-reading.v1', JSON.stringify(saved)); })()`);
const rect = (selector) => evaluate(`(() => { const r = document.querySelector(${JSON.stringify(selector)})?.getBoundingClientRect(); return r ? { top: r.top, bottom: r.bottom, left: r.left, right: r.right, height: r.height, width: r.width } : null; })()`);
const noOverflow = () => evaluate(`document.documentElement.scrollWidth <= innerWidth`);
/** Requests the page made that were not plain reads; reading, suggestions and drafts must never produce one. */
const writes = () => requests.filter((request) => request.method !== 'GET' && request.method !== 'OPTIONS').length;
const mediaRequests = (since) => requests.slice(since).filter((request) => /\/(scenes|portraits)\/.*\.svg/.test(request.url));
async function shot(name) { await writeFile(resolve(shots, name), Buffer.from((await command('Page.captureScreenshot')).data, 'base64')); }

const results = [];
/** Record one named check; a failure is kept and the journey continues so one report shows every problem. */
async function check(name, body) {
  try {
    const value = await body();
    if (value === false) throw new Error('returned false');
    results.push({ name, ok: true });
    console.log(`PASS ${name}`);
  } catch (error) {
    results.push({ name, ok: false });
    console.log(`FAIL ${name}: ${error.message}`);
  }
}
const expect = (condition, message) => { if (!condition) throw new Error(message); };

try {
  await command('Browser.getVersion', {}, false);
  const { targetId } = await command('Target.createTarget', { url: 'about:blank' }, false);
  ({ sessionId } = await command('Target.attachToTarget', { targetId, flatten: true }, false));
  await command('Page.enable');
  await command('Runtime.enable');
  await command('Network.enable');
  await viewport(1440, 1100, false);
  await command('Page.navigate', { url: origin + '/library' });
  await waitFor(`document.querySelector('h1')?.textContent === 'Your adventures'`);

  // ---- Visual novel, desktop -------------------------------------------------------------------------------------------------
  await open('/adventures/mosslight/story');
  await waitFor(`document.querySelector('.story')?.dataset.scene === 'ready'`);
  await check('VN desktop: layout and scene state are exposed, the scene image loaded', async () => {
    expect(await evaluate(`document.querySelector('.story').dataset.layout`) === 'visual-novel', 'layout');
    expect(await evaluate(`document.querySelector('.scene img').naturalWidth > 0`), 'scene image');
  });
  await check('VN desktop: the scene dominates (at least half the viewport, over twice the passage frame)', async () => {
    const scene = await rect('.scene');
    const dialogue = await rect('.dialogue');
    expect(scene.height >= 550, `scene is ${scene.height}px of 1100`);
    expect(scene.height > dialogue.height * 2, `scene ${scene.height} vs passage ${dialogue.height}`);
  });
  await check('VN desktop: the first passage is attributed to the narrator with an explicit role label and has no read-back yet', async () => {
    const speaker = await evaluate(`document.querySelector('.speaker').textContent`);
    expect(speaker.includes('Narrator') && speaker.includes('Narration'), speaker);
    expect(await evaluate(`document.querySelector('[data-previous]') === null`), 'read-back before any history');
    expect(await evaluate(`document.querySelector('.portrait-frame') === null`), 'portrait for narration');
  });
  const writesBefore = writes();
  await check('VN desktop: Continue by keyboard reveals the next passage, moves focus to it and sends nothing', async () => {
    await evaluate(`document.querySelector('.reading-controls button').focus()`);
    await enter();
    await waitFor(`document.querySelector('.speaker').textContent.includes('Trail keeper')`);
    expect(await evaluate(`document.activeElement === document.querySelector('.dialogue')`), 'focus on the passage');
    expect(await evaluate(`document.querySelector('.reading-controls button').disabled`), 'Continue disabled at the end');
    expect(await evaluate(`document.querySelector('#reading-progress').textContent.includes('2 of 2')`), 'progress');
    expect(writes() === writesBefore, 'a non-read request was made');
  });
  await check('VN desktop: the character is labelled and its portrait overlaps the scene while the text stays below it', async () => {
    const speaker = await evaluate(`document.querySelector('.speaker').textContent`);
    expect(speaker.includes('Trail keeper') && speaker.includes('Character'), speaker);
    await waitFor(`document.querySelector('.portrait')?.naturalWidth > 0`);
    const scene = await rect('.scene');
    const frame = await rect('.portrait-frame');
    const text = await rect('.dialogue-text');
    expect(frame.top < scene.bottom && frame.bottom > scene.bottom, `portrait ${frame.top}-${frame.bottom} vs scene bottom ${scene.bottom}`);
    expect(text.top >= scene.bottom - 1, `text starts at ${text.top}, scene ends at ${scene.bottom}`);
    expect(await evaluate(`document.querySelector('.portrait').getAttribute('alt') === ''`), 'portrait is decorative, attribution is text');
    await shot('vn-desktop.png');
  });
  await check('VN desktop: the read-back and the chronicle repeat earlier passages with names and roles, outside the live region', async () => {
    const before = await evaluate(`document.querySelector('[data-previous]')?.textContent ?? ''`);
    expect(before.includes('Narrator') && before.includes('Narration') && before.includes('quiet grove'), before);
    expect(await evaluate(`!document.querySelector('.dialogue').contains(document.querySelector('[data-previous]'))`), 'read-back inside live region');
    expect(await evaluate(`document.querySelector('.dialogue').getAttribute('aria-live')`) === 'polite', 'live region');
    await evaluate(`document.querySelector('summary').focus()`);
    await enter();
    await waitFor(`document.querySelector('details').open`);
    const entries = await evaluate(`[...document.querySelectorAll('.chronicle li')].map(li => li.textContent.trim())`);
    expect(entries.length === 2, `${entries.length} entries`);
    expect(entries[0].startsWith('Narrator (Narration):') && entries[1].startsWith('Trail keeper (Character):'), entries.join(' | '));
    await enter();
    expect(await evaluate(`!document.querySelector('details').open`), 'chronicle closes with Enter');
    expect(writes() === writesBefore, 'a non-read request was made');
  });

  // ---- Quick replies: draft, no submission, stale and duplicate suggestions -----------------------------------------------------
  await open('/adventures/mosslight/story');
  await waitFor(`document.querySelector('[data-suggestion]') !== null`);
  const draftWrites = writes();
  await check('Replies: choosing a suggestion fills an editable draft, focuses it, keeps reading position and submits nothing', async () => {
    const progress = await evaluate(`document.querySelector('#reading-progress').textContent`);
    const labels = await evaluate(`[...document.querySelectorAll('[data-suggestion]')].map(b => b.textContent)`);
    expect(labels.length === 2 && labels.every((text) => text.startsWith('Action:')), labels.join(' | '));
    await evaluate(`document.querySelector('[data-suggestion]').focus()`);
    await enter();
    await waitFor(`document.querySelector('#player-input').value.includes('grove')`);
    expect(await evaluate(`document.activeElement.id`) === 'player-input', 'focus moves to the draft');
    expect(await evaluate(`document.querySelector('#reading-progress').textContent`) === progress, 'reading moved');
    expect(await evaluate(`document.querySelector('[data-send]').disabled`), 'Send must stay disabled');
    expect(writes() === draftWrites, 'a non-read request was made');
  });
  await check('Replies: with a nonempty draft every suggestion is disabled, so a second or duplicate choice cannot replace it', async () => {
    await waitFor(`[...document.querySelectorAll('[data-suggestion]')].every(b => b.disabled)`);
    const draft = await evaluate(`document.querySelector('#player-input').value`);
    await evaluate(`document.querySelectorAll('[data-suggestion]')[1].click(); document.querySelectorAll('[data-suggestion]')[0].click()`);
    expect(await evaluate(`document.querySelector('#player-input').value`) === draft, 'draft replaced');
  });
  await check('Replies: editing keeps the user words and dismissing a suggestion keeps the draft and focus', async () => {
    await evaluate(`document.querySelector('#player-input').select()`);
    await command('Input.insertText', { text: 'My own words' });
    await waitFor(`document.querySelector('#player-input').value === 'My own words'`);
    await evaluate(`document.querySelector('[data-dismiss]').focus(); document.querySelector('[data-dismiss]').click()`);
    await waitFor(`document.querySelectorAll('[data-dismiss]').length === 1`);
    expect(await evaluate(`document.activeElement.id`) === 'player-input', 'focus returns to the draft');
    expect(await evaluate(`document.querySelector('#player-input').value`) === 'My own words', 'draft changed');
    expect(await evaluate(`document.querySelector('[data-dismiss]').getAttribute('aria-label').startsWith('Dismiss suggestion: ')`), 'dismiss label names the reply');
  });
  await check('Replies: a stale suggestion does not survive Continue, and the draft is still kept', async () => {
    await evaluate(`document.querySelector('.reading-controls button').click()`);
    await waitFor(`document.querySelector('.speaker').textContent.includes('Trail keeper')`);
    const labels = await evaluate(`[...document.querySelectorAll('[data-suggestion]')].map(b => b.textContent)`);
    expect(labels.length === 1 && labels[0].startsWith('Question:') && !labels[0].includes('grove'), labels.join(' | '));
    expect(await evaluate(`document.querySelector('#player-input').value`) === 'My own words', 'draft lost');
    expect(writes() === draftWrites, 'a non-read request was made');
  });
  await check('Replies: the clear control empties only the draft and then a suggestion can be chosen again', async () => {
    await evaluate(`document.querySelector('.composer-actions button:last-child').click()`);
    await waitFor(`document.querySelector('#player-input').value === '' && !document.querySelector('[data-suggestion]').disabled`);
    expect(await evaluate(`document.querySelector('#reading-progress').textContent.includes('2 of 2')`), 'reading moved');
    await evaluate(`document.querySelector('#player-input').value = ''`);
  });

  // ---- Visual novel, tokens ------------------------------------------------------------------------------------------------------
  await check('Tokens: size, spacing and width set in Settings reach the story and survive a reload of the tab', async () => {
    await configure({ 'text-size': '20', 'reading-spacing': 'relaxed', 'reading-measure': 'narrow' });
    await open('/adventures/mosslight/story', true);
    const style = await evaluate(`(() => { const s = getComputedStyle(document.querySelector('.passage')); return [parseFloat(s.lineHeight) / parseFloat(s.fontSize), s.maxWidth, s.fontSize]; })()`);
    expect(Math.abs(style[0] - 1.9) < 0.01 && style[1] === '576px' && style[2] === '20px', JSON.stringify(style));
    await command('Page.reload');
    await waitFor(`!!document.querySelector('.passage')`);
    expect(await evaluate(`getComputedStyle(document.querySelector('.passage')).maxWidth`) === '576px', 'token lost on reload');
    await configure({ 'reading-spacing': 'compact', 'reading-measure': 'wide' });
    await open('/adventures/mosslight/story', true);
    const wide = await evaluate(`(() => { const s = getComputedStyle(document.querySelector('.passage')); return [parseFloat(s.lineHeight) / parseFloat(s.fontSize), s.maxWidth]; })()`);
    expect(Math.abs(wide[0] - 1.45) < 0.01 && wide[1] === '1024px', JSON.stringify(wide));
  });

  // ---- Visual novel, narrow screen ------------------------------------------------------------------------------------------------
  await check('VN narrow: 390px and 320px have no horizontal overflow, the scene is shorter than on desktop and text stays reachable', async () => {
    await open('/adventures/mosslight/story');
    await waitFor(`document.querySelector('.story')?.dataset.scene === 'ready'`);
    await evaluate(`document.querySelector('.reading-controls button').click()`);
    await waitFor(`document.querySelector('.portrait')?.naturalWidth > 0`);
    for (const width of [390, 320]) {
      await viewport(width, 844, true);
      expect(await noOverflow(), `overflow at ${width}`);
      const scene = await rect('.scene');
      expect(scene.height < 844 * 0.5 && scene.height >= 200, `scene ${scene.height}px`);
    }
    await viewport(390, 844, true);
    await shot('vn-mobile.png');
    await viewport(1440, 1100, false);
  });

  // ---- Visual novel, no images -----------------------------------------------------------------------------------------------------
  await check('VN without images: the scene and portrait failures keep labels, passage, Continue, replies and the draft usable', async () => {
    await command('Network.setBlockedURLs', { urls: ['*.svg'] });
    await open('/adventures/mosslight/story');
    await waitFor(`document.querySelector('.story')?.dataset.scene === 'failed'`);
    expect(await evaluate(`!!document.querySelector('.scene-fallback')`), 'scene fallback');
    await evaluate(`document.querySelector('.reading-controls button').click()`);
    await waitFor(`!!document.querySelector('.portrait-fallback')`);
    const speaker = await evaluate(`document.querySelector('.speaker').textContent`);
    expect(speaker.includes('Trail keeper') && speaker.includes('Character'), speaker);
    expect(await evaluate(`getComputedStyle(document.querySelector('.scene')).borderTopStyle`) === 'dashed', 'failed edge');
    await evaluate(`document.querySelector('[data-suggestion]').click()`);
    await waitFor(`document.querySelector('#player-input').value.length > 0`);
    expect(await noOverflow(), 'overflow');
    await shot('vn-no-images.png');
    await command('Network.setBlockedURLs', { urls: [] });
  });

  // ---- Text focused ----------------------------------------------------------------------------------------------------------------
  await configure({ 'reading-layout': 'text-focused', 'text-size': '20' });
  const textStart = requests.length;
  // Rewind the stored reading position (keeping layout and tokens) and empty the draft the no-images check left behind, so this
  // phase starts at the first passage with its replies available.
  await rewind();
  await open('/adventures/mosslight/story', true);
  await evaluate(`document.querySelector('.composer-actions button:last-child').click()`);
  await waitFor(`document.querySelector('#player-input').value === ''`);
  await check('Text focused: no image element or image request, a scene description in text and an exposed state', async () => {
    expect(await evaluate(`document.querySelector('.story').dataset.layout`) === 'text-focused', 'layout');
    expect(await evaluate(`document.querySelector('.story').dataset.scene`) === 'hidden', 'scene state');
    expect(await evaluate(`document.querySelectorAll('.story img, .portrait-frame, .scene').length`) === 0, 'artwork elements');
    expect(await evaluate(`document.querySelector('.scene-description').textContent.includes('winding trail')`), 'description');
    expect(mediaRequests(textStart).length === 0, `${mediaRequests(textStart).length} artwork requests`);
  });
  await check('Text focused: attribution, read-back and chronicle carry names and roles, and Continue is read-only', async () => {
    const before = writes();
    await evaluate(`document.querySelector('.reading-controls button').focus()`);
    await enter();
    await waitFor(`document.querySelector('.speaker').textContent.includes('Trail keeper')`);
    const speaker = await evaluate(`document.querySelector('.speaker').textContent`);
    expect(speaker.includes('Character'), speaker);
    expect(await evaluate(`document.querySelector('[data-previous]').textContent.includes('Narration')`), 'read-back');
    expect(await evaluate(`document.querySelectorAll('.chronicle li').length`) === 2, 'chronicle');
    expect(await evaluate(`document.querySelector('.portrait-frame') === null`), 'portrait frame');
    expect(writes() === before, 'a non-read request was made');
    expect(mediaRequests(textStart).length === 0, 'artwork requested after Continue');
  });
  await check('Text focused: the reading column follows the width token and never overflows at 1440, 390 and 320', async () => {
    const column = await rect('.story');
    expect(column.width <= 1024 + 1, `column ${column.width}px`);
    for (const [width, height, mobile] of [[390, 844, true], [320, 740, true], [844, 390, true], [1440, 1100, false]]) {
      await viewport(width, height, mobile);
      expect(await noOverflow(), `overflow at ${width}`);
    }
    await configure({ 'reading-measure': 'narrow' });
    await open('/adventures/mosslight/story', true);
    const narrow = await rect('.story');
    expect(Math.abs(narrow.width - 576) < 1, `narrow column ${narrow.width}px`);
    await shot('text-desktop.png');
    await viewport(390, 844, true);
    await shot('text-mobile.png');
    await viewport(1440, 1100, false);
  });
  await check('Text focused: keyboard reaches Continue or the chronicle, then the replies and the draft, each with a visible focus ring', async () => {
    await open('/adventures/mosslight/story', true);
    await evaluate(`document.body.focus(); document.activeElement.blur()`);
    const visited = [];
    for (let step = 0; step < 18; step++) {
      await tab();
      const info = await evaluate(`(() => { const e = document.activeElement; return { id: e.id, tag: e.tagName, data: Object.keys(e.dataset).join(','), outline: getComputedStyle(e).outlineStyle }; })()`);
      visited.push(info);
    }
    const tags = visited.map((entry) => entry.data || entry.id || entry.tag);
    expect(tags.some((tag) => tag.includes('suggestion')) && tags.includes('player-input'), tags.join(' > '));
    expect(visited.every((entry) => entry.outline !== 'none' || entry.tag === 'BODY'), 'a focused control has no outline');
  });
  await check('Text focused: replies behave as in the visual novel (draft filled, nothing sent, stale replies gone)', async () => {
    await configure({ 'reading-measure': 'standard' });
    await rewind();
    await open('/adventures/mosslight/story', true);
    expect(await evaluate(`document.querySelector('.story').dataset.layout`) === 'text-focused', 'layout changed');
    await waitFor(`document.querySelector('[data-suggestion]') !== null`);
    const before = writes();
    await evaluate(`document.querySelector('[data-suggestion]').click()`);
    await waitFor(`document.querySelector('#player-input').value.includes('grove')`);
    expect(await evaluate(`[...document.querySelectorAll('[data-suggestion]')].every(b => b.disabled)`), 'chips enabled with a draft');
    await evaluate(`document.querySelector('.composer-actions button:last-child').click()`);
    await evaluate(`document.querySelector('.reading-controls button').click()`);
    await waitFor(`document.querySelector('.speaker').textContent.includes('Trail keeper')`);
    expect(await evaluate(`[...document.querySelectorAll('[data-suggestion]')].every(b => b.textContent.startsWith('Question:'))`), 'stale suggestion');
    expect(writes() === before, 'a non-read request was made');
  });
  await check('Layout switch: returning to the visual novel restores the scene and keeps the reading position', async () => {
    await configure({ 'reading-layout': 'visual-novel' });
    await open('/adventures/mosslight/story', true);
    await waitFor(`document.querySelector('.story')?.dataset.scene === 'ready'`);
    expect(await evaluate(`document.querySelector('.speaker').textContent.includes('Trail keeper')`), 'position lost');
  });
  // ======== Live campaign screen against the scripted narration source (P17.3-B) =====================================================
  /** Open the live screen as a reload leaves it: the delivered operation is known by identity, its narration is read again. */
  async function openLive(ready = `!!document.querySelector('[data-live-story]')`) {
    await command('Page.navigate', { url: origin + '/library' });
    await waitFor(`document.querySelector('h1')?.textContent === 'Your adventures'`);
    await evaluate(`localStorage.setItem(${JSON.stringify(SYNC_KEY)}, ${JSON.stringify(JSON.stringify({ version: 1, cursor: '0:1',
      operations: [{ operationId: OP, commandType: 'WAIT', status: 'DELIVERED', operationVersion: 6 }] }))}); localStorage.removeItem(${JSON.stringify(DRAFT_KEY)});`);
    await command('Page.navigate', { url: origin + `/saved/${ADV}?playthrough=${CAMP}` });
    await waitFor(ready);
  }
  const assetRequests = (since) => requests.slice(since).filter((request) => request.url.includes(`/api/v1/campaigns/${CAMP}/assets/`));
  const liveText = (selector) => evaluate(`document.querySelector(${JSON.stringify(selector)})?.textContent ?? ''`);
  live.art = true;
  live.commandMode = 'ok';
  await evaluate(`sessionStorage.removeItem('engine.preview-reading.v1')`);
  await viewport(1440, 1100, false);

  await openLive();
  await waitFor(`document.querySelector('[data-live-story]').dataset.scene === 'ready'`);
  await check('Live VN: layout and scene state are exposed and the scene came from the campaign asset route', async () => {
    expect(await evaluate(`document.querySelector('[data-live-story]').dataset.layout`) === 'visual-novel', 'layout');
    expect(await evaluate(`document.querySelector('.scene img').src`) === `${origin}/api/v1/campaigns/${CAMP}/assets/${SCENE_HASH}?adventureId=${ADV}`, 'scene url');
    expect(await evaluate(`document.querySelector('.scene img').naturalWidth > 0`), 'scene image loaded');
    expect(await liveText('[data-scene-label]') === 'Lantern Shelter', 'place name');
  });
  await check('Live VN: the speaker is attributed with a role label, the portrait overlaps the scene and the text stays below it', async () => {
    const speaker = await liveText('[data-live-passage] .speaker');
    expect(speaker.includes('Trail keeper') && speaker.includes('Character'), speaker);
    await waitFor(`document.querySelector('.portrait')?.naturalWidth > 0`);
    const scene = await rect('.scene');
    const frame = await rect('.portrait-frame');
    const body = await rect('.dialogue-text');
    expect(frame.top < scene.bottom && frame.bottom > scene.bottom, `portrait ${frame.top}-${frame.bottom} vs scene bottom ${scene.bottom}`);
    expect(body.top >= scene.bottom - 1, `text at ${body.top}, scene ends ${scene.bottom}`);
    expect(await evaluate(`document.querySelector('.portrait').getAttribute('alt')`) === '', 'portrait is decorative, attribution is text');
    expect(scene.height >= 550, `scene ${scene.height}px of 1100`);
    await shot('live-vn-desktop.png');
  });
  await check('Live VN: the read-back and the chronicle repeat the earlier passage with name and role, outside the live region', async () => {
    const before = await liveText('[data-previous]');
    expect(before.includes('Narrator') && before.includes('Narration') && before.includes('winding trail'), before);
    expect(await evaluate(`!document.querySelector('[data-live-passage]').contains(document.querySelector('[data-previous]'))`), 'read-back inside live region');
    expect(await evaluate(`document.querySelector('[data-live-passage]').getAttribute('aria-live')`) === 'polite', 'live region');
    await evaluate(`document.querySelector('[data-chronicle] summary').focus()`);
    await enter();
    await waitFor(`document.querySelector('[data-chronicle]').open`);
    const entries = await evaluate(`[...document.querySelectorAll('[data-chronicle] li')].map(li => li.textContent.trim())`);
    expect(entries.length === 2 && entries[0].startsWith('Narrator (Narration):') && entries[1].startsWith('Trail keeper (Character):'), entries.join(' | '));
    expect(live.commands.length === 0, 'reading sent a command');
  });
  await check('Live replies: three labelled chips; choosing one by keyboard fills an editable draft, focuses it and sends nothing', async () => {
    const labels = await evaluate(`[...document.querySelectorAll('[data-suggestion]')].map(b => b.textContent.trim())`);
    expect(labels.join(' | ') === 'Speech: Agree | Question: Ask about the grove | Action: Look from here', labels.join(' | '));
    expect(await evaluate(`document.querySelector('[data-send]').disabled`), 'Send enabled without words');
    await evaluate(`document.querySelectorAll('[data-suggestion]')[1].focus()`);
    await enter();
    await waitFor(`document.querySelector('#campaign-input').value === 'What do you know about the grove?'`);
    expect(await evaluate(`document.activeElement.id`) === 'campaign-input', 'focus moves to the draft');
    await waitFor(`[...document.querySelectorAll('[data-suggestion]')].every(b => b.disabled)`);
    expect(await evaluate(`!document.querySelector('[data-send]').disabled`), 'Send disabled with words');
    await evaluate(`document.querySelectorAll('[data-suggestion]')[0].click()`);
    expect(await evaluate(`document.querySelector('#campaign-input').value`) === 'What do you know about the grove?', 'draft replaced');
    expect(live.commands.length === 0, 'a chip sent a command');
    expect((await liveText('[data-recipient]')).includes('words to Trail keeper'), 'recipient not named');
  });
  await check('Live replies: dismissing a chip keeps the draft and the focus on it', async () => {
    await evaluate(`document.querySelector('[data-dismiss]').focus(); document.querySelector('[data-dismiss]').click()`);
    await waitFor(`document.querySelectorAll('[data-suggestion]').length === 2`);
    expect(await evaluate(`document.activeElement.id`) === 'campaign-input', 'focus returns to the draft');
    expect(await evaluate(`document.querySelector('#campaign-input').value`) === 'What do you know about the grove?', 'draft changed');
    expect(live.commands.length === 0, 'dismissing sent a command');
  });
  await check('Live Send: a lost reply keeps the draft, and pressing Send again repeats the same request identity', async () => {
    live.commandMode = 'lose';
    await evaluate(`document.querySelector('[data-send]').click()`);
    await waitFor(`(document.querySelector('[data-send-note]')?.textContent ?? '').includes('The reply was lost')`);
    expect(live.commands.length === 1, `${live.commands.length} commands after the first press`);
    expect(await evaluate(`document.querySelector('#campaign-input').value`) === 'What do you know about the grove?', 'draft lost with the reply');
    expect(await evaluate(`!document.querySelector('[data-send]').disabled`), 'Send unavailable for a retry');
    live.commandMode = 'ok';
    await evaluate(`document.querySelector('[data-send]').click()`);
    await waitFor(`document.querySelector('#campaign-input').value === ''`);
    expect(live.commands.length === 2, `${live.commands.length} commands after the retry`);
    const [first, second] = live.commands;
    expect(first.requestId === second.requestId && JSON.stringify(first) === JSON.stringify(second), 'the retry is not the same request');
    expect(first.commandType === 'SPEAK' && first.payload.recipientActorId === KEEPER && first.payload.text === 'What do you know about the grove?' && first.expectedRevision === 2,
      JSON.stringify(first));
    expect(await evaluate(`document.querySelector('[data-suggestion]') === null`), 'replies are still offered after the action was saved');
  });
  await check('Live text-focused: the on-screen choice removes every image and asset request, and the choice survives a reload', async () => {
    await openLive();
    await waitFor(`document.querySelector('[data-live-story]').dataset.scene === 'ready'`);
    await evaluate(`(() => { const s = document.querySelector('#live-layout'); s.value = 'text-focused'; s.dispatchEvent(new Event('change', { bubbles: true })); })()`);
    await waitFor(`document.querySelector('[data-live-story]').dataset.layout === 'text-focused'`);
    const mark = requests.length;
    expect(await evaluate(`document.querySelector('[data-live-story]').dataset.scene`) === 'hidden', 'scene state');
    expect(await evaluate(`document.querySelectorAll('[data-live-story] img, .scene, .portrait-frame').length`) === 0, 'artwork elements');
    // A full reload of the same tab keeps the choice and requests no asset at all.
    await command('Page.reload');
    await waitFor(`!!document.querySelector('[data-live-story]')`);
    expect(await evaluate(`document.querySelector('[data-live-story]').dataset.layout`) === 'text-focused', 'choice lost on reload');
    expect(assetRequests(mark).length === 0, assetRequests(mark).map((request) => request.url).join(' '));
    const speaker = await liveText('[data-live-passage] .speaker');
    expect(speaker.includes('Trail keeper') && speaker.includes('Character'), speaker);
    expect((await liveText('[data-previous]')).includes('Narration'), 'read-back');
    expect(await evaluate(`document.querySelectorAll('[data-chronicle] li').length`) === 2, 'chronicle');
    expect(await noOverflow(), 'overflow');
    await shot('live-text-desktop.png');
  });
  await check('Live text-focused: keyboard reaches the chips, the draft and Send, each with a visible focus ring', async () => {
    await evaluate(`document.body.focus(); document.activeElement.blur()`);
    const visited = [];
    const focused = () => evaluate(`(() => { const e = document.activeElement; return { id: e.id, tag: e.tagName, data: Object.keys(e.dataset).join(','), outline: getComputedStyle(e).outlineStyle }; })()`);
    for (let step = 0; step < 60 && !visited.some((entry) => entry.id === 'campaign-input'); step++) { await tab(); visited.push(await focused()); }
    // Send is a native disabled button while the draft is empty, so the keyboard reaches it only once there are words to send.
    await command('Input.insertText', { text: 'Hello' });
    await waitFor(`document.querySelector('#campaign-input').value === 'Hello' && !document.querySelector('[data-send]').disabled`);
    await tab();
    visited.push(await focused());
    expect(live.commands.length === 2, 'typing or tabbing sent a command');
    const tags = visited.map((entry) => entry.data || entry.id || entry.tag);
    expect(tags.some((tag) => tag.includes('suggestion')) && tags.includes('campaign-input') && tags.some((tag) => tag.includes('send')), tags.join(' > '));
    expect(visited.every((entry) => entry.outline !== 'none' || entry.tag === 'BODY'), 'a focused control has no outline');
  });
  await check('Live graphic states: no registered scene shows a text label (none); a failing scene or portrait keeps the text and a dashed edge (failed)', async () => {
    await evaluate(`sessionStorage.removeItem('engine.preview-reading.v1')`);
    live.art = false;
    await openLive();
    expect(await evaluate(`document.querySelector('[data-live-story]').dataset.scene`) === 'none', 'state none');
    expect((await liveText('[data-scene-fallback]')).includes('Lantern Shelter'), 'fallback text');
    expect(await evaluate(`document.querySelector('.portrait-fallback')?.textContent.trim()`) === 'T', 'portrait fallback letter');
    live.art = true;
    await command('Network.setBlockedURLs', { urls: ['*/assets/*'] });
    await openLive();
    await waitFor(`document.querySelector('[data-live-story]').dataset.scene === 'failed'`);
    await waitFor(`!!document.querySelector('.portrait-fallback')`);
    expect(await evaluate(`getComputedStyle(document.querySelector('.scene')).borderTopStyle`) === 'dashed', 'failed edge');
    expect((await liveText('[data-live-passage] .passage')).includes('Will you come with me'), 'passage');
    expect(await evaluate(`!!document.querySelector('[data-suggestion]')`), 'replies');
    await shot('live-vn-no-images.png');
    await command('Network.setBlockedURLs', { urls: [] });
  });
  await check('Live narrow screens: 390px and 320px have no horizontal overflow and the scene stays shorter than half the screen', async () => {
    await openLive();
    await waitFor(`document.querySelector('[data-live-story]').dataset.scene === 'ready'`);
    for (const width of [390, 320]) {
      await viewport(width, 844, true);
      expect(await noOverflow(), `overflow at ${width}`);
      const scene = await rect('.scene');
      expect(scene.height < 844 * 0.5 && scene.height >= 200, `scene ${scene.height}px`);
    }
    await viewport(390, 844, true);
    await shot('live-vn-mobile.png');
    await viewport(1440, 1100, false);
  });
  await check('Live screen sent only the one command the player pressed Send for (twice, the same request)', async () => {
    expect(live.commands.length === 2 && live.commands.every((entry) => entry.commandType === 'SPEAK'), `${live.commands.length} commands`);
  });
  await check('No uncaught page exception during the journey', async () => { expect(exceptions.length === 0, exceptions.join(' | ')); });
} finally {
  browser.kill();
  server.close();
  await rm(profile, { recursive: true, force: true }).catch(() => undefined);
}
const failed = results.filter((result) => !result.ok).length;
console.log(`${results.length - failed} PASS, ${failed} FAIL`);
process.exit(failed === 0 ? 0 : 1);
