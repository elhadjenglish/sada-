// Device text-to-speech (narration + pronunciation) and the browser's built-in recognizer,
// which is used only for the optional hands-free wake phrase. Questions themselves go to Muse Voice Transcribe.
export const ttsSupported = typeof window !== 'undefined' && 'speechSynthesis' in window;
const SR = typeof window !== 'undefined' ? window.SpeechRecognition || window.webkitSpeechRecognition : null;
export const wakeSupported = !!SR;

export function englishVoice() {
  if (!ttsSupported) return null;
  const vs = window.speechSynthesis.getVoices();
  return (
    vs.find((v) => /en[-_]US/i.test(v.lang) && /google|natural|enhanced|samantha/i.test(v.name)) ||
    vs.find((v) => /en[-_](US|GB)/i.test(v.lang)) ||
    vs.find((v) => /^en/i.test(v.lang)) ||
    null
  );
}
if (ttsSupported) window.speechSynthesis.getVoices();

export function utter(text, rate = 1) {
  const u = new SpeechSynthesisUtterance(text);
  u.lang = 'en-US';
  u.rate = rate;
  const v = englishVoice();
  if (v) u.voice = v;
  return u;
}

export function speakOnce(text, rate = 0.85) {
  if (!ttsSupported) return;
  window.speechSynthesis.cancel();
  window.speechSynthesis.speak(utter(text, rate));
}

export function voiceFor(lang) {
  if (!ttsSupported) return null;
  const vs = window.speechSynthesis.getVoices();
  if (lang === 'ar') return vs.find((v) => /^ar/i.test(v.lang)) || null;
  return englishVoice();
}

/**
 * Speaks segments in order, each in its own language voice. Arabic is skipped when the phone has no Arabic voice
 * (an English voice can't read Arabic script). Resolves when done; rejects if the voice fails or never starts.
 * Returns a promise with a cancel() method.
 */
export function speakSegments(segments, { rate = 0.95 } = {}) {
  let cancelled = false;
  let timer = null;
  const p = (async () => {
    if (!ttsSupported) throw new Error('no-tts');
    const synth = window.speechSynthesis;
    synth.cancel();
    for (const seg of segments) {
      if (cancelled) return;
      const voice = voiceFor(seg.lang);
      if (seg.lang === 'ar' && !voice) continue;
      await new Promise((resolve, reject) => {
        const u = new SpeechSynthesisUtterance(seg.text);
        u.lang = seg.lang === 'ar' ? (voice?.lang || 'ar-SA') : 'en-US';
        if (voice) u.voice = voice;
        u.rate = rate;
        let started = false;
        const words = seg.text.split(/\s+/).length;
        timer = setTimeout(() => reject(new Error('tts-no-start')), 6000);
        u.onstart = () => {
          started = true;
          clearTimeout(timer);
          timer = setTimeout(resolve, 5000 + words * 700); // some Android voices never fire onend
        };
        u.onend = () => { clearTimeout(timer); resolve(); };
        u.onerror = (e) => { clearTimeout(timer); (e.error === 'interrupted' || e.error === 'canceled') ? resolve() : reject(new Error(e.error)); };
        synth.speak(u);
      });
    }
  })();
  p.cancel = () => { cancelled = true; clearTimeout(timer); if (ttsSupported) window.speechSynthesis.cancel(); };
  return p;
}

let earconCtx = null;
// Call from a tap (e.g. turning hands-free on) so the audio context is allowed to start.
export function primeEarcon() {
  try { earconCtx = earconCtx || new (window.AudioContext || window.webkitAudioContext)(); earconCtx.resume?.(); } catch {}
}
// Short tones: rising = "I'm listening", falling = "cancelled". Not speech, so the recognizer won't treat them as words.
export function earcon(kind = 'wake') {
  try {
    if (!earconCtx) return;
    const t = earconCtx.currentTime;
    const o = earconCtx.createOscillator();
    const g = earconCtx.createGain();
    o.frequency.setValueAtTime(kind === 'wake' ? 660 : 520, t);
    o.frequency.linearRampToValueAtTime(kind === 'wake' ? 990 : 330, t + 0.15);
    g.gain.setValueAtTime(0.0001, t);
    g.gain.exponentialRampToValueAtTime(0.25, t + 0.02);
    g.gain.exponentialRampToValueAtTime(0.0001, t + 0.2);
    o.connect(g).connect(earconCtx.destination);
    o.start(t);
    o.stop(t + 0.22);
  } catch {}
}

/**
 * Continuous recognizer for hands-free mode, using the browser's built-in engine (no Meta calls).
 * Uses on-device recognition when Chrome reports it available; otherwise Chrome's default service.
 * Emits interim and final transcripts; the controller decides what they mean and when to restart.
 */
export function createRecognizer({ lang = 'en-US', onResult, onEnd, onError, onEvent = () => {} }) {
  let rec = null;
  let running = false;
  let wanted = false;
  let mode = 'unknown';
  let session = 0;
  let endWatch = null;

  const modeReady = (async () => {
    try {
      if (SR && typeof SR.available === 'function') {
        const a = await SR.available({ langs: [lang], processLocally: true });
        mode = a === 'available' ? 'on-device' : 'browser-service';
      } else mode = 'browser-service';
    } catch { mode = 'browser-service'; }
    onEvent({ type: 'mode', mode });
    return mode;
  })();

  // Ends the current session exactly once, whether the browser sent "end" or not.
  function ended(me, why) {
    if (rec !== me || !running) return;
    clearTimeout(endWatch);
    running = false;
    onEvent({ type: 'session-closed', session, why });
    if (wanted) onEnd();
  }

  function start() {
    wanted = true;
    if (!SR || running) return;
    const me = new SR();
    rec = me;
    session += 1;
    me.lang = lang;
    me.continuous = true; // Android Chrome still ends after each utterance; the controller restarts it
    me.interimResults = true;
    me.maxAlternatives = 1;
    if (mode === 'on-device' && 'processLocally' in me) me.processLocally = true;
    // Handlers ignore events from an older session, so a late "end" can't stop a new session.
    me.onstart = () => { if (rec === me) onEvent({ type: 'session-start', session }); };
    me.onresult = (e) => {
      if (rec !== me) return;
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const r = e.results[i];
        if (r && r[0]) onResult(r[0].transcript, !!r.isFinal);
      }
    };
    me.onerror = (e) => {
      if (rec !== me) return;
      onError(e.error || 'unknown');
      // Some Android builds send "error" without a following "end": don't wait forever.
      clearTimeout(endWatch);
      endWatch = setTimeout(() => ended(me, `error:${e.error}`), 1500);
    };
    me.onend = () => ended(me, 'end');
    try {
      me.start();
      running = true;
    } catch {
      // Usually "already started" while the previous session is still closing: try again shortly.
      running = false;
      onEvent({ type: 'start-retry', session });
      setTimeout(() => { if (wanted && rec === me) start(); }, 400);
    }
  }

  function stop() {
    wanted = false;
    running = false;
    clearTimeout(endWatch);
    try { rec?.abort(); } catch {}
    rec = null;
  }

  return { start, stop, get running() { return running; }, get mode() { return mode; }, get session() { return session; }, modeReady };
}
