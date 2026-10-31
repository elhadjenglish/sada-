// SIMULATED: realistic recognition transcripts fed to the real hands-free controller and phrase matcher.
// Event sequences model how Chrome delivers results (growing partials, finals, sessions ending after each
// utterance). They do NOT prove what real Android Chrome recognizes; the on-screen "what Sada hears" log does.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createHandsFree } from '../src/lib/handsfree.js';
import { findPhrase } from '../src/lib/text.js';

function setup({ wakePhrase = 'Hey Sada' } = {}) {
  let t = 0, seq = 0; const due = new Map();
  const timers = { set: (fn, ms) => { const id = ++seq; due.set(id, { at: t + ms, fn }); return id; }, clear: (id) => due.delete(id) };
  const flush = () => new Promise((r) => setImmediate(r));
  const advance = async (ms) => { const end = t + ms; for (;;) { const n = [...due].filter(([, d]) => d.at <= end).sort((a, b) => a[1].at - b[1].at)[0]; if (!n) break; due.delete(n[0]); t = n[1].at; n[1].fn(); await flush(); } t = end; };
  const out = { pauses: 0, tones: 0, asked: [], debug: [] };
  const ctrl = createHandsFree({
    recognizer: { start() {}, stop() {} }, timers, now: () => t,
    getSettings: () => ({ wakePhrase, resumePhrase: 'Continue', explainLang: 'en' }),
    player: { playing: true, isPlaying() { return this.playing; }, pause() { out.pauses++; this.playing = false; return { kind: 'audio', time: 12.5 }; }, resume() { this.playing = true; } },
    ask: async (q) => { out.asked.push(q); return { is_vocab: true, word: 'reliable', meaning_en: 'trustworthy' }; },
    speak: () => Promise.resolve(), earcon: (k) => { if (k === 'wake') out.tones++; },
    onStatus() {}, onDebug: (e) => out.debug.push(e),
  });
  ctrl.start();
  // Feed a sequence: [text, isFinal] or 'END' (Android ends the session; Sada restarts it).
  const say = async (events) => { for (const e of events) { if (e === 'END') ctrl.onRecognitionEnd(); else ctrl.onTranscript(e[0], e[1]); await flush(); } };
  return { ctrl, out, say, advance };
}
const growing = (sentence) => {
  const w = sentence.split(' ');
  return [...w.map((_, i) => [w.slice(0, i + 1).join(' '), false]), [sentence, true], 'END'];
};

test('"Good morning" does NOT pause and plays no tone', async () => {
  const h = setup();
  await h.say(growing('Good morning'));
  assert.equal(h.out.pauses, 0);
  assert.equal(h.out.tones, 0);
  assert.equal(h.ctrl.state, 'wake');
  assert.ok(h.out.debug.some((d) => d.type === 'heard' && d.outcome === 'not-wake-phrase'), 'diagnostics show it was heard but not the wake phrase');
});

test('unrelated sentences do NOT pause and play no tone', async () => {
  const h = setup();
  for (const s of ['She explained that she was reliable', 'they said a lot of things', 'hey Sam can you hear me', 'say hello to everyone', 'hey Siri what time is it'])
    await h.say(growing(s));
  assert.equal(h.out.pauses, 0);
  assert.equal(h.out.tones, 0);
  assert.equal(h.out.asked.length, 0);
});

test('"Hey Sada" pauses (growing partial results, then final)', async () => {
  const h = setup();
  await h.say([['hey', false], ['hey sa', false]]);
  assert.equal(h.out.pauses, 0, 'a half-heard "hey sa" does not pause yet');
  await h.say([['hey Sada', false]]);
  assert.equal(h.out.pauses, 1, 'pauses as soon as the full phrase is recognized');
  assert.equal(h.out.tones, 1);
  assert.equal(h.ctrl.state, 'question');
});

for (const variant of ['Hey Sada', 'Hey, Sada.', 'hey sada', 'HEY SADA!', 'Hey Sarah', 'hay Sada', 'Heysada', 'hey sa da'])
  test(`variant "${variant}" pauses`, async () => {
    const h = setup();
    await h.say([[variant, true], 'END']);
    assert.equal(h.out.pauses, 1, variant);
    assert.equal(h.out.tones, 1);
  });

test('phrase split across two results ("Hey" final, then "Sada" final)', async () => {
  const h = setup();
  await h.say([['Hey', true], ['Sada', true]]);
  assert.equal(h.out.pauses, 1);
});

test('phrase split across two sessions (Android ends the session after "Hey")', async () => {
  const h = setup();
  await h.say([['hey', false], ['Hey', true], 'END', ['Sada', false], ['Sada', true], 'END']);
  assert.equal(h.out.pauses, 1);
});

test('an old "hey" does not combine with a "Sada" much later', async () => {
  const h = setup();
  await h.say([['Hey', true], 'END']);
  await h.advance(6000);
  await h.say([['Sada', true], 'END']);
  assert.equal(h.out.pauses, 0);
});

test('"Hey Sada, what does reliable mean?" in one breath triggers the question flow', async () => {
  const h = setup();
  await h.say(growing('Hey Sada, what does reliable mean?'));
  assert.equal(h.out.pauses, 1);
  assert.equal(h.out.tones, 1);
  assert.deepEqual(h.out.asked, ['what does reliable mean'], 'only the question, wake phrase removed');
});

test('wake phrase, session ends, question in the next session (two parts)', async () => {
  const h = setup();
  await h.say([['Hey Sada', true], 'END', ['what does', true], 'END', ['reliable mean', true], 'END']);
  assert.deepEqual(h.out.asked, ['what does reliable mean'], 'parts joined into one question, one API call');
});

test('short question finishes after silence', async () => {
  const h = setup();
  await h.say([['Hey Sada', true], 'END', ['reliable?', true], 'END']);
  assert.equal(h.out.asked.length, 0);
  await h.advance(1900);
  assert.deepEqual(h.out.asked, ['reliable?'], 'sent as recognized, punctuation included');
});

test('duplicate finals (some Android builds repeat results) cause one pause and one API call', async () => {
  const h = setup();
  await h.say([['hey Sada what does reliable mean', true], ['hey Sada what does reliable mean', true], 'END']);
  assert.equal(h.out.pauses, 1);
  assert.equal(h.out.asked.length, 1);
});

test('custom wake phrase in Arabic is recognized from an Arabic transcript', async () => {
  const h = setup({ wakePhrase: 'يا صدى' });
  await h.say([['يا صدى', true], 'END', ['what does reliable mean', true]]);
  assert.equal(h.out.pauses, 1);
  assert.deepEqual(h.out.asked, ['what does reliable mean']);
});

test('matcher: words near the phrase but different are rejected', () => {
  for (const t of ['hey Sam', 'they said', 'he said a', 'hey Siri', 'Good morning', 'okay', 'Sada'])
    assert.equal(findPhrase(t, 'Hey Sada'), null, t);
});

test('diagnostics never include anything but what was heard and the decision', async () => {
  const h = setup();
  await h.say(growing('Hey Sada what does reliable mean'));
  const keys = new Set(h.out.debug.flatMap((d) => Object.keys(d)));
  assert.deepEqual([...keys].sort(), ['code', 'final', 'from', 'outcome', 'text', 'to', 'type'].filter((k) => keys.has(k)).sort());
});

test('split wake phrase caught on a partial: its final is not taken as the question', async () => {
  const h = setup();
  await h.say([['Hey', true], 'END', ['Sada', false], ['Sada', true], 'END', ['what does reliable mean', true], 'END']);
  assert.equal(h.out.pauses, 1);
  assert.deepEqual(h.out.asked, ['what does reliable mean']);
});

test('asking the same question again after resuming is allowed (new wake-up)', async () => {
  const h = setup();
  await h.say([['Hey Sada what does reliable mean', true], 'END']);
  await h.advance(400);
  await h.say([['continue', true], 'END', ['Hey Sada what does reliable mean', true], 'END']);
  assert.equal(h.out.asked.length, 2);
});
