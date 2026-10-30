// Push-to-talk recorder: captures the mic and encodes mono 16-bit PCM WAV at 16 kHz,
// the format Muse Voice Transcribe accepts on /v1/asr/transcribe.

export const recordingSupported =
  typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia && typeof window !== 'undefined' && !!(window.AudioContext || window.webkitAudioContext);

const TARGET_RATE = 16000;
const START_TIMEOUT_MS = 30000; // waiting on the permission prompt

export class MicError extends Error {
  constructor(kind, message) { super(message); this.kind = kind; }
}

const MESSAGES = {
  insecure: 'The microphone only works on a secure (https) page.',
  unsupported: 'This browser can’t record audio. Type your question instead.',
  frame: 'This page is shown inside another page or app that doesn’t allow the microphone, so the browser blocks it even though your phone allows it. Open Sada directly in Chrome to use the mic.',
  'site-denied': 'Microphone access is turned off for this site. In Chrome, tap the icon left of the address, then Permissions → Microphone → Allow.',
  denied: 'The microphone wasn’t allowed. Tap the mic again and choose Allow. If you opened this link inside another app, open it in Chrome instead.',
  'no-mic': 'No microphone was found on this device.',
  busy: 'Another app is using the microphone. Close it and try again.',
  'no-audio': 'The browser couldn’t start audio recording. Try again.',
  timeout: 'The microphone didn’t start. Tap the mic and try again.',
  silent: 'No sound was recorded. Check that the microphone isn’t muted and try again.',
  unknown: 'The microphone couldn’t start. Try again.',
};
export const micMessage = (kind) => MESSAGES[kind] || MESSAGES.unknown;

// Permissions Policy decides whether a framed page may use the mic at all, regardless of OS settings.
function framePolicyAllows() {
  const p = document.permissionsPolicy || document.featurePolicy;
  try { return p?.allowsFeature ? p.allowsFeature('microphone') : true; } catch { return true; }
}

async function permissionState() {
  try { return (await navigator.permissions?.query({ name: 'microphone' }))?.state || 'unknown'; } catch { return 'unknown'; }
}

// Explains a getUserMedia failure precisely instead of always blaming browser settings.
async function classify(err) {
  const name = err?.name || '';
  if (name === 'NotAllowedError' || name === 'PermissionDeniedError' || name === 'SecurityError') {
    if (!framePolicyAllows()) return 'frame';
    if ((await permissionState()) === 'denied') return 'site-denied';
    return 'denied';
  }
  if (name === 'NotFoundError' || name === 'DevicesNotFoundError' || name === 'OverconstrainedError') return 'no-mic';
  if (name === 'NotReadableError' || name === 'TrackStartError' || name === 'AbortError') return 'busy';
  if (name === 'NotSupportedError' || name === 'TypeError') return 'unsupported';
  return 'unknown';
}

export async function startRecording({ maxMs = 20000, onLevel, onAutoStop } = {}) {
  if (!window.isSecureContext) throw new MicError('insecure', MESSAGES.insecure);
  if (!recordingSupported) throw new MicError('unsupported', MESSAGES.unsupported);
  if (!framePolicyAllows()) throw new MicError('frame', MESSAGES.frame);

  // Create the audio context synchronously inside the tap, before any await,
  // so Android Chrome doesn't leave it suspended for lack of a user gesture.
  const Ctx = window.AudioContext || window.webkitAudioContext;
  const ctx = new Ctx();
  const resumed = ctx.state === 'suspended' ? ctx.resume().catch(() => {}) : Promise.resolve();

  let stream;
  try {
    stream = await Promise.race([
      navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true } }),
      new Promise((_, rej) => setTimeout(() => rej(Object.assign(new Error('timeout'), { name: 'SadaTimeout' })), START_TIMEOUT_MS)),
    ]);
  } catch (e) {
    ctx.close().catch(() => {});
    const kind = e?.name === 'SadaTimeout' ? 'timeout' : await classify(e);
    throw new MicError(kind, micMessage(kind));
  }
  await resumed;
  if (ctx.state !== 'running') {
    stream.getTracks().forEach((t) => t.stop());
    ctx.close().catch(() => {});
    throw new MicError('no-audio', MESSAGES['no-audio']);
  }

  const src = ctx.createMediaStreamSource(stream);
  const proc = ctx.createScriptProcessor(4096, 1, 1);
  const chunks = [];
  let peak = 0;
  proc.onaudioprocess = (e) => {
    const d = e.inputBuffer.getChannelData(0);
    chunks.push(new Float32Array(d));
    let sum = 0;
    for (let i = 0; i < d.length; i += 4) { sum += d[i] * d[i]; const a = Math.abs(d[i]); if (a > peak) peak = a; }
    onLevel?.(Math.min(1, Math.sqrt(sum / (d.length / 4)) * 4));
  };
  src.connect(proc);
  proc.connect(ctx.destination);

  let done = false;
  const timer = setTimeout(() => onAutoStop?.(), maxMs);
  const cleanup = () => {
    if (done) return;
    done = true;
    clearTimeout(timer);
    try { proc.disconnect(); src.disconnect(); } catch {}
    stream.getTracks().forEach((t) => t.stop());
    ctx.close().catch(() => {});
  };

  return {
    stop() {
      const rate = ctx.sampleRate;
      cleanup();
      const length = chunks.reduce((n, c) => n + c.length, 0);
      const merged = new Float32Array(length);
      let off = 0;
      for (const c of chunks) { merged.set(c, off); off += c.length; }
      const pcm = downsample(merged, rate, TARGET_RATE);
      const seconds = pcm.length / TARGET_RATE;
      if (seconds >= 0.5 && peak < 0.002) throw new MicError('silent', MESSAGES.silent);
      return { base64: toBase64(encodeWav(pcm, TARGET_RATE)), seconds };
    },
    cancel: cleanup,
  };
}

function downsample(input, from, to) {
  if (from === to) return input;
  const ratio = from / to;
  const out = new Float32Array(Math.floor(input.length / ratio));
  for (let i = 0; i < out.length; i++) {
    const start = Math.floor(i * ratio);
    const end = Math.min(input.length, Math.floor((i + 1) * ratio));
    let sum = 0;
    for (let j = start; j < end; j++) sum += input[j];
    out[i] = sum / Math.max(1, end - start);
  }
  return out;
}

function encodeWav(samples, rate) {
  const buf = new ArrayBuffer(44 + samples.length * 2);
  const v = new DataView(buf);
  const str = (o, s) => { for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)); };
  str(0, 'RIFF'); v.setUint32(4, 36 + samples.length * 2, true); str(8, 'WAVE');
  str(12, 'fmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, rate, true); v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
  str(36, 'data'); v.setUint32(40, samples.length * 2, true);
  let o = 44;
  for (let i = 0; i < samples.length; i++, o += 2) {
    const s = Math.max(-1, Math.min(1, samples[i]));
    v.setInt16(o, s < 0 ? s * 0x8000 : s * 0x7fff, true);
  }
  return new Uint8Array(buf);
}

function toBase64(bytes) {
  let bin = '';
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(bin);
}
