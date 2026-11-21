/**
 * Real-browser journey for item artwork, rarity frames and credits (P17.1-B) against the PACKAGED installation.
 *
 * It needs `./start.sh` running (http://127.0.0.1:8080) and the private browser tools in `.agents/ralph/browser/`.
 * It never starts a stand-in server: every request goes to the engine in the containers. Phases, run one at a time:
 *   create                          create one playthrough through the screens; print its identities and items
 *   view LOCALE ADVENTURE CAMPAIGN SHOTDIR art|none
 *                                   (none: expect built-in icons and the no-artwork credits note. art: expect the bundled
 *                                   sample's authored sword icon, bound by its presentation file (P17.1-C), on every row
 *                                   of the `sword` definition, the other items on the built-in icon, and the sample's credit
 *                                   line. Rows are never inserted by hand.)
 *                                   open the playthrough at 390x844, 1280x800 and 390x844 with reduced motion; print
 *                                   art, frame and tier of every item row, credits, axe contrast and violations
 *   equip ADVENTURE CAMPAIGN ITEM equip|unequip
 *                                   submit that command through the engine's own route and print the worn items
 * Usage: node frontend/tools/artwork-journey.mjs PHASE ARGS...
 * Exit status: 0 when every printed check holds; 1 when one failed; 2 for bad usage.
 */
import { mkdirSync, readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { join } from 'node:path';
import { withPage } from '../../.agents/ralph/browser/browser.mjs';

const ORIGIN = 'http://127.0.0.1:8080';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const require = createRequire(new URL('../../.agents/ralph/browser/package.json', import.meta.url));
const AXE_SOURCE = readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8');
const [phase, ...args] = process.argv.slice(2);
let failed = false;
const check = (name, ok, detail) => {
  if (!ok) failed = true;
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${ok || detail === undefined ? '' : ' :: ' + JSON.stringify(detail).slice(0, 600)}`);
};

/** Summarize every inventory row and worn slot: art kind, frame token, image file name and the written tier. */
const summarize = () => {
  const one = (row) => ({
    key: row.getAttribute('data-item') ?? row.getAttribute('data-slot'),
    art: row.querySelector('.art-frame')?.getAttribute('data-art') ?? null,
    frame: row.querySelector('.art-frame')?.getAttribute('data-frame') ?? null,
    src: (row.querySelector('img')?.getAttribute('src') ?? '').split('/').pop(),
    tier: (row.querySelector('[data-rarity]')?.textContent ?? '').trim() });
  return { items: [...document.querySelectorAll('[data-item]')].map(one), worn: [...document.querySelectorAll('[data-slot]')].map(one) };
};

/** WCAG contrast ratio of two CSS rgb() strings, measured in the page's own computed colors. */
const ratio = (a, b) => {
  const lum = (css) => {
    const [r, g, bl] = css.match(/[\d.]+/g).slice(0, 3).map((v) => { const c = Number(v) / 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; });
    return 0.2126 * r + 0.7152 * g + 0.0722 * bl;
  };
  const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
  return Math.round(((hi + 0.05) / (lo + 0.05)) * 100) / 100;
};

/** Run axe and return violations, plus how many nodes the color-contrast rule passed and which it left for review. */
async function axeContrast(page) {
  await page.evaluate(AXE_SOURCE);
  return page.evaluate(async () => {
    const r = await globalThis.axe.run();
    const count = (list, id) => list.filter((v) => v.id === id).reduce((n, v) => n + v.nodes.length, 0);
    return { violations: r.violations.map((v) => ({ id: v.id, impact: v.impact, nodes: v.nodes.map((n) => n.target.join(' ')).slice(0, 8) })),
      contrastPassedNodes: count(r.passes, 'color-contrast'), contrastViolatingNodes: count(r.violations, 'color-contrast'),
      contrastIncompleteNodes: count(r.incomplete, 'color-contrast'), rulesPassed: r.passes.length };
  });
}

/** Resolve once the packaged page shows the playthrough panels and its credits section. */
async function open(page, locale, adventureId, campaignId) {
  await page.goto(`${ORIGIN}/${locale}/saved/${adventureId}?playthrough=${campaignId}`);
  await page.waitForSelector('[data-credits]', { timeout: 30000 });
  // After a committed command the page may keep polling for narration that the unsigned runtime never delivers.
  await page.waitForLoadState('networkidle', { timeout: 5000 }).catch(() => undefined);
  await page.waitForTimeout(1000);
}

if (phase === 'create') {
  await withPage(async (page) => {
    const problems = [];
    page.on('pageerror', (e) => problems.push(String(e)));
    await page.goto(`${ORIGIN}/en/library`, { waitUntil: 'networkidle' });
    const href = await page.locator('a[href*="/saved/"]').first().getAttribute('href');
    const adventureId = href.split('/saved/')[1];
    await page.locator('a[href*="/saved/"]').first().click();
    await page.waitForURL(/\/saved\//);
    await page.click(`a[href*="/adventures/${adventureId}/create"]`);
    await page.waitForSelector('#playthrough-title');
    await page.fill('#playthrough-title', 'Artwork check');
    await page.fill('#character-name', 'Mara');
    await page.click('button[type="submit"]');
    await page.waitForURL(/playthrough=/, { timeout: 30000 });
    const campaignId = new URL(page.url()).searchParams.get('playthrough');
    await page.waitForSelector('[data-credits]', { timeout: 30000 });
    const body = await page.evaluate(async ([a, c]) => ({
      campaigns: await (await fetch('/api/v1/campaigns')).json(),
      panels: await (await fetch(`/api/v1/campaigns/${c}/panels?adventureId=${a}`)).json() }), [adventureId, campaignId]);
    const campaign = body.campaigns.campaigns.find((c) => c.campaignId === campaignId);
    console.log(JSON.stringify({ adventureId, campaignId, contentRevisionId: campaign?.contentRevisionId, stateRevision: campaign?.stateRevision,
      inventory: body.panels.inventory.map((i) => ({ itemId: i.itemId, definitionId: i.definitionId, rarity: i.rarity, wornIn: i.wornIn })),
      equipment: body.panels.equipment.map((e) => ({ itemId: e.itemId, definitionId: e.definitionId, slot: e.slot })), problems }, null, 1));
    check('a playthrough was created through the screens', UUID.test(campaignId ?? '') && UUID.test(campaign?.contentRevisionId ?? ''));
  });
} else if (phase === 'view' && args.length === 5 && ['en', 'it'].includes(args[0]) && UUID.test(args[1]) && UUID.test(args[2]) && ['art', 'none'].includes(args[4])) {
  const [locale, adventureId, campaignId, shots, expect] = args;
  const withArt = expect === 'art';
  mkdirSync(shots, { recursive: true });
  for (const [name, width, height, reduced] of [['phone', 390, 844, false], ['desktop', 1280, 800, false], ['phone-reduced', 390, 844, true]]) {
    await withPage(async (page) => {
      const problems = [];
      const assetRequests = [];
      page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`));
      page.on('console', (m) => { if (m.type() === 'error') problems.push(`console: ${m.text().slice(0, 160)}`); });
      page.on('response', (r) => { if (r.status() >= 400) problems.push(`HTTP ${r.status()} ${r.url().replace(ORIGIN, '')}`); });
      page.on('request', (r) => { if (r.url().includes('/assets/')) assetRequests.push(new URL(r.url()).pathname.split('/').pop()); });
      await open(page, locale, adventureId, campaignId);
      const before = await page.evaluate(summarize);
      await page.reload();
      await page.waitForSelector('[data-credits]', { timeout: 30000 });
      await page.waitForLoadState('networkidle', { timeout: 5000 }).catch(() => undefined);
      await page.waitForTimeout(1000);
      const after = await page.evaluate(summarize);
      // Icons are lazy: one below the fold loads only when scrolled to, so bring each into view (bounded wait) before the
      // decode check, which would otherwise report an image that was simply never requested.
      await page.evaluate(async () => {
        for (const image of document.querySelectorAll('.art-frame img')) {
          image.scrollIntoView({ block: 'center' });
          if (!image.complete) await Promise.race([new Promise((done) => image.addEventListener('load', done, { once: true })), new Promise((done) => setTimeout(done, 5000))]);
        }
        window.scrollTo(0, 0);
      });
      const measured = await page.evaluate(() => {
        const frames = [...document.querySelectorAll('.art-frame')].map((f) => { const s = getComputedStyle(f); return { frame: f.getAttribute('data-frame'), style: s.borderTopStyle, width: s.borderTopWidth, color: s.borderTopColor, behind: (() => { for (let e = f.parentElement; e; e = e.parentElement) { const c = getComputedStyle(e).backgroundColor; if (!/rgba\(.*, 0\)$|transparent/.test(c)) return c; } return 'rgb(255, 255, 255)'; })() }; });
        return { frames, decoded: [...document.querySelectorAll('.art-frame img')].map((i) => i.complete && i.naturalWidth),
          credits: (document.querySelector('[data-credits]')?.textContent ?? '').replace(/\s+/g, ' ').trim(),
          creditsVisible: (document.querySelector('[data-credits]')?.getBoundingClientRect().height ?? 0) > 0,
          overflow: document.documentElement.scrollWidth - window.innerWidth };
      });
      const axe = await axeContrast(page);
      await page.screenshot({ path: join(shots, `packaged-${locale}-${name}.png`), fullPage: true });
      const label = `${locale} ${name}`;
      const borders = measured.frames.map((f) => ({ frame: f.frame, border: f.color, behind: f.behind, ratio: ratio(f.color, f.behind) }));
      console.log(JSON.stringify({ label, items: before.items, worn: before.worn, credits: measured.credits, decoded: measured.decoded, borders: [...new Map(borders.map((b) => [b.frame, b])).values()],
        assetRequests: [...new Set(assetRequests)], axe }, null, 1));
      check(`${label}: credits section visible`, measured.creditsVisible, measured.credits);
      if (withArt) {
        check(`${label}: the sample's credit line is shown`, /make-sample-art\.mjs/.test(measured.credits), measured.credits);
        const swords = before.items.filter((row) => row.key === 'sword');
        check(`${label}: every sword row shows the authored image`, swords.length > 0 && swords.every((row) => row.art === 'image'), before);
        check(`${label}: a worn item shows the same image as the sword row`, before.worn.every((row) => row.art === 'fallback' || row.src === swords[0]?.src), before.worn);
        check(`${label}: items without authored art keep the built-in icon`, before.items.filter((row) => row.key !== 'sword').every((row) => row.art === 'fallback'), before.items);
      }
      else check(`${label}: no image is registered, so every row shows the built-in icon`, before.items.every((i) => i.art !== 'image') && assetRequests.length === 0, { items: before.items, assetRequests });
      check(`${label}: art, frame and tier identical across a reload`, JSON.stringify(before) === JSON.stringify(after));
      if (withArt) {
        check(`${label}: registered images decoded`, measured.decoded.length > 0 && measured.decoded.every((w) => w > 0), measured.decoded);
        check(`${label}: only hash URLs were requested`, assetRequests.length > 0 && assetRequests.every((h) => /^[0-9a-f]{64}(\?|$)/.test(h)), assetRequests);
      }
      check(`${label}: no horizontal overflow`, measured.overflow <= 0, measured.overflow);
      // Chrome reports the favicon's 404 as an unattributed console error that Playwright cannot tie to a URL, as in
      // earlier browser reviews; the page is asked for it directly so the guess is observed, not assumed.
      const favicon = await page.evaluate(async () => (await fetch('/favicon.ico')).status);
      const unattributed = /^console: Failed to load resource: the server responded with a status of 404 \(\)$/;
      const real = problems.filter((p) => !(favicon === 404 && unattributed.test(p)));
      console.log(`NOTE ${label}: /favicon.ico answers ${favicon}; ${problems.length - real.length} unattributed console 404 set aside`);
      check(`${label}: no page error, failed response or other console error`, real.length === 0, real);
      check(`${label}: axe found no violation, color-contrast included`, axe.violations.length === 0 && axe.contrastViolatingNodes === 0, axe.violations);
    }, { viewport: { width, height }, reducedMotion: reduced ? 'reduce' : 'no-preference' });
  }
} else if (phase === 'equip' && args.length === 4 && UUID.test(args[0]) && UUID.test(args[1]) && UUID.test(args[2]) && ['equip', 'unequip'].includes(args[3])) {
  const [adventureId, campaignId, itemId, verb] = args;
  await withPage(async (page) => {
    await open(page, 'en', adventureId, campaignId);
    const before = await page.evaluate(summarize);
    const result = await page.evaluate(async ([a, c, item, type]) => {
      const csrf = (await (await fetch('/api/v1/session')).json()).csrfToken;
      const state = await (await fetch(`/api/v1/campaigns/${c}/state?adventureId=${a}`)).json();
      const response = await fetch(`/api/v1/campaigns/${c}/commands?adventureId=${a}`, { method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf, 'X-XSRF-TOKEN': csrf },
        body: JSON.stringify({ requestId: crypto.randomUUID(), expectedRevision: state.campaign.stateRevision, commandType: type, payload: { itemInstanceId: item } }) });
      return { status: response.status, body: (await response.text()).slice(0, 400) };
    }, [adventureId, campaignId, itemId, verb === 'equip' ? 'EQUIP_ITEM' : 'UNEQUIP_ITEM']);
    console.log(JSON.stringify({ result }));
    await open(page, 'en', adventureId, campaignId);
    const after = await page.evaluate(summarize);
    console.log(JSON.stringify({ before: before.worn, after: after.worn, itemsAfter: after.items }, null, 1));
    check(`the ${verb} command was accepted`, result.status === 200 || result.status === 202, result);
    check('the worn items changed', JSON.stringify(before.worn) !== JSON.stringify(after.worn));
    const definitionRows = (s, key) => s.items.filter((i) => i.key === key);
    const worn = (verb === 'equip' ? after : before).worn;
    check('a worn item shows the same art and frame as its inventory row', worn.length > 0 && worn.every((w) => {
      const row = after.items.find((i) => i.src === w.src && i.frame === w.frame);
      return row !== undefined && definitionRows(after, row.key).length > 0;
    }), { worn, items: after.items });
  });
} else {
  console.error('usage: artwork-journey.mjs create | view en|it ADVENTURE CAMPAIGN SHOTDIR | equip ADVENTURE CAMPAIGN ITEM equip|unequip');
  process.exit(2);
}
process.exit(failed ? 1 : 0);
