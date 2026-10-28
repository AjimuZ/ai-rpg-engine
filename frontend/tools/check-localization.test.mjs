/** Test fail-closed XLIFF parity; synthetic resources exercise failures without editing reviewed files. */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { checkCatalog } from './check-localization.mjs';

/** Wrap supplied units in XLIFF 2; returns fixture XML and performs no I/O. */
function catalog(units, locale = 'it') {
  return `<?xml version="1.0"?><xliff xmlns="urn:oasis:names:tc:xliff:document:2.0" version="2.0" srcLang="en" trgLang="${locale}"><file id="ui">${units}</file></xliff>`;
}

/** Build one synthetic unit with optional target; XML errors are intentionally left for the checker. */
function unit(id, source, target) {
  return `<unit id="${id}"><segment><source>${source}</source>${target === undefined ? '' : `<target>${target}</target>`}</segment></unit>`;
}

test('accepts exact resources and permits reordered named placeholders', /** Word order may change while placeholder identities and counts remain exact. */ () => {
  const source = 'Hello <ph id="0" equiv="NAME"/>: <ph id="1" equiv="COUNT"/>';
  const target = '<ph id="1" equiv="COUNT"/> per <ph id="0" equiv="NAME"/>';
  assert.doesNotThrow(/** Return the checked count; any parity failure propagates to the assertion. */ () => checkCatalog(catalog(unit('shell.greeting', source)), catalog(unit('shell.greeting', source, target))));
});

test('rejects missing, blank, stale, extra and duplicate entries', /** A successful localized build alone must not mask incomplete or obsolete resources. */ () => {
  const source = catalog(unit('shell.title', 'Library'));
  for (const target of [catalog(''), catalog(unit('shell.title', 'Library', ' ')),
    catalog(unit('shell.title', 'Old library', 'Biblioteca')),
    catalog(unit('shell.title', '\n Library', 'Biblioteca')),
    catalog(unit('shell.title', 'Library', 'Biblioteca') + unit('unused', 'Old', 'Vecchio')),
    catalog(unit('shell.title', 'Library', 'Biblioteca').repeat(2)),
    catalog(unit('shell.title', 'Library'))]) {
    assert.throws(/** Check each invalid fixture independently; acceptance is a test failure. */ () => checkCatalog(source, target));
  }
});

test('rejects missing, renamed and repeated placeholders', /** Broken substitutions must fail before production compilation. */ () => {
  const source = 'Hello <ph id="0" equiv="NAME"/>';
  for (const target of ['Ciao', 'Ciao <ph id="0" equiv="OTHER"/>', '<ph id="0" equiv="NAME"/><ph id="1" equiv="NAME"/>']) {
    assert.throws(/** Preserve substitution counts even when visible wording differs. */ () => checkCatalog(catalog(unit('shell.greeting', source)), catalog(unit('shell.greeting', source, target))));
  }
});

test('rejects malformed XML and the wrong target language', /** Invalid catalogs fail with diagnostics instead of silently becoming English. */ () => {
  const source = catalog(unit('shell.title', 'Library'));
  assert.throws(/** Malformed XML must propagate diagnostics instead of an empty resource set. */ () => checkCatalog(source, '<xliff>'));
  assert.throws(/** A complete catalog for another locale cannot satisfy Italian parity. */ () => checkCatalog(source, catalog(unit('shell.title', 'Library', 'Biblioteca'), 'fr')));
});

test('titles each compiled index with its reviewed brand and refuses ambiguous documents', /** The Italian page must not open with an English title. */ async () => {
  const { brandTitles, retitle } = await import('./localize-index.mjs');
  const reviewed = catalog(unit('shell.brand', 'Adventure engine', 'Motore di avventure'));
  assert.deepEqual(brandTitles(reviewed), { en: 'Adventure engine', it: 'Motore di avventure' });
  const index = '<!doctype html>\n<html lang="it" dir="ltr">\n<head><title>Adventure engine</title></head><body></body></html>';
  const titled = retitle(index, 'it', 'Motore di avventure');
  assert.equal(titled, index.replace('Adventure engine', 'Motore di avventure'));
  assert.equal(retitle(titled, 'it', 'Motore di avventure'), titled);
  assert.match(retitle(index, 'it', 'A & <B> $&'), /<title>A &amp; &lt;B&gt; \$&amp;<\/title>/);
  assert.throws(/** The wrong document language is a packaging error, not something to retitle. */ () => retitle(index, 'en', 'Adventure engine'));
  assert.throws(/** Zero or several titles are ambiguous. */ () => retitle(index.replace(/<title>.*<\/title>/, ''), 'it', 'X'));
  assert.throws(/** Zero or several titles are ambiguous. */ () => retitle(index.replace('</head>', '<title>Two</title></head>'), 'it', 'X'));
  for (const broken of [catalog(unit('shell.other', 'Adventure engine', 'Motore')), catalog(unit('shell.brand', 'Adventure engine', ' ')),
    catalog(unit('shell.brand', 'Engine <ph id="0" equiv="NAME"/>', 'Motore <ph id="0" equiv="NAME"/>'))]) {
    assert.throws(/** A missing, blank or parameterized brand cannot become a static title. */ () => brandTitles(broken));
  }
});
