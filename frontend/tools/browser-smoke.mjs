/** Smoke-test the built preview in an installed Chromium browser; exit nonzero on any failed journey. */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { resolve, sep, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../dist/engine-ui/browser/', import.meta.url));
const artifacts = fileURLToPath(new URL('../../.local/scratch/', import.meta.url));
await mkdir(artifacts, { recursive: true });
const profile = await mkdtemp(resolve(artifacts, 'ui-smoke-'));
const browserPath = process.env['BROWSER_PATH'] ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe';

/** Serve production files only from the build directory; unknown extensionless routes use the SPA entry. */
const server = createServer(async (request, response) => {
  try {
    const pathname = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
    const requested = resolve(root, '.' + pathname);
    if (!requested.startsWith(root.endsWith(sep) ? root : root + sep) && requested !== resolve(root)) {
      response.writeHead(403).end();
      return;
    }
    const file = extname(requested) ? requested : resolve(root, 'index.html');
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
  await evaluate(`document.querySelector('summary').click()`);
  assert.equal(await evaluate(`document.querySelector('details').open`), true);
  await writeFile(resolve(artifacts, 'p052-desktop.png'), Buffer.from((await command('Page.captureScreenshot')).data, 'base64'));
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
  await writeFile(resolve(artifacts, 'p052-mobile.png'), Buffer.from((await command('Page.captureScreenshot')).data, 'base64'));
  await clickLink('Crew profile', '/adventures/relay/sheet');
  assert.equal(await evaluate(`document.documentElement.scrollWidth <= innerWidth`), true);
  await writeFile(resolve(artifacts, 'p052-metadata-mobile.png'), Buffer.from((await command('Page.captureScreenshot')).data, 'base64'));
  await command('Page.navigate', { url: origin + '/adventures/missing/story' });
  await waitFor(`document.querySelector('h1')?.textContent === 'Adventure unavailable'`);
  assert.deepEqual(exceptions, []);
  assert.equal(requests.some(/** Reject world/API writes or remote media requests during preview journeys. */ (request) => request.method !== 'GET' || !request.url.startsWith(origin + '/')), false);
  console.log(`Browser smoke passed: ${version.product}; desktop/mobile navigation, metadata/resources, optional/disabled panels, reading, form, settings, unknown identity; no page exceptions or nonlocal/action requests.`);
} finally {
  browser.kill();
  await new Promise(/** Release the loopback listener before completing; no background server survives. */ (accept) => server.close(accept));
  // Remove only the freshly created contained browser profile, never any pre-existing owner data.
  if (profile.startsWith(resolve(artifacts) + sep)) await rm(profile, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
}
