// Writes the original sample music of the bundled adventures: two short looping WAV tracks for each sample.
//
// Responsibility: produce the committed bytes of, under backend/src/main/resources/,
//   sample-adventure/assets/ford-dusk.wav               (Lantern Ford, mood exploration, 8 s loop)
//   sample-adventure/assets/ford-stones.wav             (Lantern Ford, mood tension, 6 s loop)
//   sample-adventure-2/assets/alley-rain.wav            (Neon Alley, mood exploration, 6 s loop)
//   sample-adventure-2/assets/alley-wires.wav           (Neon Alley, mood tension, 4 s loop)
// from note lists and sine arithmetic alone. Run: node tools/sample-music/make-sample-music.mjs [output-root]
// where output-root replaces backend/src/main/resources (a scratch folder, to compare a second run byte for byte).
//
// Always holds: the output depends on nothing but this file and Node's built-in modules (no download, no dependency,
// no random number, no clock), so two runs write identical bytes. The melodies are written in this file, so the
// recordings are original work covered by the project's MIT license. Each loop is seamless: every note starts and ends
// at zero amplitude inside the loop, and every sustained pad frequency completes a whole number of cycles per loop, so
// the last sample joins the first without a click.
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const RATE = 16000;
const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(process.argv[2] ?? resolve(here, '../../backend/src/main/resources'));

/** Frequency of a MIDI note number in equal temperament (A4 = 69 = 440 Hz). */
const hz = (note) => 440 * 2 ** ((note - 69) / 12);

/** Round a frequency to the nearest one that completes whole cycles in `seconds`, which keeps a pad seamless. */
const looped = (frequency, seconds) => Math.round(frequency * seconds) / seconds;

/**
 * Render one track.
 *
 * @param seconds loop length; a whole number of beats
 * @param beat seconds per melody step
 * @param melody MIDI note per step, or null for a rest
 * @param pad sustained MIDI notes under the melody
 * @param bass MIDI note per beat pair, played as a soft low pulse
 * @returns 16-bit signed mono samples
 */
function render({ seconds, beat, melody, pad, bass, bright }) {
  const total = Math.round(seconds * RATE);
  const out = new Float64Array(total);
  // Melody: a plucked tone with two harmonics and a decay that reaches zero before the next step starts.
  melody.forEach((note, step) => {
    if (note === null) return;
    const start = Math.round(step * beat * RATE);
    const length = Math.round(beat * RATE * 0.95);
    const f = hz(note);
    for (let i = 0; i < length && start + i < total; i++) {
      const t = i / RATE;
      const attack = Math.min(1, i / (0.01 * RATE));
      const decay = (1 - i / length) ** 2;
      const tone = Math.sin(2 * Math.PI * f * t) + bright * Math.sin(4 * Math.PI * f * t) + 0.15 * Math.sin(6 * Math.PI * f * t);
      out[start + i] += 0.22 * attack * decay * tone;
    }
  });
  // Bass: one soft pulse every two steps, also back at zero before the next.
  bass.forEach((note, pulse) => {
    const start = Math.round(pulse * 2 * beat * RATE);
    const length = Math.round(2 * beat * RATE * 0.9);
    const f = hz(note);
    for (let i = 0; i < length && start + i < total; i++) {
      const envelope = Math.sin(Math.PI * i / length) ** 2;
      out[start + i] += 0.18 * envelope * Math.sin(2 * Math.PI * f * (i / RATE));
    }
  });
  // Pad: quiet sustained chord with a slow swell, whole cycles per loop.
  for (const note of pad) {
    const f = looped(hz(note), seconds);
    for (let i = 0; i < total; i++) {
      const swell = 0.75 + 0.25 * Math.cos(2 * Math.PI * i / total);
      out[i] += 0.05 * swell * Math.sin(2 * Math.PI * f * (i / RATE));
    }
  }
  const peak = out.reduce((m, v) => Math.max(m, Math.abs(v)), 0);
  const gain = 0.7 / peak;
  return Int16Array.from(out, (v) => Math.round(v * gain * 32767));
}

/** Little-endian PCM WAV container: RIFF header, fmt chunk (PCM, mono, 16 bit) and data chunk. */
function wav(samples) {
  const data = Buffer.alloc(samples.length * 2);
  samples.forEach((v, i) => data.writeInt16LE(v, i * 2));
  const header = Buffer.alloc(44);
  header.write('RIFF', 0, 'latin1');
  header.writeUInt32LE(36 + data.length, 4);
  header.write('WAVE', 8, 'latin1');
  header.write('fmt ', 12, 'latin1');
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20); // PCM
  header.writeUInt16LE(1, 22); // mono
  header.writeUInt32LE(RATE, 24);
  header.writeUInt32LE(RATE * 2, 28);
  header.writeUInt16LE(2, 32);
  header.writeUInt16LE(16, 34);
  header.write('data', 36, 'latin1');
  header.writeUInt32LE(data.length, 40);
  return Buffer.concat([header, data]);
}

// "Ford at Dusk": an unhurried major-pentatonic line over C and F, 16 steps of half a second.
const dusk = render({
  seconds: 8, beat: 0.5, bright: 0.3,
  melody: [72, 76, 79, 76, 74, 72, null, 67, 69, 72, 74, 76, 74, 72, 69, null],
  pad: [48, 55, 64],
  bass: [36, 36, 41, 41, 43, 43, 36, 36],
});
// "Stones in the Current": a low minor ostinato with a held fifth, 16 steps of three eighths of a second.
const stones = render({
  seconds: 6, beat: 0.375, bright: 0.5,
  melody: [57, null, 60, 57, 63, null, 62, 60, 57, null, 60, 57, 56, null, 57, null],
  pad: [45, 52],
  bass: [33, 33, 33, 32, 33, 33, 31, 32],
});

// "Neon Rain": a bright minor-pentatonic figure over a held minor chord, 16 steps of three eighths of a second.
const rain = render({
  seconds: 6, beat: 0.375, bright: 0.6,
  melody: [69, 72, 76, 72, 74, null, 72, 69, 67, 69, null, 72, 76, 79, 76, null],
  pad: [45, 52, 60],
  bass: [33, 33, 36, 36, 38, 38, 33, 31],
});
// "Humming Wires": a quick repeated low figure with a sharp upper note, 16 steps of a quarter of a second.
const wires = render({
  seconds: 4, beat: 0.25, bright: 0.8,
  melody: [52, 52, null, 55, 52, 52, 58, null, 52, 52, null, 55, 57, null, 55, null],
  pad: [40, 47],
  bass: [28, 28, 28, 31, 28, 28, 26, 27],
});

for (const [folder, name, samples] of [
  ['sample-adventure', 'ford-dusk.wav', dusk], ['sample-adventure', 'ford-stones.wav', stones],
  ['sample-adventure-2', 'alley-rain.wav', rain], ['sample-adventure-2', 'alley-wires.wav', wires]]) {
  const directory = join(root, folder, 'assets');
  mkdirSync(directory, { recursive: true });
  const bytes = wav(samples);
  writeFileSync(join(directory, name), bytes);
  console.log(`wrote ${bytes.length} bytes to ${join(directory, name)}`);
}
