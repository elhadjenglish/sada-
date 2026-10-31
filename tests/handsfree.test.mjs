// SIMULATED tests of the hands-free controller (src/lib/handsfree.js): a fake recognizer, player,
// speech output and clock drive the real state machine through complete flows.
// They do NOT test a real microphone, real speech recognition, Bluetooth or a locked screen.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createHandsFree, answerScript, isEcho, isPhrase } from '../src/lib/handsfree.js';

function harness({ settings = {}, askImpl, speakImpl, startPlaying = true } = {}) {
  let t = 0, seq = 0;
  const due = new Map();
  const timers = { set: (fn, ms) => { const id = ++seq; due.set(id, { at: t + ms, fn }); return id; }, clear: (id) => due.delete(id) };
  const advance = async (ms) => {
    const end = t + ms;
    for (;;) {
      const next = [...due.entries()].filter(([, d]) => d.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!next) break;
      due.delete(next[0]); t = next[1].at; next[1].fn(); await flush();
    }
    t = end;
  };
  const flush = () => new Promise((r) => setImmediate(r));
  const log = [];
  const recognizer = { listening: false, starts: 0, start() { this.listening = true; this.starts++; log.push('rec:start'); }, stop() { this.listening = false; log.push('rec:stop'); } };
  const player = {
    playing: startPlaying, position: 42.37, idx: 5,
    isPlaying() { return this.playing; },
    pause() { this.playing = false; log.push('pause'); return { kind: 'audio', time: this.position }; },
    resume(pos) { this.playing = true; this.position = pos.time; log.push(`resume@${pos.time}`); },
  };
  const asked = [], spoken = [], statuses = [];
  const S = { wakePhrase: 'Hey Sada', resumePhrase: 'Continue', explainLang: 'both', ...settings };
  const ctrl = createHandsFree({
    recognizer, player, timers, getSettings: () => S,
    ask: askImpl || (async (q) => { asked.push(q); return { is_vocab: true, word: 'reliable', meaning_en: 'you can trust it', meaning_ar: 'موثوق', in_context_en: 'Here it means Nadia can be trusted.', in_context_ar: 'هنا تعني أنه يمكن الوثوق بنادية.' }; }),
    speak: speakImpl || ((script) => { spoken.push(script); log.push(`speak(listening=${recognizer.listening})`); return Promise.resolve(); }),
    earcon: (k) => log.push(`earcon:${k}`),
    onStatus: (s) => statuses.push(s),
  });
  return { ctrl, recognizer, player, asked, spoken, statuses, log, advance, flush, S };
}

test('complete flow: wake → pause → question → answer spoken → resume at the exact position', async () => {
  const h = harness();
  h.ctrl.start();
  assert.equal(h.ctrl.state, 'wake');
  assert.ok(h.recognizer.listening);
  h.ctrl.onTranscript('hey sada', false); // interim: pause immediately
  assert.equal(h.player.playing, false, 'paused on the interim wake phrase');
  assert.equal(h.ctrl.state, 'question');
  h.ctrl.onTranscript('hey sada what does reliable mean', true);
  await h.flush();
  assert.deepEqual(h.asked, ['what does reliable mean'], 'only the question is sent, without the wake phrase');
  assert.equal(h.spoken.length, 1);
  assert.ok(h.log.includes('speak(listening=false)'), 'recognizer is off while Sada speaks');
  assert.equal(h.ctrl.state, 'resume');
  await h.advance(400);
  assert.ok(h.recognizer.listening, 'listening again after speaking');
  h.ctrl.onTranscript('continue', true);
  assert.equal(h.player.playing, true);
  assert.ok(h.log.includes('resume@42.37'), 'resumed at the saved position');
  assert.equal(h.ctrl.state, 'wake');
});

test('two-step question: wake phrase, pause, then the question as a separate utterance', async () => {
  const h = harness();
  h.ctrl.start();
  h.ctrl.onTranscript('Hey Sada', true);
  assert.equal(h.ctrl.state, 'question');
  h.ctrl.onTranscript('what does', false);
  h.ctrl.onTranscript('what does opportunity mean', true);
  await h.flush();
  assert.deepEqual(h.asked, ['what does opportunity mean']);
});

test('question ends after silence when no final result arrives', async () => {
  const h = harness();
  h.ctrl.start();
  h.ctrl.onTranscript('hey sada what does generous mean', false);
  assert.equal(h.asked.length, 0);
  await h.advance(1900);
  assert.deepEqual(h.asked, ['what does generous mean']);
});

test('no question after the wake phrase → back to the podcast at the same position, no AI call', async () => {
  const h = harness();
  h.ctrl.start();
  h.ctrl.onTranscript('hey sada', true);
  await h.advance(8100);
  assert.equal(h.asked.length, 0);
  assert.ok(h.log.includes('resume@42.37'));
  assert.ok(h.statuses.some((s) => s.kind === 'no-question'));
});

test('custom phrases and small mishearings', async () => {
  const h = harness({ settings: { wakePhrase: 'Okay teacher', resumePhrase: 'Go on' } });
  h.ctrl.start();
  h.ctrl.onTranscript('hey sada what does reliable mean', true);
  assert.equal(h.ctrl.state, 'wake', 'default phrase ignored when customized');
  h.ctrl.onTranscript('okay teachers what does reliable mean', true); // misheard plural
  await h.flush();
  assert.deepEqual(h.asked, ['what does reliable mean']);
  await h.advance(400);
  h.ctrl.onTranscript('continue', true);
  assert.equal(h.player.playing, false, 'old resume phrase ignored');
  h.ctrl.onTranscript('go on', true);
  assert.equal(h.player.playing, true);
});

test('podcast speech without the wake phrase is ignored and never sent to the AI', async () => {
  const h = harness();
  h.ctrl.start();
  for (const t of ['She explained that she was reliable.', 'they said a word', 'continue']) h.ctrl.onTranscript(t, true);
  await h.flush();
  assert.equal(h.asked.length, 0);
  assert.equal(h.player.playing, true);
});

test('Sada does not obey its own voice: echo of the answer is ignored', async () => {
  const h = harness();
  h.ctrl.start();
  h.ctrl.onTranscript('hey sada what does reliable mean', true);
  await h.flush();
  await h.advance(400);
  h.ctrl.onTranscript('here it means Nadia can be trusted', true); // tail of the spoken answer
  h.ctrl.onTranscript('say continue to keep listening', false); // interim echo containing the resume word
  assert.equal(h.player.playing, false, 'echo did not resume');
  assert.ok(h.statuses.some((s) => s.kind === 'echo-ignored'));
  assert.equal(h.asked.length, 1);
});

test('follow-up question while paused, then resume once', async () => {
  const h = harness();
  h.ctrl.start();
  h.ctrl.onTranscript('hey sada what does reliable mean', true);
  await h.flush(); await h.advance(400);
  h.ctrl.onTranscript('hey sada what does trust mean', true);
  await h.flush(); await h.advance(400);
  assert.deepEqual(h.asked, ['what does reliable mean', 'what does trust mean']);
  h.ctrl.onTranscript('continue', true);
  assert.deepEqual(h.log.filter((l) => l.startsWith('resume@')), ['resume@42.37'], 'restored once, to the original position');
});

test('AI/network failure: an apology is spoken and resume still works', async () => {
  const h = harness({ askImpl: async () => { throw new Error('502'); } });
  h.ctrl.start();
  h.ctrl.onTranscript('hey sada what does reliable mean', true);
  await h.flush();
  assert.match(h.spoken[0][0].text, /couldn't get an answer/);
  assert.ok(h.statuses.some((s) => s.kind === 'answer-failed'));
  await h.advance(400);
  h.ctrl.onTranscript('continue', true);
  assert.ok(h.log.includes('resume@42.37'));
});

test('speech output failure does not trap the user', async () => {
  const h = harness({ speakImpl: () => Promise.reject(new Error('tts-no-start')) });
  h.ctrl.start();
  h.ctrl.onTranscript('hey sada what does reliable mean', true);
  await h.flush();
  assert.ok(h.statuses.some((s) => s.kind === 'speak-failed'));
  assert.equal(h.ctrl.state, 'resume');
  await h.advance(400);
  h.ctrl.onTranscript('continue', true);
  assert.equal(h.player.playing, true);
});

test('recognition interruptions: restarts after the browser ends a session, backs off on network errors', async () => {
  const h = harness();
  h.ctrl.start();
  const before = h.recognizer.starts;
  h.ctrl.onRecognitionEnd();
  await h.advance(300);
  assert.equal(h.recognizer.starts, before + 1, 'restarted after a normal end');
  for (let i = 0; i < 5; i++) { h.ctrl.onRecognitionError('network'); h.ctrl.onRecognitionEnd(); await h.advance(9000); }
  assert.ok(h.statuses.some((s) => s.kind === 'network-down'), 'reports a lasting network problem');
  assert.equal(h.ctrl.state, 'wake', 'still trying, not turned off');
  h.ctrl.onRecognitionError('no-speech'); h.ctrl.onRecognitionEnd();
  await h.advance(9000);
  assert.ok(h.recognizer.listening);
});

test('microphone permission denied → hands-free stops with a reason', () => {
  for (const code of ['not-allowed', 'service-not-allowed', 'audio-capture']) {
    const h = harness();
    h.ctrl.start();
    h.ctrl.onRecognitionError(code);
    assert.equal(h.ctrl.state, 'off');
    assert.equal(h.recognizer.listening, false);
    assert.ok(h.statuses.some((s) => s.kind === 'fatal' && s.code === code));
  }
});

test('no restart while speaking, even if the browser ends recognition', async () => {
  let release;
  const h = harness({ speakImpl: () => new Promise((r) => { release = r; }) });
  h.ctrl.start();
  h.ctrl.onTranscript('hey sada what does reliable mean', true);
  await h.flush();
  assert.equal(h.ctrl.state, 'speaking');
  h.ctrl.onRecognitionEnd();
  await h.advance(1000);
  assert.equal(h.recognizer.listening, false);
  release(); await h.flush(); await h.advance(400);
  assert.ok(h.recognizer.listening);
});

test('resume phrase while the AI is still thinking cancels the answer and resumes', async () => {
  let release;
  const h = harness({ askImpl: () => new Promise((r) => { release = r; }) });
  h.ctrl.start();
  h.ctrl.onTranscript('hey sada what does reliable mean', true);
  assert.equal(h.ctrl.state, 'thinking');
  h.ctrl.onTranscript('continue', true);
  assert.equal(h.player.playing, true);
  release({ is_vocab: true, word: 'reliable' }); await h.flush();
  assert.equal(h.spoken.length, 0, 'late answer not spoken over the podcast');
});

test('one API call per question (duplicate finals are ignored)', async () => {
  const h = harness();
  h.ctrl.start();
  h.ctrl.onTranscript('hey sada what does reliable mean', true);
  h.ctrl.onTranscript('hey sada what does reliable mean', true);
  await h.flush();
  assert.equal(h.asked.length, 1);
});

test('wake phrase when the podcast was already paused: answers, resume does not start playback', async () => {
  const h = harness({ startPlaying: false });
  h.ctrl.start();
  h.ctrl.onTranscript('hey sada what does reliable mean', true);
  await h.flush(); await h.advance(400);
  h.ctrl.onTranscript('continue', true);
  assert.equal(h.player.playing, false);
  assert.equal(h.asked.length, 1);
});

test('screen visibility changes are reported and listening restarts when visible', async () => {
  const h = harness();
  h.ctrl.start();
  h.ctrl.onVisibility(true);
  assert.ok(h.statuses.some((s) => s.kind === 'hidden'));
  h.recognizer.listening = false;
  h.ctrl.onVisibility(false);
  assert.ok(h.recognizer.listening);
});

test('spoken answer follows the explanation-language setting', () => {
  const r = { is_vocab: true, word: 'reliable', meaning_en: 'you can trust it', meaning_ar: 'موثوق', in_context_en: 'ctx en', in_context_ar: 'ctx ar' };
  const langs = (l) => answerScript(r, l, 'Continue').map((x) => `${x.lang}:${x.text}`);
  assert.deepEqual(langs('ar'), ['en:reliable.', 'ar:موثوق', 'ar:ctx ar', 'en:Say Continue to keep listening.']);
  assert.deepEqual(langs('en'), ['en:reliable.', 'en:It means: you can trust it', 'en:ctx en', 'en:Say Continue to keep listening.']);
  assert.deepEqual(langs('both'), ['en:reliable.', 'ar:موثوق', 'en:It means: you can trust it', 'en:ctx en', 'en:Say Continue to keep listening.']);
});

test('helpers: phrase matching and echo detection', () => {
  assert.ok(isPhrase('Continue.', 'Continue'));
  assert.ok(isPhrase('ok continue', 'Continue'));
  assert.ok(!isPhrase('continue reading the next chapter of the book', 'Continue'));
  assert.ok(isEcho('it means you can trust it', 'Reliable. It means: you can trust it. Say Continue.'));
  assert.ok(!isEcho('what does generous mean', 'Reliable. It means: you can trust it.'));
});
