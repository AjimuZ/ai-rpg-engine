/**
 * Real-browser QA-32 journey for optional music (P17.2-D) against the PACKAGED installation.
 *
 * It needs `./start.sh` running (http://127.0.0.1:8080), the private browser tools in `.agents/ralph/browser/`, and
 * playthroughs of the bundled sample made through the screens (`node frontend/tools/artwork-journey.mjs create`). It never
 * starts a stand-in server and never inserts rows. Phases, run one at a time:
 *   play LOCALE ADVENTURE CAMPAIGN OTHER_CAMPAIGN SHOTDIR [engage]
 *       deliberate activation, playback, a stable loop, pause/mute/volume, the crossfade to the observed-tension track,
 *       the secret-sentinel checks, a switch to another playthrough and leaving it; `engage` also starts the offered fight
 *       and checks that the pack policy keeps the tension track without a restart (this changes the playthrough for good)
 *   fail decode|source|offline ADVENTURE CAMPAIGN
 *       a track whose bytes cannot be decoded, a refused source, and no network: the panel says so, nothing is retried
 *       by itself, the story stays usable, and (offline) the explicit retry plays once the network is back
 *   adventures [en|it]
 *       both bundled samples (ADR 0055) appear in the English and Italian library; one playthrough of each is created
 *       through the screens of that locale (default en) and opened, and neither playthrough can be read under the other
 *       adventure; prints the IDs
 *   switch LOCALE ADVENTURE_A CAMPAIGN_A ADVENTURE_B CAMPAIGN_B
 *       music on in a playthrough of A, then a switch to a playthrough of B: A's element is paused within 150 ms, B reads
 *       its own listing once and plays its own exploration track, and no hidden detail of either adventure appears
 * The page's `Audio` constructor is wrapped by an init script only to observe the elements the player creates (URL,
 * paused, time, volume, loop, errors); the player itself is unchanged. Headless Chrome has no audible output, so what is
 * measured is media state, not sound.
 * Usage: node frontend/tools/music-journey.mjs PHASE ARGS...
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
const PUBLIC_STATES = ['quiet', 'offered', 'fight', 'won', 'lost', 'settled'];
/**
 * Hidden details of the sample's waiting encounter (its template, participant, attack, objectives, terms, zones and
 * stats). None may appear in the music listing, the music panel, a media address or the saved music preference.
 */
const SENTINELS = ['ford-ambush', 'raider', 'cudgel', 'drive-off', 'slip-away', 'shared-crossing', 'reeds', 'stepping', '"health"', 'guard',
  // The same details of the second sample's waiting encounter (ADR 0055).
  'lane-standoff', 'enforcer', 'shock-baton', 'clear-lane', 'offer-toll', 'paid-passage', 'cargo bins'];
const [phase, ...args] = process.argv.slice(2);
let failed = false;
const check = (name, ok, detail) => {
  if (!ok) failed = true;
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${ok || detail === undefined ? '' : ' :: ' + JSON.stringify(detail).slice(0, 700)}`);
};
const wait = (ms) => new Promise((done) => setTimeout(done, ms));

/** Record every element the page creates through `new Audio(url)`, with its error count; runs before the app. */
function observeAudio() {
  const Native = window.Audio;
  const made = [];
  window.__music = made;
  window.Audio = function (url) {
    const element = new Native(url);
    const record = { url: String(url), element, errors: 0 };
    element.addEventListener('error', () => { record.errors += 1; });
    made.push(record);
    return element;
  };
  window.Audio.prototype = Native.prototype;
}

/** Media state of every created element, in creation order. */
const sample = (page) => page.evaluate(() => window.__music.map((m) => ({
  url: m.url.replace(location.origin, ''), paused: m.element.paused, time: Math.round(m.element.currentTime * 1000) / 1000,
  duration: Number.isFinite(m.element.duration) ? Math.round(m.element.duration * 1000) / 1000 : null,
  volume: Math.round(m.element.volume * 1000) / 1000, loop: m.element.loop, ready: m.element.readyState,
  error: m.element.error?.code ?? null, errors: m.errors })));

const panel = (page) => page.evaluate(() => {
  const root = document.querySelector('[data-music]');
  return { status: root?.querySelector('[data-music-status]')?.textContent?.trim() ?? null,
    title: root?.querySelector('[data-music-title]')?.textContent?.trim() ?? null,
    source: root?.querySelector('[data-music-source]')?.textContent?.trim() ?? null,
    html: root?.outerHTML ?? '', text: root?.textContent ?? '',
    storyUsable: document.querySelector('#campaign-input') !== null && document.querySelector('[data-credits]') !== null };
});

/** Open the playthrough, recording media and music-route traffic, and return those records. */
async function open(page, locale, adventureId, campaignId) {
  const traffic = { media: [], listings: [] };
  page.on('request', (r) => { const url = r.url(); if (url.includes('/assets/') || url.includes('wikimedia')) traffic.media.push(url.replace(ORIGIN, '')); });
  page.on('response', async (r) => { if (r.url().includes('/music?')) traffic.listings.push({ status: r.status(), body: await r.text().catch(() => '') }); });
  await page.addInitScript(observeAudio);
  await page.goto(`${ORIGIN}/${locale}/saved/${adventureId}?playthrough=${campaignId}`);
  await page.waitForSelector('[data-music] [data-music-status]', { timeout: 30000 });
  await page.waitForLoadState('networkidle', { timeout: 5000 }).catch(() => undefined);
  await page.waitForTimeout(800);
  return traffic;
}

const leaks = (text) => SENTINELS.filter((word) => text.toLowerCase().includes(word));

if (phase === 'play' && args.length >= 5 && ['en', 'it'].includes(args[0]) && UUID.test(args[1]) && UUID.test(args[2]) && UUID.test(args[3])) {
  const [locale, adventureId, campaignId, otherId, shots, engage] = args;
  mkdirSync(shots, { recursive: true });
  await withPage(async (page) => {
    const problems = [];
    page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`));
    page.on('console', (m) => { if (m.type() === 'error') problems.push(`console: ${m.text().slice(0, 160)}`); });
    const traffic = await open(page, locale, adventureId, campaignId);

    // 1. Delivery of the pack policy: one listing, closed keys, the pack's own tracks.
    const listing = traffic.listings[0];
    const body = listing ? JSON.parse(listing.body) : null;
    console.log(JSON.stringify({ listing: body }, null, 1));
    check('the music listing was read once with 200', traffic.listings.length === 1 && listing.status === 200, traffic.listings);
    check('the policy names only public states', body?.policy !== null && Object.keys(body.policy).every((key) => PUBLIC_STATES.includes(key)), body?.policy);
    check('the pack ships its own exploration and tension tracks addressed by hash', body?.tracks?.length === 2
      && body.tracks.every((t) => /^[0-9a-f]{64}$/.test(t.sha256) && t.mediaType === 'audio/wav') && body.tracks.map((t) => t.mood).join() === 'exploration,tension', body?.tracks);

    // 2. Deliberate activation: nothing is created or fetched before the click.
    const before = await sample(page);
    check('no audio element and no media request before the click', before.length === 0 && traffic.media.length === 0, { before, media: traffic.media });
    check('the panel says music is off', (await panel(page)).status !== null && (await page.$('[data-music-on]')) !== null, await panel(page));
    await page.click('[data-music-on]');
    await page.waitForTimeout(2500);
    let state = await sample(page);
    const shown = await panel(page);
    console.log(JSON.stringify({ afterClick: state, panel: { status: shown.status, title: shown.title, source: shown.source } }, null, 1));
    const dusk = body.tracks[0];
    check('one element plays the exploration track from the engine asset route', state.length === 1 && state[0].url.includes(`/assets/${dusk.sha256}?adventureId=${adventureId}`)
      && !state[0].paused && state[0].loop && state[0].error === null, state);
    check('the current-track view names the pack track and its source text', shown.title === dusk.title && shown.source === dusk.source, shown);
    check('no request reached the online host', traffic.media.every((url) => !url.includes('wikimedia')), traffic.media);

    // 3. Playback and a stable loop: time advances, wraps at the end, one element, no error.
    const times = [];
    for (let i = 0; i < 50; i++) { times.push((await sample(page))[0]); await wait(200); }
    const wraps = times.slice(1).filter((s, i) => s.time + 1 < times[i].time).length;
    const advancing = times.slice(1).filter((s, i) => s.time > times[i].time).length;
    console.log(JSON.stringify({ duration: times[0].duration, first: times[0].time, last: times.at(-1).time, wraps, advancing, samples: times.length }));
    check('playback time advances', advancing > 30, { advancing });
    check('the loop wrapped at least once over 10 s without pausing or a new element', wraps >= 1 && times.every((s) => !s.paused && s.error === null) && (await sample(page)).length === 1, { wraps, duration: times[0].duration });
    // Loop lengths written in tools/sample-music/make-sample-music.mjs.
    const loop = { 'Ford at Dusk': 8, 'Neon Rain': 6 }[dusk.title];
    check(`the decoded duration is the ${loop} s loop`, Math.abs((times[0].duration ?? 0) - loop) < 0.05, times[0].duration);

    // 4. Pause, resume, mute and volume.
    await page.click('[data-music-pause]');
    await page.waitForTimeout(300);
    check('pause stops the element in place', (await sample(page)).every((s) => s.paused), await sample(page));
    await page.click('[data-music-resume]');
    await page.waitForTimeout(500);
    check('resume plays the same element again', (await sample(page)).length === 1 && !(await sample(page))[0].paused, await sample(page));
    await page.click('[data-music-mute]');
    await page.waitForTimeout(300);
    check('mute sets the element volume to 0', (await sample(page))[0].volume === 0, await sample(page));
    await page.click('[data-music-mute]');
    await page.locator('[data-music-volume]').evaluate((input) => { input.value = '20'; input.dispatchEvent(new Event('input', { bubbles: true })); });
    await page.waitForTimeout(300);
    check('the music volume slider sets 0.2', (await sample(page))[0].volume === 0.2, await sample(page));

    // 5. Secret sentinel: the encounter waiting at this location is not observed yet, so the music must not react to it.
    const quiet = await panel(page);
    const stored = await page.evaluate(() => localStorage.getItem('engine.music.v1') ?? '');
    check('before the player looks for fights the exploration track stays (no reaction to the waiting encounter)', quiet.title === dusk.title && (await sample(page)).length === 1, quiet.title);
    check('no hidden encounter detail in the listing, the panel, a media address or the saved preference',
      leaks(listing.body + quiet.html + traffic.media.join(' ') + stored).length === 0, leaks(listing.body + quiet.html + traffic.media.join(' ') + stored));

    // 6. Observed tension: the player asks, the offer is shown, the pack policy maps `offered` to tension, and it crossfades.
    await page.locator('[data-music-volume]').evaluate((input) => { input.value = '50'; input.dispatchEvent(new Event('input', { bubbles: true })); });
    await page.click('[data-look-for-fights]');
    const fade = [];
    for (let i = 0; i < 35; i++) { fade.push(await sample(page)); await wait(100); }
    const last = fade.at(-1);
    const old = fade.map((s) => s[0]);
    const fresh = fade.map((s) => s[1]).filter(Boolean);
    const stones = body.tracks[1];
    const audible = Math.max(...fade.map((s) => s.filter((e) => !e.paused).length));
    console.log(JSON.stringify({ oldVolumes: old.map((s) => s.volume), newVolumes: fresh.map((s) => s.volume), audible }));
    check('the offer became visible to the player', (await page.$('[data-offers]')) !== null);
    check('a second element plays the tension track', last.length === 2 && last[1].url.includes(`/assets/${stones.sha256}?`) && !last[1].paused, last);
    check('the old track fades out to 0 and stops; the new one fades in to the music volume', old.every((s, i) => i === 0 || s.volume <= old[i - 1].volume)
      && last[0].volume === 0 && last[0].paused && fresh.every((s, i) => i === 0 || s.volume >= fresh[i - 1].volume) && fresh[0].volume < 0.2 && last[1].volume === 0.5,
    { old: old.map((s) => s.volume), fresh: fresh.map((s) => s.volume) });
    check('at most two sources were audible during the crossfade', audible <= 2, audible);
    const tense = await panel(page);
    check('the current-track view names the tension track', tense.title === stones.title, tense.title);
    check('after the offer is observed, still no hidden detail in the panel or media addresses', leaks(tense.html + traffic.media.join(' ')).length === 0, leaks(tense.html + traffic.media.join(' ')));
    await page.screenshot({ path: join(shots, `music-${locale}-desktop.png`), fullPage: true });
    await page.evaluate(AXE_SOURCE);
    const axe = await page.evaluate(async () => (await globalThis.axe.run()).violations.map((v) => ({ id: v.id, nodes: v.nodes.length })));
    check('axe found no violation with music playing', axe.length === 0, axe);

    if (engage === 'engage') {
      // 7. Fight under the pack policy: `fight` maps to tension, so the same element keeps playing and nothing restarts.
      const count = (await sample(page)).length;
      await page.click('[data-entry]');
      await page.click('[data-entry-confirm]');
      await page.waitForSelector('[data-offers]', { state: 'detached', timeout: 30000 }).catch(() => undefined);
      await page.waitForTimeout(3000);
      const fight = await sample(page);
      const fightPanel = await panel(page);
      console.log(JSON.stringify({ fight, title: fightPanel.title, inFight: await page.evaluate(() => document.querySelector('[data-offers]') === null) }));
      check('in the fight the tension track keeps playing without a new element (pack policy fight -> tension)', fight.length === count && !fight.at(-1).paused && fightPanel.title === stones.title, fight);
      check('the fight adds no hidden detail to the panel or media addresses', leaks(fightPanel.html + traffic.media.join(' ')).length === 0, leaks(fightPanel.html));
    }

    // 8. Switch to another playthrough: the old scope is silenced at once, the new scope chooses its own track.
    const playing = (await sample(page)).filter((s) => !s.paused).length;
    await page.evaluate((url) => { history.pushState(null, '', url); dispatchEvent(new PopStateEvent('popstate', { state: null })); }, `/${locale}/saved/${adventureId}?playthrough=${otherId}`);
    await page.waitForTimeout(150);
    const atSwitch = await sample(page);
    await page.waitForSelector(`.campaign-playthrough[data-campaign-id="${otherId}"]`, { timeout: 30000 });
    await page.waitForTimeout(2500);
    const afterSwitch = await sample(page);
    console.log(JSON.stringify({ playingBefore: playing, atSwitch, afterSwitch, title: (await panel(page)).title }));
    const previous = atSwitch.filter((s) => s.url.includes(`/campaigns/${campaignId}/`));
    check('within 150 ms of the switch every element of the previous playthrough is paused', playing >= 1 && previous.length > 0 && previous.every((s) => s.paused), atSwitch);
    const newer = afterSwitch.filter((s) => !s.url.includes(`/campaigns/${campaignId}/`));
    check('the other playthrough plays its own exploration track, once, and nothing else was created', newer.length === 1 && newer[0].url.includes(`/campaigns/${otherId}/assets/${dusk.sha256}?`) && !newer[0].paused, newer);
    await page.evaluate((url) => { history.pushState(null, '', url); dispatchEvent(new PopStateEvent('popstate', { state: null })); }, `/${locale}/library`);
    await page.waitForTimeout(500);
    check('leaving the playthrough stops all music', (await sample(page)).every((s) => s.paused), await sample(page));
    check('no element reported a media error', (await sample(page)).every((s) => s.errors === 0), await sample(page));
    check('over the whole visit no element and no request used the online host', traffic.media.every((url) => !url.includes('wikimedia'))
      && (await sample(page)).every((s) => !s.url.includes('wikimedia')), traffic.media);
    const real = problems.filter((p) => !/status of 404 \(\)$/.test(p));
    check('no page error or console error (besides the known favicon 404)', real.length === 0, problems);
  }, { viewport: { width: 1280, height: 800 } });
} else if (phase === 'fail' && args.length === 3 && ['decode', 'source', 'offline'].includes(args[0]) && UUID.test(args[1]) && UUID.test(args[2])) {
  const [kind, adventureId, campaignId] = args;
  await withPage(async (page, context) => {
    const hits = [];
    // Only media is intercepted; every other request goes to the packaged engine unchanged.
    await page.route('**/assets/*', async (route) => {
      hits.push(Date.now());
      if (kind === 'decode') return route.fulfill({ status: 200, contentType: 'audio/wav', body: Buffer.concat([Buffer.from('RIFF'), Buffer.alloc(4), Buffer.from('WAVE'), Buffer.alloc(512, 7)]) });
      if (kind === 'source') return route.abort('failed');
      return route.continue();
    });
    const traffic = await open(page, 'en', adventureId, campaignId);
    if (kind === 'offline') await context.setOffline(true);
    await page.click('[data-music-on]');
    await page.waitForTimeout(3000);
    const failedState = await panel(page);
    const elements = await sample(page);
    const hitsAtFailure = hits.length;
    await page.waitForTimeout(5000);
    console.log(JSON.stringify({ kind, status: failedState.status, elements, mediaRequests: traffic.media.length, routeHits: hits.length }));
    check(`${kind}: the panel reports the track as unavailable and offers the retry button`, (await page.$('[data-music-retry]')) !== null && failedState.title === null, failedState.status);
    check(`${kind}: no element is left playing`, elements.every((s) => s.paused), elements);
    check(`${kind}: nothing is retried by itself in the next 5 s`, hits.length === hitsAtFailure && (await sample(page)).length === elements.length, { hitsAtFailure, hits: hits.length });
    check(`${kind}: the story stays usable`, failedState.storyUsable, failedState);
    check(`${kind}: no other recording was substituted (no online request)`, traffic.media.every((url) => !url.includes('wikimedia')), traffic.media);
    if (kind === 'offline') {
      await context.setOffline(false);
      await page.click('[data-music-retry]');
      await page.waitForTimeout(2500);
      const back = await sample(page);
      check('offline: the explicit retry plays the track once the network is back', !back.at(-1).paused && back.at(-1).error === null, back);
    }
  });
} else if (phase === 'adventures' && args.length <= 1 && ['en', 'it', undefined].includes(args[0])) {
  // Playthroughs are created from the screens of this locale, so `it` shows each pack's Italian content.
  const create = args[0] ?? 'en';
  await withPage(async (page) => {
    const problems = [];
    page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`));
    const listed = {};
    for (const locale of ['en', 'it']) {
      await page.goto(`${ORIGIN}/${locale}/library`, { waitUntil: 'networkidle' });
      listed[locale] = await page.locator('a[href*="/saved/"]').evaluateAll((links) => links.map((a) => ({
        id: a.getAttribute('href').split('/saved/')[1].split('?')[0],
        // The link reads "Open saved adventure"; the title is in the card around it.
        text: (a.closest('li, article') ?? a.parentElement).textContent.replace(/\s+/g, ' ').trim() })));
    }
    console.log(JSON.stringify({ listed }, null, 1));
    const ids = [...new Set(listed.en.map((entry) => entry.id))];
    check('the English library lists two adventures', ids.length === 2 && ids.every((id) => UUID.test(id)), listed.en);
    check('the Italian library lists the same two adventures', JSON.stringify([...new Set(listed.it.map((e) => e.id))].sort()) === JSON.stringify([...ids].sort()), listed.it);
    check('the libraries show both titles', ['en', 'it'].every((l) => listed[l].some((e) => e.text.includes('Lantern Ford')) && listed[l].some((e) => e.text.includes('Neon Alley'))), listed);
    const made = [];
    for (const adventureId of ids) {
      await page.goto(`${ORIGIN}/${create}/saved/${adventureId}`, { waitUntil: 'networkidle' });
      await page.click(`a[href*="/adventures/${adventureId}/create"]`);
      await page.waitForSelector('#playthrough-title');
      await page.fill('#playthrough-title', 'Music check');
      await page.fill('#character-name', 'Mara');
      await page.click('button[type="submit"]');
      await page.waitForURL(/playthrough=/, { timeout: 30000 });
      const campaignId = new URL(page.url()).searchParams.get('playthrough');
      await page.waitForSelector('[data-credits]', { timeout: 30000 });
      const opened = await page.evaluate(() => ({ title: document.querySelector('#campaign-title')?.textContent?.trim() ?? null,
        place: document.querySelector('[data-location]')?.textContent?.replace(/\s+/g, ' ').trim() ?? null,
        items: [...document.querySelectorAll('[data-item]')].map((row) => row.getAttribute('data-item')) }));
      made.push({ adventureId, campaignId, opened });
      check(`a playthrough of ${adventureId} was created through the screens and opened`, UUID.test(campaignId ?? '') && opened.items.length > 0, opened);
    }
    console.log(JSON.stringify({ made }, null, 1));
    const [a, b] = made;
    const crossed = await page.evaluate(async ([one, two]) => {
      const read = async (campaign, adventure) => (await fetch(`/api/v1/campaigns/${campaign}/panels?adventureId=${adventure}`)).status;
      const own = await Promise.all([read(one.campaignId, one.adventureId), read(two.campaignId, two.adventureId)]);
      const cross = await Promise.all([read(one.campaignId, two.adventureId), read(two.campaignId, one.adventureId)]);
      const music = await Promise.all([one, two].map(async (s) => (await (await fetch(`/api/v1/campaigns/${s.campaignId}/music?adventureId=${s.adventureId}`)).json()).tracks.map((t) => t.sha256)));
      return { own, cross, music };
    }, [a, b]);
    console.log(JSON.stringify({ crossed }));
    check('each playthrough is read under its own adventure', crossed.own.every((s) => s === 200), crossed.own);
    check('neither playthrough is readable under the other adventure', crossed.cross.every((s) => s === 404), crossed.cross);
    check('the two adventures play different own tracks', crossed.music.every((list) => list.length === 2) && crossed.music[0].every((h) => !crossed.music[1].includes(h)), crossed.music);
    check('the two playthroughs have different items (no shared state)', JSON.stringify(a.opened.items) !== JSON.stringify(b.opened.items), [a.opened.items, b.opened.items]);
    check('no page error', problems.length === 0, problems);
  });
} else if (phase === 'switch' && args.length === 5 && ['en', 'it'].includes(args[0]) && args.slice(1).every((id) => UUID.test(id))) {
  const [locale, adventureA, campaignA, adventureB, campaignB] = args;
  await withPage(async (page) => {
    const traffic = await open(page, locale, adventureA, campaignA);
    await page.click('[data-music-on]');
    await page.waitForTimeout(2500);
    const first = JSON.parse(traffic.listings[0]?.body ?? 'null');
    const before = await sample(page);
    check('A plays its own exploration track', before.length === 1 && !before[0].paused && before[0].url.includes(`/campaigns/${campaignA}/assets/${first?.tracks?.[0]?.sha256}?`), before);
    await page.evaluate((url) => { history.pushState(null, '', url); dispatchEvent(new PopStateEvent('popstate', { state: null })); }, `/${locale}/saved/${adventureB}?playthrough=${campaignB}`);
    await page.waitForTimeout(150);
    const atSwitch = await sample(page);
    await page.waitForSelector(`.campaign-playthrough[data-campaign-id="${campaignB}"]`, { timeout: 30000 });
    await page.waitForTimeout(3000);
    const after = await sample(page);
    const shown = await panel(page);
    const second = JSON.parse(traffic.listings[1]?.body ?? 'null');
    console.log(JSON.stringify({ atSwitch, after, title: shown.title, listings: traffic.listings.map((l) => l.status) }));
    check('within 150 ms of the adventure switch A\'s element is paused', atSwitch.length >= 1 && atSwitch.filter((s) => s.url.includes(`/campaigns/${campaignA}/`)).every((s) => s.paused), atSwitch);
    check('B\'s listing was read once with its own scope', traffic.listings.length === 2 && second?.scope?.adventureId === adventureB && second?.scope?.campaignId === campaignB, traffic.listings.map((l) => l.status));
    const newer = after.filter((s) => s.url.includes(`/campaigns/${campaignB}/`));
    check('B plays its own exploration track once, a different file from A\'s', newer.length === 1 && !newer[0].paused && newer[0].url.includes(`/assets/${second?.tracks?.[0]?.sha256}?adventureId=${adventureB}`)
      && second?.tracks?.[0]?.sha256 !== first?.tracks?.[0]?.sha256, newer);
    check('the current-track view names B\'s track', shown.title === second?.tracks?.[0]?.title, shown.title);
    check('nothing else was created and no element used the online host', after.length === 2 && traffic.media.every((url) => !url.includes('wikimedia')), { after, media: traffic.media });
    const stored = await page.evaluate(() => localStorage.getItem('engine.music.v1') ?? '');
    const seen = traffic.listings.map((l) => l.body).join(' ') + shown.html + traffic.media.join(' ') + stored;
    check('no hidden encounter detail of either adventure in the listings, the panel, media addresses or the preference', leaks(seen).length === 0, leaks(seen));
    await page.evaluate((url) => { history.pushState(null, '', url); dispatchEvent(new PopStateEvent('popstate', { state: null })); }, `/${locale}/library`);
    await page.waitForTimeout(500);
    check('leaving the playthrough stops all music', (await sample(page)).every((s) => s.paused), await sample(page));
  }, { viewport: { width: 1280, height: 800 } });
} else {
  console.error('usage: music-journey.mjs play en|it ADVENTURE CAMPAIGN OTHER_CAMPAIGN SHOTDIR [engage] | fail decode|source|offline ADVENTURE CAMPAIGN | adventures [en|it] | switch en|it ADV_A CAMP_A ADV_B CAMP_B');
  process.exit(2);
}
process.exit(failed ? 1 : 0);
