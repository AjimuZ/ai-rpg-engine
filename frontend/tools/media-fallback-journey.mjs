/**
 * Real-browser journey for P17.5-B: images, line audio and music tracks that answer 404 or serve corrupt bytes never block play.
 *
 * It serves the unlocalized production build (`frontend/dist/engine-ui/browser`, built by `sh .agents/ralph/verify.sh frontend`) and a
 * SCRIPTED campaign API on loopback, and drives the live campaign screen in headless Chrome through the private tools in
 * `.agents/ralph/browser/`. The failures happen at the network level: the loopback server itself answers every media request with
 * `404`, or with `200` and bytes that no decoder accepts (a PNG signature or a RIFF header followed by noise). Nothing in the page is
 * stubbed, and only the media answers differ between the three passes:
 *   ok       control: valid PNG and WAV files; images load, music plays, line audio plays to its end
 *   missing  every image, the line audio and the music track answer 404
 *   corrupt  every image, the line audio and the music track answer 200 with undecodable bytes
 * In each failing pass the journey checks that the requests were really answered that way, that the scene, portrait and item fall back
 * to their documented text/initial/built-in icon, that the passage, panels and credits stay, that the line-audio and music failures are
 * reported as text without a retry loop, and that a quick reply plus Send still reaches the server as one typed `SPEAK`. The artwork rows
 * carry planted extra fields: none may reach the page or a request.
 *
 * Usage: `node frontend/tools/media-fallback-journey.mjs [screenshot-directory]` after a frontend build.
 * Exit status: 0 when every printed check holds; 1 when one failed or the browser could not run.
 * Not established: the packaged installation's own engine (a stand-in answers here), real audible output (headless Chrome has none, so
 * media state is measured), narration from a real runtime, Firefox or Safari, a screen reader.
 */
import { createServer } from 'node:http';
import { mkdirSync, readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { extname, join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { withPage } from '../../.agents/ralph/browser/browser.mjs';

const root = fileURLToPath(new URL('../dist/engine-ui/browser/', import.meta.url));
const shots = resolve(process.argv[2] ?? fileURLToPath(new URL('../../.local/scratch/p175b-shots/', import.meta.url)));
mkdirSync(shots, { recursive: true });
const require = createRequire(new URL('../../.agents/ralph/browser/package.json', import.meta.url));
const AXE_SOURCE = readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8');

const ADV = '00000000-0000-4000-8000-00000000000a';
const CAMP = '00000000-0000-4000-8000-0000000000a1';
const OP = '00000000-0000-4000-8000-0000000000c1';
const OP_SENT = '00000000-0000-4000-8000-0000000000c2';
const KEEPER = '00000000-0000-4000-8000-0000000000d1';
const ITEM = '00000000-0000-4000-8000-0000000000e1';
const SYNC_KEY = `engine.campaign-sync.v1:${JSON.stringify([ADV, CAMP])}`;
const SCENE_HASH = 'a'.repeat(64);
const PORTRAIT_HASH = 'b'.repeat(64);
const ITEM_HASH = 'c'.repeat(64);
const MUSIC_HASH = 'd'.repeat(64);
const LINE_ASSET = 'line-greeting';
/** Text a hidden fact would leave behind; it is planted in artwork rows where the contract allows no such field. */
const HIDDEN_MARKER = 'HIDDEN-TWIST-MARKER';

/** A valid 1x1 PNG, enough for a real image load and decode. */
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64');
/** The PNG signature followed by noise: the bytes arrive, no decoder accepts them. */
const BAD_PNG = Buffer.concat([PNG.subarray(0, 8), Buffer.alloc(200, 7)]);
/** A valid 0.3 s silent WAV (8 kHz, 8-bit, mono). */
const WAV = (() => {
  const data = Buffer.alloc(2400, 128);
  const head = Buffer.alloc(44);
  head.write('RIFF', 0); head.writeUInt32LE(36 + data.length, 4); head.write('WAVEfmt ', 8); head.writeUInt32LE(16, 16);
  head.writeUInt16LE(1, 20); head.writeUInt16LE(1, 22); head.writeUInt32LE(8000, 24); head.writeUInt32LE(8000, 28);
  head.writeUInt16LE(1, 32); head.writeUInt16LE(8, 34); head.write('data', 36); head.writeUInt32LE(data.length, 40);
  return Buffer.concat([head, data]);
})();
/** A RIFF/WAVE header followed by noise: not a decodable stream. */
const BAD_WAV = Buffer.concat([Buffer.from('RIFF'), Buffer.alloc(4), Buffer.from('WAVE'), Buffer.alloc(512, 7)]);

/** How the loopback engine answers media requests and what it was asked; `commands` holds every typed command it received. */
const state = { mode: 'ok', commands: [], media: [] };
const scope = { adventureId: ADV, campaignId: CAMP, contentRevisionId: OP_SENT, stateRevision: 2 };
const receipt = { stateRevision: 2, firstEventSequence: 3, lastEventSequence: 4, commandType: 'WAIT', elapsedSeconds: 60, turnAdvanced: true, resources: [], calculations: [], randomDraws: [] };
const operationBody = (id, status, version, extra = {}) => ({ operationId: id, campaignId: CAMP, commandType: id === OP ? 'WAIT' : 'SPEAK', status, baseRevision: 1,
  committedRevision: 2, operationVersion: version, replayed: false, createdAt: '2026-11-21T00:00:00Z', updatedAt: '2026-11-21T00:00:01Z', receipt, query: '/q', stream: '/s', ...extra });
const narrated = operationBody(OP, 'DELIVERED', 6, { presentation: { mode: 'NARRATED', reason: null, retriesRemaining: 0, narration: { locale: 'en', repaired: false,
  passages: [{ messageId: `${OP}:p1`, kind: 'NARRATION', speaker: null, text: 'The grove is quiet; a winding trail leads on.', locale: 'en' },
    { messageId: `${OP}:p2`, kind: 'CHARACTER_SPEECH', speaker: { actorId: KEEPER, name: 'Trail keeper' }, text: 'Will you come with me to the grove?', locale: 'en' }],
  suggestions: [{ suggestionId: `${OP}:s1`, kind: 'REPLY', label: 'Agree', draftText: 'I will come with you.', actionId: null }] } } });
const text = (value) => ({ text: value, locale: 'en', fallback: false });
// The playthrough title must differ from the hero's name ("Mara"): equal names give two regions one accessible name (axe `landmark-unique`),
// a finding about player-chosen names that is recorded in the roadmap and is not what this journey measures.
const campaign = { campaignId: CAMP, adventureId: ADV, contentRevisionId: OP_SENT, title: 'Lantern walk', mode: 'EXPLORATION', stateRevision: 2, gameTurn: 3,
  worldTimeSeconds: 0, eventSequence: 4, updatedAt: '2026-11-21T00:00:00Z', pendingDecisionAvailable: true };
const apiBodies = {
  [`/api/v1/adventures/${ADV}`]: { adventureId: ADV, title: 'The Lantern', genre: 'Fantasy', sourceLocale: 'en', metadataRevision: 0, createdAt: '2026-11-21T00:00:00Z', updatedAt: '2026-11-21T00:00:00Z' },
  [`/api/v1/campaigns/${CAMP}/state`]: { scope, campaign, player: {}, resources: [], inventory: [], abilities: [], knownFacts: [], quests: [],
    startingSituation: { text: 'A lantern glows at the trailhead.', locale: 'en', fallback: false }, pendingDecision: null },
  [`/api/v1/campaigns/${CAMP}/resume`]: { scope, title: 'Lantern walk', mode: 'EXPLORATION', gameTurn: 3, worldTimeSeconds: 0, location: { id: 'trailhead', name: 'Lantern Shelter', nameLocale: 'en', nameFallback: false },
    presentActors: [{ actorId: KEEPER, name: 'Trail keeper' }], resources: [], encounter: null, pendingDecision: 'FREE_INPUT', awaitingNarrationTotal: 0, awaitingNarration: [],
    dependencies: { status: 'AVAILABLE', mutationPaused: false, issues: [] }, continuity: { checkpoint: 'VALID', publicSummary: 'NONE' } },
  [`/api/v1/campaigns/${CAMP}/panels`]: { scope, locale: 'en', contentAvailable: true, clock: { seconds: 90000, day: 2, hour: 1, minute: 0, second: 0 },
    character: { actorId: OP, name: 'Mara', role: 'Surveyor', location: { id: 'trailhead', name: text('Lantern Shelter') }, attributes: [], skills: [], derived: [] },
    resources: [], conditions: [], abilities: [], equipment: [], quests: [], knowledge: { discoveriesTotal: 0, discoveries: [], promisesTotal: 0, promises: [] },
    inventory: [{ itemId: ITEM, definitionId: 'sword', name: text('Sword'), quantity: 1, rarity: 'ordinary', rarityName: text('Ordinary'), wornIn: null }],
    journal: { entries: [], nextBefore: null } },
  [`/api/v1/campaigns/${CAMP}/encounter`]: { scope, mode: 'EXPLORATION', encounter: null, offers: [] },
  [`/api/v1/campaigns/${CAMP}/operations/${OP}`]: narrated,
  '/api/v1/session': { csrfToken: 'token' },
};
const extras = { prompt: `${HIDDEN_MARKER} prompt`, path: `/srv/${HIDDEN_MARKER}.png`, note: HIDDEN_MARKER, url: `https://example.invalid/${HIDDEN_MARKER}`, alt: HIDDEN_MARKER };
const artworkRow = (category, subject, sha256) => ({ category, subject, appearanceRevision: 1, sha256, mediaType: 'image/png', width: 1, height: 1, credit: 'Scripted sample art', ...extras });
const musicListing = { scope: { adventureId: ADV, campaignId: CAMP }, policy: null, tracks: [{ id: 'dusk', title: 'Scripted Dusk', creator: 'Scripted author', mood: 'exploration',
  source: 'Scripted source text', license: 'MIT', licenseUrl: 'https://opensource.org/license/mit', mediaType: 'audio/wav', sha256: MUSIC_HASH,
  notices: 'Scripted notice', modifications: 'None' }] };

/** Answer one media request according to the pass; the status and bytes are what the browser really receives. */
function media(response, pathname, kind) {
  state.media.push({ path: pathname, kind, mode: state.mode });
  if (state.mode === 'missing') return void response.writeHead(404, { 'Content-Type': 'application/json' }).end('{"code":"ASSET_NOT_FOUND"}');
  const good = kind === 'image' ? PNG : WAV;
  const bad = kind === 'image' ? BAD_PNG : BAD_WAV;
  return void response.writeHead(200, { 'Content-Type': kind === 'image' ? 'image/png' : 'audio/wav' }).end(state.mode === 'corrupt' ? bad : good);
}

/** Answer one API request from the script; unknown routes answer like the real server's closed scope. */
async function scripted(request, response, url) {
  const pathname = url.pathname;
  const json = (body, status = 200) => response.writeHead(status, { 'Content-Type': 'application/json' }).end(JSON.stringify(body));
  if (request.method === 'POST' && pathname === `/api/v1/campaigns/${CAMP}/commands`) {
    let body = '';
    for await (const chunk of request) body += chunk;
    state.commands.push(JSON.parse(body));
    return json(operationBody(OP_SENT, 'COMMITTED', 1), 202);
  }
  if (pathname === '/api/v1/campaigns') return json({ campaigns: [campaign], nextAfter: null });
  if (pathname === `/api/v1/campaigns/${CAMP}/artwork`) {
    return json({ scope: { adventureId: ADV, campaignId: CAMP }, artwork: [artworkRow('scene', 'trailhead', SCENE_HASH), artworkRow('portrait', KEEPER, PORTRAIT_HASH),
      artworkRow('item', 'sword', ITEM_HASH)] });
  }
  if (pathname === `/api/v1/campaigns/${CAMP}/music`) return json(musicListing);
  if (pathname === `/api/v1/campaigns/${CAMP}/assets/${MUSIC_HASH}`) return media(response, pathname, 'audio');
  if (pathname.startsWith(`/api/v1/campaigns/${CAMP}/assets/`)) return media(response, pathname, 'image');
  if (pathname.startsWith('/api/v1/assets/')) return media(response, pathname, 'audio');
  if (pathname === `/api/v1/campaigns/${CAMP}/stream`) {
    // The saved cursor is 0:1, so the cue at 0:2 is new once; a later poll is held back so the page does not spin.
    if (url.searchParams.get('after') === '0:2') await new Promise((done) => setTimeout(done, 1500));
    const frame = (id, event, data) => `id: ${id}\nevent: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
    const cue = { campaignId: CAMP, stateRevision: 2, operationId: OP, commandType: 'WAIT', status: 'DELIVERED', operationVersion: 6, deliveryId: `0:2:${OP}:0`,
      kind: 'LINE_AUDIO', ordinal: 0, actorId: KEEPER, lineId: 'greeting', assetId: LINE_ASSET };
    return void response.writeHead(200, { 'Content-Type': 'text/event-stream' })
      .end(frame('0:1', 'stream.ready', { campaignId: CAMP, stateRevision: 2 }) + frame('0:2', 'presentation.event', cue));
  }
  if (apiBodies[pathname]) return json(apiBodies[pathname]);
  json({ code: 'COMMAND_SCOPE_UNAVAILABLE' }, 404);
}

/** Serve build files and the scripted API; extensionless paths get the single-page entry so routed URLs load directly. */
const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url, 'http://localhost');
    const pathname = decodeURIComponent(url.pathname);
    if (pathname.startsWith('/api/')) { await scripted(request, response, url); return; }
    const requested = resolve(root, '.' + pathname);
    if (!requested.startsWith(root.endsWith(sep) ? root : root + sep)) { response.writeHead(403).end(); return; }
    const file = extname(requested) ? requested : resolve(root, 'index.html');
    const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };
    // Read before writing headers: a missing file (the favicon) must still be answered with a clean 404.
    const data = readFileSync(file);
    response.writeHead(200, { 'Content-Type': types[extname(file)] ?? 'application/octet-stream' }).end(data);
  } catch { if (!response.headersSent) response.writeHead(404).end(); }
});
await new Promise((accept, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', accept); });
const origin = `http://127.0.0.1:${server.address().port}`;

let failed = false;
const check = (name, ok, detail) => {
  if (!ok) failed = true;
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${ok || detail === undefined ? '' : ' :: ' + JSON.stringify(detail).slice(0, 600)}`);
};
const wait = (ms) => new Promise((done) => setTimeout(done, ms));
const mediaOf = (mode, hash) => state.media.filter((entry) => entry.mode === mode && entry.path.endsWith(hash));

/** One pass of the journey with the engine answering media as `mode` says. */
async function pass(mode) {
  state.mode = mode;
  state.commands = [];
  const failing = mode !== 'ok';
  console.log(`--- pass: ${mode}`);
  await withPage(async (page) => {
    const problems = [];
    const answers = [];
    const requested = [];
    page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`));
    page.on('console', (m) => { if (m.type() === 'error') problems.push(`console: ${m.text().slice(0, 160)}`); });
    page.on('request', (r) => requested.push(r.url()));
    page.on('response', (r) => { if (r.url().includes('/assets/')) answers.push({ path: new URL(r.url()).pathname, status: r.status(), type: r.headers()['content-type'] }); });

    // The screen is opened as a reload leaves it: the delivered operation is known, its narration is read again, and the stream delivers one line-audio cue.
    await page.goto(`${origin}/library`);
    await page.evaluate(([key, value]) => localStorage.setItem(key, value), [SYNC_KEY, JSON.stringify({ version: 1, cursor: '0:1',
      operations: [{ operationId: OP, commandType: 'WAIT', status: 'DELIVERED', operationVersion: 6 }] })]);
    await page.goto(`${origin}/saved/${ADV}?playthrough=${CAMP}`);
    await page.waitForSelector('[data-live-story]', { timeout: 30000 });
    await page.waitForFunction(() => document.querySelector('[data-live-story]').dataset.scene !== 'loading', null, { timeout: 15000 });
    await page.waitForSelector('[data-audio-toggle]', { timeout: 15000 });
    await page.locator('[data-item="sword"]').scrollIntoViewIfNeeded();
    await page.waitForTimeout(1500);

    // 1. The requests were really answered the way this pass says.
    const scene = answers.filter((a) => a.path.endsWith(SCENE_HASH));
    const portrait = answers.filter((a) => a.path.endsWith(PORTRAIT_HASH));
    const item = answers.filter((a) => a.path.endsWith(ITEM_HASH));
    const expectedStatus = mode === 'missing' ? 404 : 200;
    check(`${mode}: the scene, portrait and item images were requested and answered ${expectedStatus}${mode === 'corrupt' ? ' with undecodable bytes' : ''}`,
      [scene, portrait, item].every((list) => list.length >= 1 && list.every((a) => a.status === expectedStatus)), { scene, portrait, item });

    // 2. The documented image fallbacks.
    const story = await page.evaluate(() => ({ scene: document.querySelector('[data-live-story]').dataset.scene,
      sceneImg: document.querySelector('.scene img') !== null, sceneImgLoaded: (document.querySelector('.scene img')?.naturalWidth ?? 0) > 0,
      sceneFallback: document.querySelector('[data-scene-fallback]')?.textContent ?? null, portraitImg: (document.querySelector('.portrait')?.naturalWidth ?? 0) > 0,
      portraitFallback: document.querySelector('.portrait-fallback')?.textContent?.trim() ?? null,
      itemArt: document.querySelector('[data-item="sword"] .art-frame')?.getAttribute('data-art') ?? null,
      itemImg: document.querySelector('[data-item="sword"] img') !== null, passage: document.querySelector('[data-live-passage] .passage')?.textContent ?? null,
      speaker: document.querySelector('[data-live-passage] .speaker')?.textContent ?? '', previous: document.querySelector('[data-previous]')?.textContent ?? '',
      panels: document.querySelector('[data-panels]') !== null, inventory: document.querySelector('[data-inventory]')?.textContent ?? '',
      credits: document.querySelector('[data-credits]')?.textContent ?? '' }));
    if (failing) {
      check(`${mode}: the scene shows its text label (data-scene="failed") and no image element is left`, story.scene === 'failed' && !story.sceneImg
        && story.sceneFallback?.includes('Lantern Shelter'), story);
      check(`${mode}: the portrait falls back to the speaker's initial`, !story.portraitImg && story.portraitFallback === 'T', story);
      check(`${mode}: the item shows its built-in icon (data-art="fallback") and keeps its name and quantity`, story.itemArt === 'fallback' && !story.itemImg
        && story.inventory.includes('Sword') && story.inventory.includes('× 1'), story);
    } else {
      check('ok: the scene, portrait and item images load', story.scene === 'ready' && story.sceneImgLoaded && story.portraitImg && story.itemArt === 'image', story);
    }
    check(`${mode}: the passage, its attribution, the read-back, the panels and the credits stay`, story.passage === 'Will you come with me to the grove?'
      && story.speaker.includes('Trail keeper') && story.previous.includes('winding trail') && story.panels && story.credits.includes('Artwork credits'), story);
    await page.screenshot({ path: join(shots, `media-${mode}.png`), fullPage: true });

    // 3. Line audio: nothing is fetched before the click; then the request fails or plays, and the text notice is the only consequence.
    check(`${mode}: no line-audio request before the player asks`, answers.every((a) => !a.path.startsWith('/api/v1/assets/')) && state.media.every((m) => !(m.mode === mode && m.path === `/api/v1/assets/${LINE_ASSET}`)));
    await page.click('[data-audio-toggle]');
    if (failing) {
      await page.waitForFunction(() => (document.querySelector('[data-audio-status]')?.textContent ?? '').includes('Audio is not available'), null, { timeout: 15000 }).catch(() => undefined);
    } else {
      await page.waitForTimeout(1500);
    }
    const line = await page.evaluate(() => ({ status: document.querySelector('[data-audio-status]')?.textContent?.trim() ?? null,
      button: document.querySelector('[data-audio-toggle]')?.textContent?.trim() ?? null, passage: document.querySelector('[data-live-passage] .passage')?.textContent ?? null }));
    const lineAnswers = answers.filter((a) => a.path === `/api/v1/assets/${LINE_ASSET}`);
    check(`${mode}: the line audio was requested and answered ${expectedStatus}`, lineAnswers.length >= 1 && lineAnswers.every((a) => a.status === expectedStatus), lineAnswers);
    if (failing) {
      check(`${mode}: the player is told in text that the line has no audio, the button offers Play again, and the passage is unchanged`,
        line.status?.includes('Audio is not available for this line') && line.button === 'Play line audio' && line.passage === 'Will you come with me to the grove?', line);
      const hits = lineAnswers.length;
      await wait(3000);
      check(`${mode}: the failed line is not requested again by itself in 3 s`, answers.filter((a) => a.path === `/api/v1/assets/${LINE_ASSET}`).length === hits, hits);
    } else {
      check('ok: the line plays to its end without a notice and the button returns to Play', line.status === '' && line.button === 'Play line audio', line);
    }

    // 4. Music: off until the player turns it on; a failing track reports itself, offers an explicit retry and is never retried silently.
    check(`${mode}: no music track was requested before the player turned music on`, state.media.every((m) => !(m.mode === mode && m.path.endsWith(MUSIC_HASH))));
    await page.click('[data-music-on]');
    await page.waitForTimeout(3000);
    const music = await page.evaluate(() => ({ status: document.querySelector('[data-music-status]')?.textContent?.trim() ?? null,
      retry: document.querySelector('[data-music-retry]') !== null, pause: document.querySelector('[data-music-pause]') !== null,
      title: document.querySelector('[data-music-title]')?.textContent?.trim() ?? null }));
    const trackHits = mediaOf(mode, MUSIC_HASH).length;
    check(`${mode}: the music track was requested`, trackHits >= 1, state.media);
    if (failing) {
      check(`${mode}: the music panel reports the track as unavailable, shows no current track and offers the retry button`, music.retry && !music.pause && music.title === null, music);
      await wait(5000);
      check(`${mode}: the music track is not requested again by itself in 5 s`, mediaOf(mode, MUSIC_HASH).length === trackHits, { trackHits, now: mediaOf(mode, MUSIC_HASH).length });
    } else {
      check('ok: the music track plays and names itself', music.pause && music.title === 'Scripted Dusk', music);
    }

    // 5. Play continues: a quick reply fills the draft, Send reaches the server as one typed SPEAK with the exact words.
    await page.click('[data-suggestion]');
    await page.waitForFunction(() => document.querySelector('#campaign-input').value === 'I will come with you.', null, { timeout: 15000 });
    await page.waitForFunction(() => !document.querySelector('[data-send]').disabled, null, { timeout: 15000 });
    await page.click('[data-send]');
    await page.waitForFunction(() => document.querySelector('#campaign-input').value === '', null, { timeout: 15000 }).catch(() => undefined);
    const sent = state.commands;
    check(`${mode}: after the media failures a quick reply and Send reach the server as one SPEAK to the speaker with the exact words`, sent.length === 1
      && sent[0].commandType === 'SPEAK' && sent[0].payload.recipientActorId === KEEPER && sent[0].payload.text === 'I will come with you.', sent);

    // 6. Nothing hidden leaks, and the fallback screens stay accessible.
    const html = await page.evaluate(() => document.documentElement.outerHTML);
    check(`${mode}: the page and every request hold none of the planted text, example.invalid or a stored path`, !html.includes(HIDDEN_MARKER) && !html.includes('example.invalid')
      && !html.includes('/srv/') && requested.every((url) => !url.includes(HIDDEN_MARKER) && !url.includes('example.invalid') && !url.includes('/srv/')), requested.filter((u) => u.includes('invalid')));
    check(`${mode}: every media request used the engine's own hash or line route on the loopback origin`, answers.every((a) => /^\/api\/v1\/campaigns\/[0-9a-f-]{36}\/assets\/[0-9a-f]{64}$|^\/api\/v1\/assets\/line-greeting$/.test(a.path))
      && requested.every((url) => url.startsWith(origin) || url.startsWith('data:') || url.startsWith('blob:')), requested.filter((u) => !u.startsWith(origin)));
    await page.evaluate(AXE_SOURCE);
    const axe = await page.evaluate(async () => (await globalThis.axe.run()).violations.map((v) => ({ id: v.id, nodes: v.nodes.map((n) => n.html.slice(0, 120)) })));
    check(`${mode}: axe found no violation on the screen in this state`, axe.length === 0, axe);
    // A failed media load is logged by the browser itself as a console error; nothing else may be, and no script error at all.
    const unexpected = problems.filter((p) => !/Failed to load resource: the server responded with a status of 404/.test(p));
    check(`${mode}: no script error and no console error besides the browser's own report of a 404 answer`, unexpected.length === 0, problems);
  }, { viewport: { width: 1280, height: 800 } });
}

try {
  for (const mode of ['ok', 'missing', 'corrupt']) await pass(mode);
} catch (error) {
  failed = true;
  console.log(`FAIL the journey could not finish :: ${error.message}`);
} finally {
  server.close();
}
process.exit(failed ? 1 : 0);
