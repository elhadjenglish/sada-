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

// Continuous listener for the wake phrase. Restarts itself when the browser ends the session.
export function startWakeListener({ onText, onFatal }) {
  if (!SR) return () => {};
  let stopped = false;
  let rec;
  const begin = () => {
    rec = new SR();
    rec.lang = 'en-US';
    rec.continuous = true;
    rec.interimResults = false;
    rec.onresult = (e) => {
      for (let i = e.resultIndex; i < e.results.length; i++) {
        if (e.results[i].isFinal) onText(e.results[i][0].transcript);
      }
    };
    rec.onerror = (e) => {
      if (e.error === 'not-allowed' || e.error === 'service-not-allowed' || e.error === 'audio-capture') {
        stopped = true;
        onFatal?.(e.error);
      }
    };
    rec.onend = () => {
      if (!stopped) setTimeout(() => { if (!stopped) try { begin(); } catch {} }, 300);
    };
    try { rec.start(); } catch {}
  };
  begin();
  return () => {
    stopped = true;
    try { rec?.abort(); } catch {}
  };
}
