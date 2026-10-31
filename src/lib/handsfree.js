// Hands-free listening controller: a pure state machine with no DOM access.
// Browser pieces (recognizer, player, AI call, speech) are injected, so the full flow is testable.
//
//   wake ──wake phrase──▶ question ──question──▶ thinking ──answer──▶ speaking ──done──▶ resume
//     ▲                      │ (no question)                                             │
//     └──────────────────────┴──────────────────────── resume phrase ◀───────────────────┘
//
// The recognizer is stopped while Sada speaks, so it can't hear its own answer; an echo filter
// also ignores transcripts that repeat what was just spoken.
import { matchPhrase, norm } from './text.js';

export const STATES = ['off', 'wake', 'question', 'thinking', 'speaking', 'resume'];
const QUESTION_SILENCE_MS = 1800; // end of question after this much quiet following speech
const QUESTION_TIMEOUT_MS = 8000; // nothing asked after the wake phrase → go back to the podcast
const MAX_NET_RETRIES = 5;

export function isPhrase(text, phrase) {
  const t = norm(text);
  const p = norm(phrase);
  if (!t || !p) return false;
  const m = matchPhrase(t, p);
  // the phrase must be essentially the whole utterance (allow one filler word: "ok continue", "continue please")
  return !!m && t.split(' ').length <= p.split(' ').length + 1;
}

// Builds what Sada says aloud for an explanation, in the learner's chosen language(s).
export function answerScript(r, lang, resumePhrase) {
  const out = [];
  const en = (text) => text && out.push({ text, lang: 'en' });
  const ar = (text) => text && out.push({ text, lang: 'ar' });
  if (!r) return out;
  if (r.demo_unavailable) {
    en(`This preview has no demo answer for ${r.word || 'that word'}.`);
  } else if (r.is_vocab) {
    en(`${r.word}.`);
    if (lang !== 'en') ar(r.meaning_ar);
    if (lang !== 'ar') en(r.meaning_en ? `It means: ${r.meaning_en}` : '');
    if (lang === 'ar') ar(r.in_context_ar);
    else en(r.in_context_en);
  } else {
    if (lang !== 'en') ar(r.answer_ar);
    if (lang !== 'ar' || !r.answer_ar) en(r.answer_en);
  }
  en(`Say ${resumePhrase} to keep listening.`);
  return out;
}

// True when a transcript is mostly words Sada just said (its own voice picked up by the mic).
export function isEcho(text, spoken) {
  const t = norm(text).split(' ').filter((w) => w.length > 2);
  if (t.length < 2 || !spoken) return false;
  const s = new Set(norm(spoken).split(' '));
  return t.filter((w) => s.has(w)).length / t.length >= 0.6;
}

export function createHandsFree(deps) {
  const { recognizer, player, ask, speak, earcon, onStatus, getSettings, timers = { set: (fn, ms) => setTimeout(fn, ms), clear: (id) => clearTimeout(id) } } = deps; // wrapped: browsers reject setTimeout called as another object's method
  let state = 'off';
  let paused = null; // position saved when Sada paused the podcast; null if it wasn't playing
  let buffer = '';
  let silenceTimer = null, timeoutTimer = null, restartTimer = null;
  let netFailures = 0;
  let lastSpoken = '';
  let lastQuestion = { text: '', at: 0 };
  let hidden = false;
  let speakingCancel = null;

  const s = () => getSettings();
  const status = (kind, extra = {}) => onStatus?.({ state, kind, ...extra });
  const clearQ = () => { timers.clear(silenceTimer); timers.clear(timeoutTimer); silenceTimer = timeoutTimer = null; };
  const setState = (next, kind = next, extra) => { state = next; status(kind, extra); };

  // Listening is wanted in every state except off and speaking.
  const shouldListen = () => state !== 'off' && state !== 'speaking';
  const listen = () => { if (shouldListen()) recognizer.start(); };

  function pausePodcast() {
    if (paused) return;
    paused = player.isPlaying() ? player.pause() : null;
  }

  function resumePodcast() {
    clearQ();
    const pos = paused;
    paused = null;
    if (pos) player.resume(pos);
    setState('wake', pos ? 'resumed' : 'wake');
    listen();
  }

  function beginQuestion(rest) {
    pausePodcast();
    earcon?.('wake');
    buffer = rest || '';
    setState('question');
    clearQ();
    timeoutTimer = timers.set(() => {
      if (state !== 'question') return;
      if (buffer.trim()) return finishQuestion(buffer);
      earcon?.('cancel');
      status('no-question');
      resumePodcast();
    }, QUESTION_TIMEOUT_MS);
  }

  async function finishQuestion(raw) {
    clearQ();
    let q = raw.trim();
    const again = matchPhrase(q, s().wakePhrase);
    if (again) q = again.rest; // "hey sada hey sada what…" or a final result repeating the wake phrase
    q = q.trim();
    if (isPhrase(q, s().resumePhrase)) return resumePodcast();
    if (norm(q).length < 2) { status('no-question'); return resumePodcast(); }
    const now = Date.now();
    if (norm(q) === lastQuestion.text && now - lastQuestion.at < 4000) return; // same final delivered twice
    lastQuestion = { text: norm(q), at: now };

    setState('thinking', 'thinking', { question: q });
    let result = null, failed = false;
    try { result = await ask(q); } catch { failed = true; }
    if (state !== 'thinking') return; // turned off or resumed meanwhile
    if (!result) failed = true;
    const script = failed
      ? [{ text: `Sorry, I couldn't get an answer. Say ${s().resumePhrase} to keep listening, or ask again.`, lang: 'en' }]
      : answerScript(result, s().explainLang, s().resumePhrase);
    await sayThenWait(script, failed ? 'answer-failed' : 'answered');
  }

  async function sayThenWait(script, kind) {
    setState('speaking');
    recognizer.stop(); // never listen while speaking: prevents hearing our own answer
    lastSpoken = script.map((x) => x.text).join(' ');
    try {
      const p = speak(script);
      speakingCancel = p?.cancel;
      await p;
    } catch { status('speak-failed'); }
    speakingCancel = null;
    if (state !== 'speaking') return;
    setState('resume', kind);
    // short gap so the tail of our own voice isn't captured
    restartTimer = timers.set(listen, 350);
  }

  function onTranscript(text, isFinal) {
    if (state === 'off' || state === 'speaking') return;
    netFailures = 0;
    if (state === 'wake') {
      const m = matchPhrase(text, s().wakePhrase);
      if (!m) return;
      beginQuestion(m.rest); // pauses immediately, even on an interim result
      if (isFinal && m.rest.split(' ').filter(Boolean).length >= 2) finishQuestion(m.rest);
      else if (m.rest) armSilence();
      return;
    }
    if (state === 'question') {
      const m = matchPhrase(text, s().wakePhrase);
      buffer = m ? m.rest : text;
      if (isFinal && buffer.trim()) return finishQuestion(buffer);
      armSilence();
      return;
    }
    if (state === 'thinking') {
      if (isFinal && isPhrase(text, s().resumePhrase)) { state = 'resume'; resumePodcast(); }
      return;
    }
    if (state === 'resume') {
      if (isEcho(text, lastSpoken)) { status('echo-ignored'); return; }
      if (!isFinal) {
        // act on an interim resume phrase only if it's the whole utterance
        if (isPhrase(text, s().resumePhrase)) resumePodcast();
        return;
      }
      if (isPhrase(text, s().resumePhrase)) return resumePodcast();
      const m = matchPhrase(text, s().wakePhrase);
      if (m) {
        beginQuestion(m.rest);
        if (m.rest.split(' ').filter(Boolean).length >= 2) finishQuestion(m.rest);
      }
    }
  }

  function armSilence() {
    timers.clear(silenceTimer);
    silenceTimer = timers.set(() => { if (state === 'question' && buffer.trim()) finishQuestion(buffer); }, QUESTION_SILENCE_MS);
  }

  function onRecognitionEnd() {
    if (!shouldListen()) return;
    timers.clear(restartTimer);
    const delay = netFailures ? Math.min(8000, 500 * 2 ** netFailures) : 250;
    restartTimer = timers.set(listen, delay);
  }

  function onRecognitionError(code) {
    if (['not-allowed', 'service-not-allowed', 'audio-capture', 'language-not-supported'].includes(code)) {
      stop();
      status('fatal', { code });
      return;
    }
    if (code === 'network') {
      netFailures += 1;
      if (netFailures >= MAX_NET_RETRIES) { status('network-down'); }
    }
    // no-speech, aborted, network: onend follows and restarts with backoff
  }

  function onVisibility(isHidden) {
    hidden = isHidden;
    status(isHidden ? 'hidden' : state);
    if (!isHidden && shouldListen()) listen();
  }

  function start() {
    if (state !== 'off') return;
    netFailures = 0;
    setState('wake');
    listen();
  }

  function stop() {
    clearQ();
    timers.clear(restartTimer);
    speakingCancel?.();
    state = 'off';
    recognizer.stop();
    status('off');
  }

  // The learner pressed play/continue on screen: forget the saved pause, go back to wake-listening.
  function onManualResume() {
    if (state === 'off') return;
    clearQ();
    paused = null;
    if (state === 'speaking') speakingCancel?.();
    setState('wake');
    listen();
  }

  return {
    start, stop, onTranscript, onRecognitionEnd, onRecognitionError, onVisibility, onManualResume,
    get state() { return state; },
    get pausedAt() { return paused; },
    get hidden() { return hidden; },
  };
}
