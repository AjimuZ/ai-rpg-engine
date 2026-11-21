/**
 * QA-25 real-browser accessibility and viewport check of the unlocalized production build (P17.4-A).
 *
 * It serves `frontend/dist/engine-ui/browser` on loopback (every `/api/` request answers 404, so each screen shows its own
 * no-server state) and drives an installed Chrome through the private tools in `.agents/ralph/browser/`. For every routed screen it
 * runs five profiles: desktop (1280x800), narrow (390x844), reflow (320x256 CSS pixels, what a 400% browser zoom of a 1280x1024
 * window leaves), reduced motion (the user preference emulated), and enlarged text (root font size 200% and then the WCAG text-spacing
 * overrides). Each profile checks axe, horizontal overflow and clipped text; desktop, narrow and reduced motion also walk the page by
 * keyboard (skip link, one visible focus indicator per stop, every tabbable control reached, no trap). The accessibility tree of each
 * screen is printed as role and name pairs for the record.
 *
 * Usage: `node frontend/tools/accessibility-check.mjs [screenshot-directory]` after `npm run build`.
 * Exit status: 0 when every printed check holds; 1 when one failed; 2 when the browser cannot start.
 * What it does not establish: any screen-reader output (the accessibility tree is what assistive technology is offered, not what it
 * says), real browser zoom (the viewport is emulated at the equivalent CSS width), the Italian build, the live campaign screen with real
 * narration, a human visual or contrast judgment beyond axe's color-contrast rule, Firefox or Safari, touch devices.
 */
import { createServer } from 'node:http';
import { mkdirSync, readFileSync } from 'node:fs';
import { extname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { withPage, axeReport, accessibilitySnapshot } from '../../.agents/ralph/browser/browser.mjs';

const root = fileURLToPath(new URL('../dist/engine-ui/browser/', import.meta.url));
const shots = process.argv[2] ? resolve(process.argv[2]) : null;
if (shots) mkdirSync(shots, { recursive: true });

const SCREENS = ['/library', '/settings', '/interface-kit', '/adventures/mosslight/story', '/adventures/mosslight/create',
  '/adventures/mosslight/sheet', '/adventures/mosslight/inventory', '/adventures/mosslight/journal', '/adventures/mosslight/guide',
  '/adventures/mosslight/creative', '/saved/00000000-0000-4000-8000-00000000000a'];
const TABBABLE = 'a[href],button:not([disabled]),input:not([disabled]):not([type=hidden]),select:not([disabled]),textarea:not([disabled]),summary,[tabindex]:not([tabindex="-1"])';

let failed = false;
let passes = 0;
let failures = 0;
const verbose = process.env.A11Y_VERBOSE === '1';
/** Count one named check; failures always print, passes only with A11Y_VERBOSE=1, and the totals print at the end. */
const check = (name, ok, detail) => {
  if (ok) passes++; else { failed = true; failures++; }
  if (!ok || verbose) console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${ok ? '' : ' :: ' + JSON.stringify(detail).slice(0, 700)}`);
};

/** Serve build files; extensionless paths get the single-page entry so routed URLs load directly. */
const server = createServer((request, response) => {
  try {
    const pathname = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
    if (pathname.startsWith('/api/')) { response.writeHead(404, { 'Content-Type': 'application/json' }).end('{"code":"NOT_FOUND"}'); return; }
    const requested = resolve(root, '.' + pathname);
    if (!requested.startsWith(root.endsWith(sep) ? root : root + sep)) { response.writeHead(403).end(); return; }
    const file = extname(requested) ? requested : resolve(root, 'index.html');
    const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png' };
    const data = readFileSync(file);
    response.writeHead(200, { 'Content-Type': types[extname(file)] ?? 'application/octet-stream' }).end(data);
  } catch { response.writeHead(404).end(); }
});
await new Promise((accept, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', accept); });
const origin = `http://127.0.0.1:${server.address().port}`;

/** Open one screen and wait until the routed heading exists. Returns the console errors seen while loading. */
async function openScreen(page, path) {
  const errors = [];
  const onConsole = (message) => { if (message.type() === 'error') errors.push(message.text().slice(0, 160)); };
  page.on('console', onConsole);
  page.on('pageerror', (error) => errors.push(String(error).slice(0, 160)));
  await page.goto(origin + path, { waitUntil: 'networkidle' });
  await page.waitForSelector('h1');
  page.off('console', onConsole);
  return errors;
}

/** Horizontal overflow and clipped text, measured in the page. Deliberate clamps (read-back, artwork frames) are excluded. */
const page_measure = () => {
  const clipped = [];
  for (const element of document.querySelectorAll('body *')) {
    if (element.matches('img,svg,.scene,.scene *,.portrait-frame,.portrait-frame *,.previous,.adventure-card,.adventure-card img,option')) continue;
    const style = getComputedStyle(element);
    if (style.display === 'none' || style.visibility === 'hidden') continue;
    const clips = ['hidden', 'clip'].includes(style.overflowX) || ['hidden', 'clip'].includes(style.overflowY);
    if (clips && (element.scrollWidth > element.clientWidth + 1 || element.scrollHeight > element.clientHeight + 1) && element.textContent.trim()) {
      clipped.push(`${element.tagName.toLowerCase()}.${element.className || ''}`.slice(0, 60));
    }
  }
  return { overflow: document.documentElement.scrollWidth > innerWidth, scrollWidth: document.documentElement.scrollWidth, innerWidth, clipped: clipped.slice(0, 8) };
};

/** Walk the page with Tab: skip link first, a visible focus indicator at each stop, every tabbable control reached, no trap. */
async function keyboardWalk(page, tag) {
  await page.evaluate(() => { document.activeElement?.blur(); window.scrollTo(0, 0); });
  const stops = [];
  for (let step = 0; step < 80; step++) {
    await page.keyboard.press('Tab');
    const stop = await page.evaluate(() => {
      const element = document.activeElement;
      if (!element || element === document.body || element === document.documentElement) return null;
      element.dataset.walked = '1';
      const style = getComputedStyle(element);
      const rect = element.getBoundingClientRect();
      const indicator = (style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) > 0) || style.boxShadow !== 'none';
      const reachable = rect.width > 0 && rect.height > 0 && rect.bottom > 0 && rect.right > 0 && rect.top < innerHeight && rect.left < innerWidth;
      return { label: `${element.tagName.toLowerCase()}${element.id ? '#' + element.id : ''}`, indicator, reachable, first: element.classList.contains('skip-link') };
    });
    if (!stop) break;
    stops.push(stop);
  }
  const missed = await page.evaluate((selector) => [...document.querySelectorAll(selector)]
    .filter((element) => element.offsetParent !== null && getComputedStyle(element).visibility !== 'hidden' && !element.dataset.walked)
    .map((element) => element.tagName.toLowerCase() + (element.id ? '#' + element.id : '')), TABBABLE);
  check(`${tag}: the first Tab stop is the skip link`, stops[0]?.first === true, stops.slice(0, 2));
  check(`${tag}: every one of ${stops.length} Tab stops shows a focus indicator and is on screen`, stops.every((stop) => stop.indicator && stop.reachable), stops.filter((stop) => !stop.indicator || !stop.reachable));
  check(`${tag}: Tab leaves the page after ${stops.length} stops (no trap) and reaches every tabbable control`, stops.length < 80 && missed.length === 0, { stops: stops.length, missed });
  await page.evaluate(() => document.querySelectorAll('[data-walked]').forEach((element) => delete element.dataset.walked));
  return stops.length;
}

/** The skip link moves focus into main, so the next Tab starts inside the content. */
async function skipLink(page, tag, path) {
  await page.evaluate(() => { document.activeElement?.blur(); });
  const before = new URL(page.url()).pathname;
  await page.keyboard.press('Tab');
  await page.keyboard.press('Enter');
  await page.waitForTimeout(400);
  await page.waitForLoadState('networkidle');
  const after = new URL(page.url());
  const inMain = await page.evaluate(() => document.activeElement?.id === 'main' || !!document.activeElement?.closest('main'));
  check(`${tag}: Enter on the skip link moves focus into the main content and stays on the screen`, inMain && after.pathname === before && after.hash === '', { before, after: after.pathname + after.hash, inMain });
  // Reload the screen: the sequential focus start point now sits inside main, and a failed skip link may have navigated away.
  await openScreen(page, path);
}

const PROFILES = [
  { name: 'desktop 1280x800', viewport: { width: 1280, height: 800 }, walk: true, skip: true },
  { name: 'narrow 390x844', viewport: { width: 390, height: 844 }, walk: true },
  { name: 'reflow 320x256 (400% zoom equivalent)', viewport: { width: 320, height: 256 } },
  { name: 'reduced motion 1280x800', viewport: { width: 1280, height: 800 }, reducedMotion: 'reduce', walk: true, motion: true },
  { name: 'text 200% then WCAG text spacing', viewport: { width: 1280, height: 800 }, text: true },
];

for (const profile of PROFILES) {
  await withPage(async (page) => {
    for (const path of SCREENS) {
      const tag = `${profile.name} ${path.replace('/adventures/mosslight', '').replace(/\/saved\/.*/, '/saved')}`;
      const errors = await openScreen(page, path);
      // Chrome's /api and /favicon.ico requests answer 404 on this server: those are the only tolerated console errors.
      check(`${tag}: no console or page error besides the stand-in server's 404 answers`, errors.every((text) => text.includes('status of 404')), errors);
      check(`${tag}: one h1, one main landmark, document language set`, await page.evaluate(() => document.querySelectorAll('h1').length === 1
        && document.querySelectorAll('main').length === 1 && document.documentElement.lang === 'en'), {});
      if (profile.text) {
        await page.addStyleTag({ content: 'html { font-size: 200% !important; }' });
        const grown = await page.evaluate(page_measure);
        check(`${tag}: root text 200% leaves no horizontal overflow or clipped text`, !grown.overflow && grown.clipped.length === 0, grown);
        check(`${tag}: axe reports no violation at root text 200%`, (await axeReport(page)).length === 0, await axeReport(page));
        await page.addStyleTag({ content: '* { line-height: 1.5 !important; letter-spacing: .12em !important; word-spacing: .16em !important; } p { margin-bottom: 2em !important; }' });
        const spaced = await page.evaluate(page_measure);
        check(`${tag}: text spacing overrides leave no horizontal overflow or clipped text`, !spaced.overflow && spaced.clipped.length === 0, spaced);
        continue;
      }
      const sizes = await page.evaluate(page_measure);
      check(`${tag}: no horizontal overflow and no clipped text`, !sizes.overflow && sizes.clipped.length === 0, sizes);
      const violations = await axeReport(page);
      check(`${tag}: axe reports no violation`, violations.length === 0, violations);
      if (profile.motion) {
        const motion = await page.evaluate(() => ({ reduced: matchMedia('(prefers-reduced-motion: reduce)').matches, running: document.getAnimations().length, smooth: getComputedStyle(document.documentElement).scrollBehavior }));
        check(`${tag}: the reduced-motion preference is honored, with no running animation and no smooth scrolling`, motion.reduced && motion.running === 0 && motion.smooth !== 'smooth', motion);
      }
      if (profile.skip) await skipLink(page, tag, path);
      if (profile.walk) await keyboardWalk(page, tag);
      if (shots && path === '/adventures/mosslight/story') await page.screenshot({ path: `${shots}/${profile.name.split(' ')[0]}-story.png`, fullPage: true });
    }
  }, { viewport: profile.viewport, reducedMotion: profile.reducedMotion ?? 'no-preference' }).catch((error) => {
    console.error(`The browser check could not run: ${error.message}`);
    process.exitCode = 2;
  });
}

// Accessibility tree of each screen (roles and names offered to assistive technology; this is NOT a screen-reader review).
await withPage(async (page) => {
  for (const path of SCREENS) {
    await openScreen(page, path);
    const tree = await accessibilitySnapshot(page);
    const roles = {};
    for (const node of tree) roles[node.role] = (roles[node.role] ?? 0) + 1;
    const unnamed = await page.evaluate((selector) => [...document.querySelectorAll(selector)].filter((element) => element.offsetParent !== null
      && !(element.getAttribute('aria-label') || element.getAttribute('aria-labelledby') || element.textContent.trim() || element.title
        || (element.labels && element.labels.length) || element.getAttribute('alt'))).map((element) => element.outerHTML.slice(0, 80)), TABBABLE);
    // The main landmark has no accessible name, so the named-node tree omits it; the DOM checks above count it.
    check(`tree ${path}: ${tree.length} named nodes (${Object.entries(roles).map(([role, count]) => `${role} ${count}`).join(', ')}); every control has a name`,
      unnamed.length === 0 && (roles.heading ?? 0) >= 1 && (roles.navigation ?? 0) >= 1, unnamed);
    if (verbose) console.log(`  roles ${path}: ${JSON.stringify(roles)}`);
  }
}, { viewport: { width: 1280, height: 800 } }).catch((error) => { console.error(`The browser check could not run: ${error.message}`); process.exitCode = 2; });

server.close();
console.log(`${passes} PASS, ${failures} FAIL`);
process.exit(process.exitCode === 2 ? 2 : failed ? 1 : 0);
