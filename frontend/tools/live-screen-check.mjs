/**
 * Packaged-installation check of the live campaign screen WITHOUT a signed-in narration runtime (P17.3-B).
 *
 * It needs `./start.sh` running (http://127.0.0.1:8080), an existing playthrough (`node frontend/tools/artwork-journey.mjs create`
 * prints its identities) and the private browser tools in `.agents/ralph/browser/`. It never starts a stand-in server and sends
 * nothing: it opens the screen in English and Italian at 1280 and 390 pixels and checks what is shown when no narration exists, which
 * is the state of an installation whose runtime account is not signed in: no story section, no quick replies and no recipient, the
 * starting situation still readable, Send disabled even over a typed draft, no write request, no overflow, no axe violation.
 *
 * Usage: node frontend/tools/live-screen-check.mjs ADVENTURE CAMPAIGN [SHOT-DIRECTORY]
 * Exit status: 0 when every printed check holds; 1 when one failed; 2 for bad usage.
 * What it does not establish: any screen with real narration (QA-29 with live narration, owner gate P17.3-O) and screen-reader output.
 */
import { mkdirSync } from 'node:fs';
import { withPage, axeReport } from '../../.agents/ralph/browser/browser.mjs';

const [adventure, campaign, shots] = process.argv.slice(2);
if (!adventure || !campaign) { console.error('Usage: live-screen-check.mjs ADVENTURE CAMPAIGN [SHOT-DIRECTORY]'); process.exit(2); }
if (shots) mkdirSync(shots, { recursive: true });
let failed = false;
const check = (name, ok, detail) => { if (!ok) failed = true; console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${ok ? '' : ' :: ' + JSON.stringify(detail).slice(0, 600)}`); };

for (const [locale, width, height] of [['en', 1280, 800], ['it', 1280, 800], ['en', 390, 844]]) {
  await withPage(async (page) => {
    const errors = [];
    const writes = [];
    page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text().slice(0, 200)); });
    page.on('pageerror', (error) => errors.push(String(error).slice(0, 200)));
    page.on('request', (request) => { if (request.method() !== 'GET') writes.push(`${request.method()} ${request.url()}`); });
    await page.goto(`http://127.0.0.1:8080/${locale}/saved/${adventure}?playthrough=${campaign}`, { waitUntil: 'networkidle' });
    await page.waitForSelector('#campaign-input');
    const tag = `${locale}-${width}`;
    const state = await page.evaluate(() => ({
      story: document.querySelector('[data-live-story]') !== null,
      starting: document.querySelector('.starting-situation .passage')?.textContent ?? null,
      sendDisabled: document.querySelector('[data-send]').disabled,
      chips: document.querySelectorAll('[data-suggestion]').length,
      help: document.querySelector('#campaign-composer-help')?.textContent ?? '',
      connection: document.querySelector('[data-connection]')?.textContent ?? '',
      overflow: document.documentElement.scrollWidth > innerWidth,
    }));
    check(`${tag}: no narration yet, so no story section, no chips and no recipient`, !state.story && state.chips === 0 && !state.help.includes('words to') && !state.help.includes('parole rivolte'), state);
    check(`${tag}: the starting situation is still readable`, !!state.starting, state);
    check(`${tag}: Send is disabled without a recipient`, state.sendDisabled, state);
    await page.fill('#campaign-input', 'Hello there');
    check(`${tag}: a typed draft without a recipient still leaves Send disabled`, await page.evaluate(() => document.querySelector('[data-send]').disabled), {});
    check(`${tag}: the connection line is shown`, state.connection.length > 0, state);
    check(`${tag}: no horizontal overflow`, !state.overflow, state);
    check(`${tag}: nothing was written`, writes.length === 0, writes);
    // Chrome's own /favicon.ico request is answered 404 by the packaged origin (README known defect): the one tolerated console error.
    check(`${tag}: no console or page error besides the one known favicon 404`, errors.length === 1 && errors[0].includes('status of 404'), errors);
    check(`${tag}: axe reports no violation`, (await axeReport(page)).length === 0, await axeReport(page));
    if (shots) await page.screenshot({ path: `${shots}/live-${tag}.png`, fullPage: true });
  }, { viewport: { width, height } });
}
process.exit(failed ? 1 : 0);
