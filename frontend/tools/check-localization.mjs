/** Validate extracted English against reviewed Italian XLIFF; CLI failures exit nonzero without rewriting resources. */
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { Xliff2TranslationParser } from '@angular/localize/tools';

/** Parse targets with Angular's pinned parser; malformed XML, duplicate IDs and warnings throw diagnostics. */
export function parse(contents) {
  const parser = new Xliff2TranslationParser();
  const analysis = parser.analyze('messages.xlf', contents);
  assert.ok(analysis.canParse, 'Invalid XLIFF 2 catalog');
  assert.equal(analysis.diagnostics.messages.length, 0, 'XLIFF analysis diagnostics');
  const bundle = parser.parse('messages.xlf', contents, analysis.hint);
  assert.equal(bundle.diagnostics.messages.length, 0, JSON.stringify(bundle.diagnostics.messages));
  return bundle;
}

/** Read source messages through the same parser; returns normalized messages and throws on invalid source XML. */
export function sources(contents) {
  // Angular's parser consumes target nodes. This deliberately narrow rewrite supports
  // the CLI's canonical XLIFF 2 output, preserving its inline placeholder structure.
  return parse(contents.replace(/<target\b[^>]*>[\s\S]*?<\/target>/g, '')
    .replace(/<(\/?)source\b/g, '<$1target')).translations;
}

/** Check exact IDs, source freshness and substitution multisets; returns the message count or throws on any mismatch. */
export function checkCatalog(english, italian) {
  const expected = sources(english);
  const reviewedSource = sources(italian);
  const translated = parse(italian);
  assert.equal(translated.locale, 'it', 'Target locale must be Italian');
  const ids = Object.keys(expected).sort();
  assert.ok(ids.length > 0, 'English extraction must contain messages');
  assert.deepEqual(Object.keys(reviewedSource).sort(), ids, 'Source ID parity');
  assert.deepEqual(Object.keys(translated.translations).sort(), ids, 'Target ID parity');
  for (const id of ids) {
    assert.match(id, /^[a-z][a-zA-Z0-9]*(?:\.[a-zA-Z0-9]+)+$/, `Semantic message ID: ${id}`);
    assert.deepEqual(reviewedSource[id], expected[id], `Stale English source: ${id}`);
    const target = translated.translations[id];
    assert.ok(target.text.trim(), `Empty Italian target: ${id}`);
    assert.deepEqual([...target.placeholderNames].sort(), [...expected[id].placeholderNames].sort(), `Placeholder parity: ${id}`);
  }
  return ids.length;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const [englishPath, italianPath] = process.argv.slice(2);
  assert.ok(englishPath && italianPath, 'Usage: node tools/check-localization.mjs <extracted-en.xlf> <reviewed-it.xlf>');
  const [english, italian] = await Promise.all([readFile(englishPath, 'utf8'), readFile(italianPath, 'utf8')]);
  console.log(`Localization parity: ${checkCatalog(english, italian)} reviewed messages`);
}
