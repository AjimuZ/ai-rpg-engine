/** Smoke-test the built preview in an installed Chromium browser; exit nonzero on any failed journey. */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { resolve, sep, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const root = fileURLToPath(new URL('../dist/engine-ui/browser/', import.meta.url));
const localizedRoot = fileURLToPath(new URL('../dist/engine-ui-localized/browser/', import.meta.url));
const reviewLocales = process.argv.includes('--locales');
const artifacts = fileURLToPath(new URL('../../.local/scratch/', import.meta.url));
await mkdir(artifacts, { recursive: true });
const profile = await mkdtemp(resolve(artifacts, 'ui-smoke-'));
const browserPath = process.env['BROWSER_PATH'] ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe';

/** Serve production files only from the build directory; unknown extensionless routes use the SPA entry. */
const server = createServer(async (request, response) => {
  try {
    const pathname = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
    const locale = reviewLocales ? /^\/(en|it)(?:\/|$)/.exec(pathname)?.[1] : undefined;
    const directory = locale ? localizedRoot : root;
    const requested = resolve(directory, '.' + pathname);
    if (!requested.startsWith(directory.endsWith(sep) ? directory : directory + sep) && requested !== resolve(directory)) {
      response.writeHead(403).end();
      return;
    }
    // Review variants expose known preview routes only. Missing resources and API
    // paths must remain 404s rather than becoming successful localized HTML.
    if (locale && !extname(requested) && !new RegExp(`^/${locale}/(?:settings|library|interface-kit|adventures/[^/]+/(?:story|create|sheet|inventory|journal|guide|creative))?$`).test(pathname)) {
      response.writeHead(404).end();
      return;
    }
    const file = extname(requested) ? requested : resolve(directory, ...(locale ? [locale] : []), 'index.html');
    const data = await readFile(file);
    const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };
    response.writeHead(200, { 'Content-Type': types[extname(file)] ?? 'application/octet-stream' }).end(data);
  } catch {
    response.writeHead(404).end();
  }
});
await new Promise(/** Resolve once the ephemeral loopback listener is ready; reject bind failures. */ (accept, reject) => {
  server.once('error', reject);
  server.listen(0, '127.0.0.1', accept);
});
const origin = `http://127.0.0.1:${server.address().port}`;
// A newly created profile and private debugging pipes never reuse owner account state.
const browser = spawn(browserPath, ['--headless=new', '--no-first-run', '--no-default-browser-check',
  '--disable-background-networking', '--remote-debugging-pipe', `--user-data-dir=${profile}`, 'about:blank'],
{ windowsHide: true, stdio: ['ignore', 'ignore', 'ignore', 'pipe', 'pipe'] });
let sequence = 0;
let buffer = '';
let sessionId;
const pending = new Map();
const exceptions = [];
const requests = [];

/** Decode null-delimited CDP replies; reject protocol errors and collect page exceptions/requests. */
browser.stdio[4].on('data', (chunk) => {
  buffer += chunk.toString();
  let boundary;
  while ((boundary = buffer.indexOf('\0')) !== -1) {
    const reply = JSON.parse(buffer.slice(0, boundary));
    buffer = buffer.slice(boundary + 1);
    if (reply.method === 'Runtime.exceptionThrown') exceptions.push(reply.params.exceptionDetails.text);
    if (reply.method === 'Network.requestWillBeSent') requests.push(reply.params.request);
    const waiter = pending.get(reply.id);
    if (waiter) {
      clearTimeout(waiter.timer);
      pending.delete(reply.id);
      if (reply.error) waiter.reject(new Error(JSON.stringify(reply.error)));
      else waiter.accept(reply.result);
    }
  }
});

/** Send one bounded CDP command; startup, protocol and timeout failures reject the returned promise. */
function command(method, params = {}, scoped = true) {
  return new Promise(/** Register correlation before writing to avoid losing a fast reply. */ (accept, reject) => {
    const id = ++sequence;
    const timer = setTimeout(/** Fail closed when a browser command never replies. */ () => {
      pending.delete(id);
      reject(new Error(`CDP timeout: ${method}`));
    }, 15000);
    pending.set(id, { accept, reject, timer });
    browser.stdio[3].write(JSON.stringify({ id, method, params, ...(scoped && sessionId ? { sessionId } : {}) }) + '\0');
  });
}

/** Evaluate browser code and return its serializable result; page exceptions become test failures. */
async function evaluate(expression) {
  const result = await command('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
  assert.equal(result.exceptionDetails, undefined, JSON.stringify(result.exceptionDetails));
  return result.result.value;
}

/** Poll a browser predicate for at most five seconds; throw with the predicate when UI never settles. */
async function waitFor(expression) {
  for (let attempt = 0; attempt < 50; attempt++) {
    if (await evaluate(expression)) return;
    await new Promise(/** Yield between DOM checks without a long blocking sleep. */ (accept) => setTimeout(accept, 100));
  }
  throw new Error(`UI timeout: ${expression}`);
}

/** Navigate using an actual visible link; missing links or failed Angular navigation fail the test. */
async function clickLink(text, path) {
  assert.equal(await evaluate(`(() => { const link = [...document.querySelectorAll('a')].find(a => a.textContent.trim() === ${JSON.stringify(text)}); if (!link) return false; link.click(); return true; })()`), true);
  await waitFor(`location.pathname === ${JSON.stringify(path)}`);
}

/** Dispatch native key events to the focused element; protocol errors reject the journey. */
async function pressKey(key, code, virtualKey, text) {
  await command('Input.dispatchKeyEvent', { type: 'keyDown', key, code, windowsVirtualKeyCode: virtualKey, ...(text ? { text } : {}) });
  await command('Input.dispatchKeyEvent', { type: 'keyUp', key, code, windowsVirtualKeyCode: virtualKey });
}

// Spawn failures should reject pending commands immediately instead of leaving a hanging harness.
browser.on('error', /** Propagate executable/startup errors to every pending command. */ (error) => {
  for (const waiter of pending.values()) { clearTimeout(waiter.timer); waiter.reject(error); }
  pending.clear();
});

try {
  const version = await command('Browser.getVersion', {}, false);
  const { targetId } = await command('Target.createTarget', { url: 'about:blank' }, false);
  ({ sessionId } = await command('Target.attachToTarget', { targetId, flatten: true }, false));
  await command('Page.enable');
  await command('Runtime.enable');
  await command('Network.enable');
  await command('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false });
  await command('Page.navigate', { url: origin + '/library' });
  await waitFor(`document.querySelector('h1')?.textContent === 'Your adventures'`);
  await clickLink('Open story preview', '/adventures/mosslight/story');
  await waitFor(`document.querySelector('.scene img')?.naturalWidth > 0`);
  assert.equal(await evaluate(`document.querySelector('.speaker').textContent.includes('Narrator')`), true);
  assert.equal(await evaluate(`document.querySelector('.scene').getBoundingClientRect().height > document.querySelector('.dialogue').getBoundingClientRect().height * 2`), true);
  await command('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Tab', windowsVirtualKeyCode: 9 });
  await command('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Tab', windowsVirtualKeyCode: 9 });
  assert.equal(await evaluate(`document.activeElement !== document.body`), true);
  await evaluate(`document.querySelector('.reading-controls button').focus()`);
  assert.equal(await evaluate(`document.activeElement === document.querySelector('.reading-controls button')`), true);
  // Chromium needs the native carriage-return text as well as the key code to activate a button.
  await command('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Enter', code: 'Enter', text: '\r', windowsVirtualKeyCode: 13 });
  await command('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 });
  await waitFor(`document.querySelector('.speaker').textContent.includes('Trail keeper')`);
  assert.equal(await evaluate(`document.querySelector('.reading-controls button').disabled`), true);
  assert.equal(await evaluate(`document.activeElement === document.querySelector('.dialogue')`), true);
  await waitFor(`document.querySelector('.portrait')?.naturalWidth > 0`);
  // Tab must skip the now-disabled Continue and reach the next reading control.
  await pressKey('Tab', 'Tab', 9);
  assert.equal(await evaluate(`document.activeElement === document.querySelector('summary')`), true);
  assert.equal(await evaluate(`getComputedStyle(document.activeElement).outlineStyle !== 'none'`), true);
  await pressKey(' ', 'Space', 32, ' ');
  assert.equal(await evaluate(`document.querySelector('details').open`), true);
  await pressKey('Enter', 'Enter', 13, '\r');
  assert.equal(await evaluate(`document.querySelector('details').open`), false);
  await pressKey('Enter', 'Enter', 13, '\r');
  await writeFile(resolve(artifacts, 'p053-desktop.png'), Buffer.from((await command('Page.captureScreenshot')).data, 'base64'));
  for (const [label, path] of [['Character', 'sheet'], ['Inventory', 'inventory'], ['Journal', 'journal'], ['New playthrough', 'create']]) {
    await clickLink(label, `/adventures/mosslight/${path}`);
    await waitFor(`!!document.querySelector('h1')`);
  }
  await evaluate(`(() => { const input = document.querySelector('#character-name'); input.value = 'River'; input.dispatchEvent(new Event('input', {bubbles:true})); })()`);
  await evaluate(`document.querySelector('form button').click()`);
  await waitFor(`document.querySelector('[role="status"]')?.textContent.includes('River')`);
  await clickLink('Trail guide', '/adventures/mosslight/guide');
  await waitFor(`document.querySelector('h1')?.textContent === 'Trail guide'`);
  await clickLink('Settings', '/settings');
  await evaluate(`(() => { const select = document.querySelector('#text-size'); select.value = '24'; select.dispatchEvent(new Event('change', {bubbles:true})); })()`);
  await clickLink('Library', '/library');
  await evaluate(`document.querySelector('a[href="/adventures/relay/story"]').click()`);
  await waitFor(`location.pathname === '/adventures/relay/story' && document.querySelector('.scene img')?.naturalWidth > 0`);
  assert.equal(await evaluate(`getComputedStyle(document.querySelector('.passage')).fontSize`), '24px');
  await clickLink('Crew profile', '/adventures/relay/sheet');
  await waitFor(`document.querySelector('h1')?.textContent === 'Crew profile'`);
  assert.equal(await evaluate(`document.querySelector('[data-resource="charge"]')?.textContent.includes('Not connected')`), true);
  assert.equal(await evaluate(`document.querySelector('nav[aria-label="Adventure"]').textContent.includes('Trail guide')`), false);
  await clickLink('Cargo', '/adventures/relay/inventory');
  await waitFor(`document.querySelector('h1')?.textContent === 'Cargo'`);
  await clickLink('Mission log', '/adventures/relay/journal');
  await waitFor(`document.querySelector('h1')?.textContent === 'Mission log'`);
  await clickLink('Field techniques', '/adventures/relay/creative');
  await waitFor(`document.querySelector('h1')?.textContent === 'Field techniques'`);
  await command('Page.navigate', { url: origin + '/adventures/relay/guide' });
  await waitFor(`document.querySelector('h1')?.textContent === 'Panel unavailable'`);
  await clickLink('Story', '/adventures/relay/story');
  await waitFor(`document.querySelector('.scene img')?.naturalWidth > 0`);
  await command('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  assert.equal(await evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
  await evaluate(`document.querySelector('.reading-controls button').focus()`);
  await pressKey(' ', 'Space', 32, ' ');
  await waitFor(`document.querySelector('.speaker').textContent.includes('Dock attendant')`);
  await waitFor(`document.querySelector('.portrait')?.naturalWidth > 0`);
  assert.equal(await evaluate(`document.activeElement === document.querySelector('.dialogue')`), true);
  await writeFile(resolve(artifacts, 'p053-mobile.png'), Buffer.from((await command('Page.captureScreenshot')).data, 'base64'));
  // A blocked media load preserves labels, passage and reading controls.
  await evaluate(`document.querySelector('.portrait').dispatchEvent(new Event('error')); document.querySelector('.scene img').dispatchEvent(new Event('error'))`);
  await waitFor(`!!document.querySelector('.portrait-fallback') && !!document.querySelector('.scene-fallback')`);
  assert.equal(await evaluate(`document.querySelector('.speaker').textContent.includes('Dock attendant')`), true);
  await clickLink('Settings', '/settings');
  await evaluate(`(() => { const select = document.querySelector('#reading-layout'); select.value = 'text-focused'; select.dispatchEvent(new Event('change', {bubbles:true})); })()`);
  // The earlier full-page direct-route check intentionally reset tab-lifetime preferences.
  await evaluate(`(() => { const select = document.querySelector('#text-size'); select.value = '24'; select.dispatchEvent(new Event('change', {bubbles:true})); })()`);
  await command('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: 'reduce' }] });
  await clickLink('Library', '/library');
  await waitFor(`document.querySelector('h1')?.textContent === 'Your adventures'`);
  const textRequestsStart = requests.length;
  await evaluate(`document.querySelector('a[href="/adventures/relay/story"]').click()`);
  await waitFor(`!!document.querySelector('.story.text-focused')`);
  assert.equal(await evaluate(`document.querySelectorAll('.story img').length`), 0);
  assert.equal(await evaluate(`document.querySelector('.speaker').textContent.includes('Dock attendant')`), true);
  assert.equal(await evaluate(`getComputedStyle(document.querySelector('.passage')).fontSize`), '24px');
  assert.equal(await evaluate(`document.querySelector('.scene-description').textContent.includes('station corridor')`), true);
  // Exercise the no-artwork reading column at a narrow 320px width and landscape.
  for (const [width, height] of [[320, 740], [844, 390]]) {
    await command('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: true });
    assert.equal(await evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
  }
  await command('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
  await evaluate(`document.querySelector('summary').focus()`);
  await pressKey('Enter', 'Enter', 13, '\r');
  await waitFor(`document.querySelector('details').open`);
  assert.equal(await evaluate(`document.querySelectorAll('.chronicle li').length`), 2);
  assert.equal(requests.slice(textRequestsStart).some(/** Text layout must not initiate scene or portrait loads. */ (request) => /\/(scenes|portraits)\/.*\.svg/.test(request.url)), false);
  await writeFile(resolve(artifacts, 'p053-text-mobile.png'), Buffer.from((await command('Page.captureScreenshot')).data, 'base64'));
  await clickLink('Crew profile', '/adventures/relay/sheet');
  assert.equal(await evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
  // Hard reload verifies device persistence independently of the root service's in-memory cache.
  for (const mobile of [false, true]) {
    await command('Emulation.setDeviceMetricsOverride', { width: mobile ? 390 : 1440, height: mobile ? 844 : 1100, deviceScaleFactor: 1, mobile });
    await command('Page.navigate', { url: origin + '/adventures/mosslight/story' });
    await waitFor(`!!document.querySelector('#player-input')`);
    await evaluate(`document.querySelector('.composer-actions button:last-child').click()`);
    const reading = await evaluate(`document.querySelector('#reading-progress').textContent`);
    await evaluate(`document.querySelector('[data-suggestion]').focus()`);
    await pressKey('Enter', 'Enter', 13, '\r');
    await waitFor(`document.querySelector('#player-input').value.includes('grove')`);
    assert.equal(await evaluate(`document.activeElement.id`), 'player-input');
    assert.equal(await evaluate(`document.querySelector('#reading-progress').textContent`), reading);
    await evaluate(`document.querySelector('#player-input').select()`);
    await command('Input.insertText', { text: 'My edited response' });
    await pressKey('Enter', 'Enter', 13, '\r');
    await command('Input.insertText', { text: 'Still drafting.' });
    const draft = 'My edited response\nStill drafting.';
    await waitFor(`document.querySelector('#player-input').value === ${JSON.stringify(draft)}`);
    // Native input updates before zoneless rendering; await the guard's DOM update before asserting it.
    await waitFor(`document.querySelector('[data-suggestion]').disabled`);
    assert.equal(await evaluate(`document.querySelector('[data-suggestion]').disabled`), true);
    await evaluate(`document.querySelector('[data-dismiss]').focus(); document.querySelector('[data-dismiss]').click()`);
    await waitFor(`document.querySelectorAll('[data-dismiss]').length === 1`);
    assert.equal(await evaluate(`document.activeElement.id`), 'player-input');
    assert.equal(await evaluate(`document.querySelector('#player-input').value`), draft);
    await evaluate(`(() => { const field = document.querySelector('#player-input'); field.setSelectionRange(2,5); field.dispatchEvent(new Event('select')); })()`);
    await command('Page.reload');
    await waitFor(`document.querySelector('#player-input')?.value === ${JSON.stringify(draft)}`);
    await evaluate(`document.querySelector('#player-input').focus()`);
    assert.deepEqual(await evaluate(`(() => { const field = document.querySelector('#player-input'); return [field.selectionStart, field.selectionEnd]; })()`), [2, 5]);
    // Browser signals are hints only; online cannot silently assert operation readiness or replay a draft.
    await command('Network.emulateNetworkConditions', { offline: true, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
    await waitFor(`document.querySelector('.composer [role="status"]').textContent.includes('Connection unavailable')`);
    assert.equal(await evaluate(`document.querySelector('#player-input').value`), draft);
    await command('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
    await waitFor(`document.querySelector('.composer [role="status"]').textContent.includes('Operation status must be checked')`);
    assert.equal(await evaluate(`document.querySelector('[data-send]').disabled`), true);
    assert.equal(await evaluate(`document.querySelector('#reading-progress').textContent`), reading);
    await clickLink('Library', '/library');
    await evaluate(`document.querySelector('a[href="/adventures/relay/story"]').click()`);
    await waitFor(`location.pathname === '/adventures/relay/story' && !!document.querySelector('#player-input')`);
    assert.equal(await evaluate(`document.querySelector('#player-input').value`), '');
    await clickLink('Library', '/library');
    await evaluate(`document.querySelector('a[href="/adventures/mosslight/story"]').click()`);
    await waitFor(`document.querySelector('#player-input')?.value === ${JSON.stringify(draft)}`);
    assert.equal(await evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
    if (mobile) await writeFile(resolve(artifacts, 'p054-composer-mobile.png'), Buffer.from((await command('Page.captureScreenshot')).data, 'base64'));
    await evaluate(`document.querySelector('.composer-actions button:last-child').click()`);
    await command('Page.reload');
    await waitFor(`document.querySelector('#player-input')?.value === ''`);
  }
  // Review shared primitives independently of campaign content; samples cannot create world actions.
  await command('Emulation.setDeviceMetricsOverride', { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false });
  await command('Page.navigate', { url: origin + '/settings' });
  await waitFor(`!!document.querySelector('a[href="/interface-kit"]')`);
  await clickLink('Review interface graphic kit', '/interface-kit');
  await waitFor(`!!document.querySelector('button[aria-pressed]')`);
  assert.equal(await evaluate(`document.querySelectorAll('[role=meter]').length`), 3);
  assert.equal(await evaluate(`document.querySelectorAll('.icon-inventory li').length`), 20);
  assert.equal(await evaluate(`document.querySelector('button[aria-busy]').disabled`), true);
  assert.equal(await evaluate(`document.querySelector('input[aria-invalid]').getAttribute('aria-describedby')`), 'kit-error-help');
  await evaluate(`document.querySelector('button[aria-pressed]').focus()`);
  await pressKey('Enter', 'Enter', 13, '\r');
  await waitFor(`document.querySelector('button[aria-pressed]').getAttribute('aria-pressed') === 'true'`);
  assert.equal(await evaluate(`document.querySelector('button[aria-pressed]').getAttribute('aria-pressed')`), 'true');
  assert.equal(await evaluate(`getComputedStyle(document.activeElement).outlineStyle`), 'solid');
  await evaluate(`document.querySelector('.toggle input').focus()`);
  await pressKey(' ', 'Space', 32, ' ');
  assert.equal(await evaluate(`document.querySelector('.toggle input').checked`), true);
  await evaluate(`document.querySelector('.kit-help summary').focus()`);
  await pressKey('Enter', 'Enter', 13, '\r');
  assert.equal(await evaluate(`document.querySelector('.kit-help').open`), true);
  // Measure contrast from rendered opaque colours instead of duplicating palette estimates.
  assert.equal(await evaluate(`(() => {
    /** Convert computed RGB to relative luminance; all sampled theme colours are opaque. */
    const luminance = colour => colour.match(/[\\d.]+/g).slice(0, 3).map(Number).map(/** Linearize one sRGB byte for the WCAG luminance formula. */ v => { const c = v / 255; return c <= .04045 ? c / 12.92 : ((c + .055) / 1.055) ** 2.4; }).reduce(/** Weight linear channels into relative luminance. */ (sum, c, i) => sum + c * [.2126, .7152, .0722][i], 0);
    /** Return the WCAG contrast ratio for two opaque computed colours. */
    const ratio = (a, b) => { const x = luminance(a), y = luminance(b); return (Math.max(x, y) + .05) / (Math.min(x, y) + .05); };
    const panel = getComputedStyle(document.querySelector('.graphic-kit')).backgroundColor;
    const field = getComputedStyle(document.querySelector('#kit-field'));
    const placeholder = getComputedStyle(document.querySelector('#kit-field'), '::placeholder');
    const button = getComputedStyle(document.querySelector('button[aria-pressed]'));
    const text = [...document.querySelectorAll('.graphic-kit p, .graphic-kit label, .speaker')].every(/** Check each visible label against its shared opaque panel surface. */ el => ratio(getComputedStyle(el).color, panel) >= 4.5);
    return text && ratio(placeholder.color, field.backgroundColor) >= 4.5 && ratio(field.color, field.backgroundColor) >= 4.5 && ratio(field.borderTopColor, field.backgroundColor) >= 3 && ratio(button.color, button.backgroundColor) >= 4.5 && ratio(button.outlineColor, panel) >= 3;
  })()`), true);
  // Pointer states must remain distinguishable without an animated transition.
  await evaluate(`document.querySelector('.quick-replies button').scrollIntoView({block: 'center'})`);
  const point = await evaluate(`(() => { const r = document.querySelector('.quick-replies button').getBoundingClientRect(); return { x: r.x + r.width / 2, y: r.y + r.height / 2 }; })()`);
  const normal = await evaluate(`getComputedStyle(document.querySelector('.quick-replies button')).backgroundColor`);
  await command('Input.dispatchMouseEvent', { type: 'mouseMoved', ...point });
  const hover = await evaluate(`getComputedStyle(document.querySelector('.quick-replies button')).backgroundColor`);
  assert.notEqual(hover, normal);
  await command('Input.dispatchMouseEvent', { type: 'mousePressed', button: 'left', clickCount: 1, ...point });
  assert.notEqual(await evaluate(`getComputedStyle(document.querySelector('.quick-replies button')).backgroundColor`), hover);
  await command('Input.dispatchMouseEvent', { type: 'mouseReleased', button: 'left', clickCount: 1, ...point });
  for (const [width, height] of [[1440, 1100], [390, 844], [320, 740], [740, 320]]) {
    await command('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: width < 600 });
    assert.equal(await evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
    if (width === 1440 || width === 390) {
      const { cssContentSize } = await command('Page.getLayoutMetrics');
      await writeFile(resolve(artifacts, `p055-kit-${width}.png`), Buffer.from((await command('Page.captureScreenshot', { captureBeyondViewport: true, clip: { x: 0, y: 0, width, height: cssContentSize.height, scale: 1 } })).data, 'base64'));
    }
  }
  await command('Emulation.setEmulatedMedia', { features: [{ name: 'forced-colors', value: 'active' }, { name: 'prefers-reduced-motion', value: 'reduce' }] });
  assert.equal(await evaluate(`getComputedStyle(document.querySelector('.meter-fill')).forcedColorAdjust`), 'none');
  assert.equal(await evaluate(`getComputedStyle(document.querySelector('progress:indeterminate')).appearance`), 'none');
  assert.equal(await evaluate(`document.querySelector('[role=meter]').getAttribute('aria-valuenow')`), '18');
  await command('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-contrast', value: 'more' }] });
  assert.equal(await evaluate(`getComputedStyle(document.querySelector('#kit-field')).borderTopWidth`), '2px');
  await command('Emulation.setEmulatedMedia', { features: [] });
  assert.equal(await evaluate(`fetch('/ui/LICENSE.txt').then(r => r.text()).then(text => text.includes('MIT License') && text.includes('Copyright (c) 2026 Azim Ben Othman'))`), true);
  assert.equal(await evaluate(`fetch('/ui/README.md').then(r => r.text()).then(text => text.includes('src/app/preview/ui-kit.ts') && text.includes('No third-party artwork'))`), true);
  await command('Page.navigate', { url: origin + '/adventures/missing/story' });
  await waitFor(`document.querySelector('h1')?.textContent === 'Adventure unavailable'`);
  if (reviewLocales) {
    for (const [locale, heading, navigation, title] of [
      ['en', 'Settings', 'Application', 'Settings | Adventure engine'],
      ['it', 'Impostazioni', 'Applicazione', 'Impostazioni | Motore di avventure'],
    ]) {
      await command('Page.navigate', { url: `${origin}/${locale}/settings` });
      await waitFor(`document.querySelector('h1')?.textContent === ${JSON.stringify(heading)}`);
      assert.equal(await evaluate('document.documentElement.lang'), locale);
      assert.equal(await evaluate('document.baseURI'), `${origin}/${locale}/`);
      assert.equal(await evaluate('document.title'), title);
      assert.equal(await evaluate(`document.querySelector('nav').getAttribute('aria-label')`), navigation);
      assert.equal(await evaluate(`document.querySelector('#text-size').labels[0].textContent`), locale === 'it' ? 'Dimensione del testo della storia' : 'Story text size');
      assert.equal(await evaluate(`document.querySelector('#text-size').options.length`), 10);
      assert.equal(await evaluate(`document.querySelector('a.brand').getAttribute('href')`), `/${locale}/library`);
      await evaluate(`document.querySelector('#text-size').focus()`);
      assert.equal(await evaluate(`getComputedStyle(document.activeElement).outlineStyle`), 'solid');
      for (const [width, height] of [[1440, 1100], [390, 844], [320, 740], [740, 320]]) {
        await command('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: width < 600 });
        assert.equal(await evaluate('document.documentElement.scrollWidth <= innerWidth'), true);
        if (width === 1440 || width === 390) {
          await writeFile(resolve(artifacts, `p056-settings-${locale}-${width}.png`), Buffer.from((await command('Page.captureScreenshot')).data, 'base64'));
        }
      }
      // Exercise real compiled translations, not runtime replacement of English
      // strings. Draft bytes and stable IDs remain shared across both variants.
      const italian = locale === 'it';
      await evaluate('localStorage.clear()');
      await command('Page.navigate', { url: `${origin}/${locale}/adventures/mosslight/story` });
      await waitFor(`document.querySelector('#player-input') !== null`);
      assert.equal(await evaluate('document.title'), italian ? 'Storia | Motore di avventure' : 'Story | Adventure engine');
      assert.equal(await evaluate(`document.querySelector('.workspace-heading p').textContent.includes('Mosslight Trail')`), true);
      assert.equal(await evaluate(`document.querySelector('.dialogue').getAttribute('aria-label')`), italian ? 'Brano corrente' : 'Current passage');
      assert.equal(await evaluate(`document.querySelector('#player-input').placeholder`), italian ? 'Scrivi una bozza…' : 'Write a draft…');
      assert.ok((await evaluate(`document.querySelector('.chronicle summary').textContent`)).includes(italian ? 'Un brano' : 'One passage'));
      assert.ok((await evaluate(`document.querySelector('[data-dismiss]').getAttribute('aria-label')`)).startsWith(italian ? 'Nascondi suggerimento: ' : 'Dismiss suggestion: '));
      await evaluate(`document.querySelector('[data-suggestion]').click()`);
      await waitFor(`document.querySelector('#player-input').value === ${JSON.stringify(italian ? 'Osserva il boschetto.' : 'Look around the grove.')}`);
      await evaluate(`(/** Save literal player input and a nonterminal selection; DOM failures reject the browser check. */ () => {
        const field = document.querySelector('#player-input');
        field.value = 'My words <b>restano</b>';
        field.setSelectionRange(3, 8);
        field.dispatchEvent(new Event('input', { bubbles: true }));
      })()`);
      await command('Page.reload');
      await waitFor(`document.querySelector('#player-input')?.value === 'My words <b>restano</b>'`);
      await evaluate(`document.querySelector('#player-input').focus()`);
      assert.deepEqual(await evaluate(`(/** Read native UTF-16 selection offsets; missing input fails the browser check. */ () => { const field = document.querySelector('#player-input'); return [field.selectionStart, field.selectionEnd]; })()`), [3, 8]);
      assert.equal(await evaluate(`document.querySelector('.composer b') === null`), true);
      await evaluate(`document.querySelector('.reading-controls button').click()`);
      await waitFor(`document.querySelector('.reading-controls button').disabled`);
      assert.ok((await evaluate(`document.querySelector('.speaker').textContent`)).includes(italian ? 'Custode del sentiero' : 'Trail keeper'));
      assert.ok((await evaluate(`document.querySelector('.chronicle summary').textContent`)).includes(italian ? '2 brani' : '2 passages'));
      assert.ok((await evaluate(`document.querySelector('#reading-progress').textContent`)).includes(italian ? 'Brano 2 di 2' : 'Passage 2 of 2'));
      assert.equal(await evaluate(`document.activeElement.classList.contains('dialogue')`), true);
      await evaluate(`window.dispatchEvent(new Event('offline'))`);
      await waitFor(`document.querySelector('.composer [role=status]').textContent.includes(${JSON.stringify(italian ? 'Connessione non disponibile' : 'Connection unavailable')})`);
      await evaluate(`window.dispatchEvent(new Event('online'))`);
      await waitFor(`document.querySelector('.composer [role=status]').textContent.includes(${JSON.stringify(italian ? 'Prima di riprovare' : 'before any retry')})`);
      assert.equal(await evaluate(`document.querySelector('#player-input').value`), 'My words <b>restano</b>');
      assert.equal(await evaluate(`document.querySelector('[data-send]').disabled`), true);
      for (const [width, height] of [[1440, 1100], [390, 844], [320, 740], [740, 320]]) {
        await command('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: width < 600 });
        assert.equal(await evaluate('document.documentElement.scrollWidth <= innerWidth'), true);
        if (width === 1440 || width === 390) {
          await writeFile(resolve(artifacts, `p056-story-${locale}-${width}.png`), Buffer.from((await command('Page.captureScreenshot')).data, 'base64'));
        }
      }
      await evaluate(`document.querySelector('.scene img').dispatchEvent(new Event('error'))`);
      await waitFor(`document.querySelector('.scene-fallback') !== null`);
      assert.ok((await evaluate(`document.querySelector('.scene-fallback').textContent`)).includes(italian ? 'Illustrazione della scena non disponibile' : 'Scene illustration unavailable'));
      // Native constraint/receipt text must be translated while user names stay
      // literal and the preview remains unable to create a campaign.
      await command('Page.navigate', { url: `${origin}/${locale}/adventures/relay/create` });
      await waitFor(`document.querySelector('#character-name') !== null`);
      assert.equal(await evaluate(`document.querySelector('#character-name').labels[0].textContent`), italian ? 'Nome dello specialista' : 'Specialist name');
      await evaluate(`document.querySelector('button[type=submit]').click()`);
      await waitFor(`document.querySelector('[role=alert]') !== null`);
      assert.equal(await evaluate(`document.querySelector('[role=alert]').textContent`), italian ? 'Inserisci un nome da 1 a 60 caratteri.' : 'Enter a name of 1–60 characters.');
      await evaluate(`(/** Enter a literal markup-looking name; native input errors reject the receipt check. */ () => {
        const field = document.querySelector('#character-name');
        field.value = '<b>Alex</b>';
        field.dispatchEvent(new Event('input', { bubbles: true }));
      })()`);
      await evaluate(`document.querySelector('button[type=submit]').click()`);
      await waitFor(`document.querySelector('[role=status]') !== null`);
      assert.equal(await evaluate(`document.querySelector('[role=status]').textContent`), italian ? '<b>Alex</b> è pronto per l’anteprima. Nessuna campagna è stata creata.' : '<b>Alex</b> is ready to preview. No campaign has been created.');
      assert.equal(await evaluate(`document.querySelector('[role=status] b') === null`), true);
      await command('Page.navigate', { url: `${origin}/${locale}/interface-kit` });
      await waitFor(`document.querySelector('.graphic-kit') !== null`);
      assert.equal(await evaluate(`document.querySelector('#kit-error-help').textContent`), italian ? 'Inserisci un valore supportato.' : 'Enter a supported value.');
      assert.ok((await evaluate(`document.querySelector('.item-frame').textContent`)).includes(italian ? 'non comune' : 'Uncommon'));
      await evaluate(`document.querySelector('button[aria-pressed]').click()`);
      await waitFor(`document.querySelector('button[aria-pressed]').textContent.includes(${JSON.stringify(italian ? 'Selezionato' : 'Selected')})`);
      await command('Page.navigate', { url: `${origin}/${locale}/adventures/missing/story` });
      await waitFor(`document.querySelector('h1')?.textContent === ${JSON.stringify(italian ? 'Avventura non disponibile' : 'Adventure unavailable')}`);
      // Compare actual HTTP bytes with the production files, including notices;
      // a misleading HTML fallback cannot pass a hash comparison.
      const html = await readFile(resolve(localizedRoot, locale, 'index.html'), 'utf8');
      const assets = [...html.matchAll(/(?:src|href)="([^"]+\.(?:js|css))"/g)].map(
        /** Retain compiler-selected filenames rather than assuming a stable chunk name. */ (match) => match[1]);
      assert.ok(assets.length > 0);
      for (const asset of [...assets, 'ui/README.md', 'ui/LICENSE.txt']) {
        const response = await fetch(`${origin}/${locale}/${asset}`);
        assert.equal(response.status, 200);
        const served = Buffer.from(await response.arrayBuffer());
        const expected = await readFile(resolve(localizedRoot, locale, asset));
        assert.equal(createHash('sha256').update(served).digest('hex'), createHash('sha256').update(expected).digest('hex'));
      }
      for (const absent of ['missing.js', 'ui/missing.svg', 'api/v1/missing', 'unknown-screen']) {
        assert.equal((await fetch(`${origin}/${locale}/${absent}`)).status, 404);
      }
    }
  }
  assert.deepEqual(exceptions, []);
  assert.equal(requests.some(/** Reject world/API writes or remote media requests during preview journeys. */ (request) => request.method !== 'GET' || !request.url.startsWith(origin + '/')), false);
  console.log(`Browser smoke passed: ${version.product}; responsive reading/drafts/recovery and graphic kit${reviewLocales ? ', EN/IT story/plurals/forms/receipts/fallbacks and resource hashes/404s' : ''}; no page exceptions or nonlocal/action requests.`);
} finally {
  browser.kill();
  await new Promise(/** Release the loopback listener before completing; no background server survives. */ (accept) => server.close(accept));
  // Remove only the freshly created contained browser profile, never any pre-existing owner data.
  if (profile.startsWith(resolve(artifacts) + sep)) await rm(profile, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
}
