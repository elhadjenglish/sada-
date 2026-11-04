import { BASE_URL, ASR_MODEL, friendlyError, logError, UpstreamError } from './_meta.js';
import { guard, readBody, LIMITS } from './_guard.js';

export const config = { maxDuration: 30 };

export const MAX_SECONDS = 30;
const RATES = [16000, 24000];
// Largest accepted request: 30 s at 24 kHz mono 16-bit (1,440,000 bytes of audio) plus a generous allowance for
// header chunks, base64-encoded (x 4/3), plus the JSON around it. Vercel accepts request bodies up to 4.5 MB.
export const MAX_BODY_BYTES = Math.ceil((MAX_SECONDS * 24000 * 2 + 64 * 1024) * 4 / 3) + 16 * 1024;

/**
 * Reads a WAV file chunk by chunk (RIFF allows extra chunks such as LIST, in any order, with odd sizes padded).
 * Accepts what both Sada clients record and Meta's Speech-to-text documents: PCM, mono, 16-bit, 16 or 24 kHz.
 * Returns { error } or { rate, pcm } where pcm is the audio data only.
 */
export function readWav(buf) {
  if (buf.length < 12) return { error: 'The recording was empty.' };
  if (buf.toString('ascii', 0, 4) !== 'RIFF' || buf.toString('ascii', 8, 12) !== 'WAVE') return { error: 'The recording is not a WAV file.' };
  let fmt = null, pcm = null;
  for (let i = 12; i + 8 <= buf.length; ) {
    const id = buf.toString('ascii', i, i + 4);
    let size = buf.readUInt32LE(i + 4);
    const start = i + 8;
    if (id === 'data') {
      // Some recorders write 0 or 0xFFFFFFFF while streaming: then the audio runs to the end of the file.
      if (size === 0 || size === 0xffffffff || start + size > buf.length) size = buf.length - start;
      pcm = buf.subarray(start, start + size);
      if (fmt) break;
    } else if (id === 'fmt ') {
      if (size < 16 || start + size > buf.length) return { error: 'The recording format is not supported.' };
      fmt = { format: buf.readUInt16LE(start), channels: buf.readUInt16LE(start + 2), rate: buf.readUInt32LE(start + 4), bits: buf.readUInt16LE(start + 14) };
    } else if (start + size > buf.length) {
      break; // truncated extra chunk
    }
    i = start + size + (size & 1); // chunks are padded to an even length
  }
  if (!fmt || !pcm) return { error: 'The recording format is not supported.' };
  if (fmt.format !== 1 || fmt.channels !== 1 || fmt.bits !== 16 || !RATES.includes(fmt.rate)) {
    return { error: 'The recording must be mono 16-bit PCM WAV at 16 or 24 kHz.' };
  }
  const usable = pcm.subarray(0, pcm.length - (pcm.length % 2));
  const seconds = usable.length / (fmt.rate * 2);
  if (seconds < 0.3) return { error: 'The recording was too short.' };
  if (seconds > MAX_SECONDS) return { error: `Keep questions under ${MAX_SECONDS} seconds.` };
  return { rate: fmt.rate, pcm: usable };
}

/** A canonical 44-byte-header WAV (fmt + data only): exactly the simple layout Meta's examples use. */
export function canonicalWav(rate, pcm) {
  const h = Buffer.alloc(44);
  h.write('RIFF', 0); h.writeUInt32LE(36 + pcm.length, 4); h.write('WAVE', 8);
  h.write('fmt ', 12); h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22);
  h.writeUInt32LE(rate, 24); h.writeUInt32LE(rate * 2, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34);
  h.write('data', 36); h.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([h, pcm]);
}

const cleanList = (v, maxItems, maxLen) =>
  Array.isArray(v) ? v.filter((x) => typeof x === 'string').map((x) => x.trim().slice(0, maxLen)).filter(Boolean).slice(0, maxItems) : [];

const LANGUAGES = new Set(['English', 'Arabic', 'French']);

export default async function handler(req, res) {
  const ok = await guard(req, res, {
    maxBodyBytes: MAX_BODY_BYTES, // 30 s at 24 kHz, base64-encoded, plus headers and JSON
    limits: [
      { name: 'asr-min', max: LIMITS.asrMinute(), windowSec: 60 },
      { name: 'asr-day', max: LIMITS.asrDay(), windowSec: 86400 },
      { name: 'all-day', max: LIMITS.globalDay(), windowSec: 86400, scope: 'global' },
    ],
  });
  if (!ok) return;

  const body = readBody(req);
  if (!body || typeof body.audio !== 'string' || !/^[A-Za-z0-9+/=]+$/.test(body.audio)) {
    return res.status(400).json({ error: 'No audio received.' });
  }
  const wav = readWav(Buffer.from(body.audio, 'base64'));
  if (wav.error) return res.status(400).json({ error: wav.error });
  const bytes = canonicalWav(wav.rate, wav.pcm);

  const request = { model: ASR_MODEL, mode: 'PUSH_TO_TALK', audioEncoding: 'WAV' };
  const langs = cleanList(body.languages, 3, 20).filter((l) => LANGUAGES.has(l));
  request.languageBias = langs.length ? langs : ['English', 'Arabic'];
  const keywords = cleanList(body.keywords, 40, 40);
  if (keywords.length) request.keywords = keywords;

  try {
    // Same shape as Meta's reference curl: a JSON "request" part (no filename) and an "audio" WAV part.
    const boundary = `----sada${Date.now().toString(16)}${Math.random().toString(16).slice(2, 8)}`;
    const payload = Buffer.concat([
      Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="request"\r\nContent-Type: application/json\r\n\r\n${JSON.stringify(request)}\r\n`),
      Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="audio"; filename="question.wav"\r\nContent-Type: audio/wav\r\n\r\n`),
      bytes,
      Buffer.from(`\r\n--${boundary}--\r\n`),
    ]);
    const r = await fetch(`${BASE_URL}/asr/transcribe`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${process.env.MODEL_API_KEY}`,
        Accept: 'application/json',
        'Content-Type': `multipart/form-data; boundary=${boundary}`,
      },
      body: payload,
      signal: AbortSignal.timeout(25000),
    });
    const raw = await r.text();
    if (!r.ok) throw new UpstreamError(r.status, raw);
    let data;
    try { data = JSON.parse(raw); } catch { throw new Error('Meta returned a transcript that was not JSON.'); }
    if (typeof data?.transcript !== 'string') throw new Error('Meta returned no transcript field.');
    return res.status(200).json({ text: data.transcript.trim(), model: ASR_MODEL, source: 'meta' });
  } catch (e) {
    logError('transcribe', e);
    if (e instanceof UpstreamError && e.status === 400) return res.status(502).json({ error: 'Meta could not read the recording. Try again.' });
    return res.status(502).json({ error: friendlyError(e) });
  }
}
