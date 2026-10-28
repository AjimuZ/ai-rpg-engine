/**
 * Give each compiled locale index its reviewed pre-bootstrap document title (P05.6, QA-35).
 *
 * Angular localizes templates and `$localize` calls but copies `src/index.html` verbatim apart from
 * `lang` and the base href, so the Italian index would keep the English `<title>` until the router
 * sets a route title. That English title is what a browser tab, history entry and screen reader
 * announce first, and the only title when scripting is off. This step runs after
 * `ng build --configuration production,localized` and writes the reviewed `shell.brand` message (the
 * brand shown in the navigation) into each locale's index. It validates every index before
 * writing any, so a failure leaves the build output unchanged, and it is idempotent.
 */
import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { parse, sources } from './check-localization.mjs';

/** Message whose wording becomes the document title before Angular sets a route title. */
export const BRAND_MESSAGE = 'shell.brand';
const TITLE = /<title>[^<]*<\/title>/g;

/**
 * Return the reviewed brand wording for both compiled locales.
 *
 * @param {string} italianCatalog reviewed XLIFF 2 text containing the brand's English source and Italian target
 * @returns {{ en: string, it: string }} plain, trimmed text
 * @throws AssertionError when the message is missing, blank or contains placeholders
 */
export function brandTitles(italianCatalog) {
  const english = sources(italianCatalog)[BRAND_MESSAGE];
  const italian = parse(italianCatalog).translations[BRAND_MESSAGE];
  assert.ok(english && italian, `Missing ${BRAND_MESSAGE} message`);
  assert.equal(english.placeholderNames.length + italian.placeholderNames.length, 0, 'The brand title cannot contain placeholders');
  assert.ok(english.text.trim() && italian.text.trim(), 'Blank brand title');
  return { en: english.text.trim(), it: italian.text.trim() };
}

/**
 * Replace the text of an index document's single title element.
 *
 * @param {string} html compiled locale index
 * @param {string} locale expected `lang` of the document element
 * @param {string} title plain text; `&`, `<` and `>` are escaped
 * @returns {string} the document with only the title text changed
 * @throws AssertionError unless the document declares `lang` = locale and has exactly one plain `<title>`
 */
export function retitle(html, locale, title) {
  assert.match(html, new RegExp(`<html lang="${locale}"[ >]`), `Index for ${locale} must declare lang="${locale}"`);
  assert.equal((html.match(TITLE) ?? []).length, 1, `Index for ${locale} must contain exactly one plain title element`);
  const text = title.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  return html.replace(TITLE, () => `<title>${text}</title>`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const [catalogPath, browserDirectory] = process.argv.slice(2);
  assert.ok(catalogPath && browserDirectory, 'Usage: node tools/localize-index.mjs <reviewed-it.xlf> <dist/.../browser>');
  const titles = Object.entries(brandTitles(await readFile(catalogPath, 'utf8')));
  const paths = titles.map(([locale]) => resolve(browserDirectory, locale, 'index.html'));
  const documents = await Promise.all(paths.map((path) => readFile(path, 'utf8')));
  const updated = documents.map((html, index) => retitle(html, titles[index][0], titles[index][1]));
  await Promise.all(paths.map((path, index) => writeFile(path, updated[index])));
  console.log(`Localized index titles: ${titles.map(([locale, title]) => `${locale} "${title}"`).join(', ')}`);
}
