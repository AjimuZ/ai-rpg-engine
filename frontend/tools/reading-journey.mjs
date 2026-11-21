/**
 * QA-29 browser journey for the story screen (P17.3-A): visual-novel and text-focused layouts, attributed reading and history,
 * read-only Continue, quick replies that fill an editable draft without submitting, the configurable reading tokens, and the
 * no-image and keyboard paths. It drives the unlocalized production build in an installed Chrome through a private debugging
 * pipe and a fresh profile, and serves only the build directory on loopback.
 *
 * Usage: `node tools/reading-journey.mjs [screenshot-directory]` after `npm run build`. Exit status is nonzero when any check fails.
 * What it does not establish: the live campaign screen (it has no scene or quick replies yet), real screen-reader output,
 * Firefox or Safari, or a human visual judgment beyond the screenshots it writes.
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

/** Serve build files only; extensionless paths get the single-page entry so routed URLs load directly. */
const server = createServer(async (request, response) => {
  try {
    const pathname = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
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
  await check('No uncaught page exception during the journey', async () => { expect(exceptions.length === 0, exceptions.join(' | ')); });
} finally {
  browser.kill();
  server.close();
  await rm(profile, { recursive: true, force: true }).catch(() => undefined);
}
const failed = results.filter((result) => !result.ok).length;
console.log(`${results.length - failed} PASS, ${failed} FAIL`);
process.exit(failed === 0 ? 0 : 1);
