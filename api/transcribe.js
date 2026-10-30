import { BASE_URL, ASR_MODEL, friendlyError, logError, UpstreamError } from './_meta.js';
import { guard, readBody, LIMITS } from './_guard.js';

export const config = { maxDuration: 30 };

const MAX_SECONDS = 30;

// Checks the clip is what the browser recorder produces and Meta accepts:
// RIFF/WAVE, PCM, mono, 16-bit, 16 or 24 kHz.
function inspectWav(buf) {
  if (buf.length < 44) return 'The recording was empty.';
  if (buf.toString('ascii', 0, 4) !== 'RIFF' || buf.toString('ascii', 8, 12) !== 'WAVE') return 'The recording is not a WAV file.';
  if (buf.toString('ascii', 12, 16) !== 'fmt ') return 'The recording format is not supported.';
  const format = buf.readUInt16LE(20), channels = buf.readUInt16LE(22), rate = buf.readUInt32LE(24), bits = buf.readUInt16LE(34);
  if (format !== 1 || channels !== 1 || bits !== 16 || ![16000, 24000].includes(rate)) return 'The recording must be mono 16-bit WAV at 16 or 24 kHz.';
  const seconds = (buf.length - 44) / (rate * 2);
  if (seconds < 0.3) return 'The recording was too short. Ask your question, then tap send.';
  if (seconds > MAX_SECONDS) return `Keep questions under ${MAX_SECONDS} seconds.`;
  return null;
}

const cleanList = (v, maxItems, maxLen) =>
  Array.isArray(v) ? v.filter((x) => typeof x === 'string').map((x) => x.trim().slice(0, maxLen)).filter(Boolean).slice(0, maxItems) : [];

const LANGUAGES = new Set(['English', 'Arabic', 'French']);

export default async function handler(req, res) {
  const ok = await guard(req, res, {
    maxBodyBytes: 1_400_000, // 30 s of 16 kHz mono 16-bit WAV, base64-encoded, plus JSON
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
  const bytes = Buffer.from(body.audio, 'base64');
  const problem = inspectWav(bytes);
  if (problem) return res.status(400).json({ error: problem });

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
