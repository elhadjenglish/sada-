// Bridge to the Android app (window.SadaNative, added by the native WebView host). In a normal browser every export
// is inert: the web app keeps working exactly as before.
const N = typeof window !== 'undefined' ? window.SadaNative : null;
export const nativeAvailable = !!N;

const json = (s, fallback) => { try { return JSON.parse(s); } catch { return fallback; } };

/** Current native state: { running, playing, loaded, ended, phase, stateText, episodeId, positionMs, durationMs, preparing, error }. */
export function nativeState() { return N ? json(N.state(), null) : null; }
/** Events produced while the screen was off or the app was in the background (questions, answers, words to save). */
export function nativeDrain() { return N ? json(N.drainEvents(), []) : []; }
export function nativePlay(args) { N?.play(JSON.stringify(args)); }
export const nativeResume = () => N?.resume();
export const nativePause = () => N?.pause();
export const nativeSeek = (fraction) => N?.seek(String(fraction));
export const nativeAsk = () => N?.ask();
export const nativeStop = () => N?.stop();
export const nativeSetPrefs = (lang, level) => N?.setPrefs(lang, level);
export const nativeOpenDiagnostics = () => N?.openDiagnostics();
export const nativeCancel = () => N?.cancel();
/** Speaks English text with the phone's own voice (the WebView has no working speechSynthesis). */
export const nativeSpeak = (text) => N?.speak(String(text || ''));
/** Opens Android's file picker for audio; the chosen file is copied into the app. Result: onAudioPicked(cb). */
export const nativePickAudio = () => N?.pickAudio();
let audioPickedCb = null;
export const onAudioPicked = (cb) => { audioPickedCb = cb; };
if (typeof window !== 'undefined') {
  window.__sadaAudioPicked = (url, name, error) => audioPickedCb?.({ url, name, error });
}

// Requests to our own /api routes go through the native app: it talks to the Vercel server (the Meta key stays there).
const pending = new Map();
let nextId = 1;
if (typeof window !== 'undefined') {
  window.__sadaFetchDone = (id, status, body) => {
    const p = pending.get(id);
    if (p) { pending.delete(id); p({ status, body }); }
  };
}
export function nativeFetch(path, body) {
  return new Promise((resolve) => {
    const id = nextId++;
    pending.set(id, resolve);
    N.fetchJson(id, path, JSON.stringify(body));
  });
}

/** Where in the episode a sentence starts, as a fraction of the narration (by character count). */
export function fractionOfIdx(sentences, i) {
  const total = sentences.reduce((n, s) => n + s.length + 1, 0) || 1;
  let before = 0;
  for (let k = 0; k < i && k < sentences.length; k++) before += sentences[k].length + 1;
  return before / total;
}
/** Which sentence is being narrated at a fraction of the narration. */
export function idxOfFraction(sentences, f) {
  const total = sentences.reduce((n, s) => n + s.length + 1, 0) || 1;
  let acc = 0;
  for (let k = 0; k < sentences.length; k++) {
    acc += sentences[k].length + 1;
    if (f * total < acc) return k;
  }
  return Math.max(0, sentences.length - 1);
}
