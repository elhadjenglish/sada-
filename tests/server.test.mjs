// MOCKED tests: these run the real server functions against a local fake of Meta's API.
// They prove request shape, validation, limits and error handling. They do NOT prove Meta accepts
// the requests; that needs the live check described in TESTING.md.
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';

const KEY = 'sk-test-SECRET-should-never-leak';
const calls = [];
let behavior = {};

const mock = http.createServer(async (req, res) => {
  let body = Buffer.alloc(0);
  for await (const c of req) body = Buffer.concat([body, c]);
  const call = { url: req.url, headers: req.headers, raw: body };
  calls.push(call);
  const send = (code, obj, type = 'application/json') => { res.writeHead(code, { 'content-type': type }); res.end(typeof obj === 'string' ? obj : JSON.stringify(obj)); };
  if (req.url === '/v1/chat/completions') {
    const j = JSON.parse(body);
    call.json = j;
    if (behavior.chat) return behavior.chat(j, send);
    return send(200, { model: j.model, choices: [{ message: { role: 'assistant', content: JSON.stringify(behavior.content || { ok: true }) }, finish_reason: 'stop' }] });
  }
  if (req.url === '/v1/asr/transcribe') {
    if (behavior.asr) return behavior.asr(call, send);
    return send(200, { sessionId: 's1', transcript: ' What does helpful mean? ', audioDurationMs: 1200, turns: [] });
  }
  send(404, { error: { message: 'not found' } });
});
await new Promise((r) => mock.listen(0, r));

process.env.META_BASE_URL = `http://127.0.0.1:${mock.address().port}/v1`;
process.env.MODEL_API_KEY = KEY;
process.env.VERCEL_ENV = 'production';
const ai = (await import('../api/ai.js')).default;
const transcribe = (await import('../api/transcribe.js')).default;

let ipCounter = 0;
function call(handler, body, { method = 'POST', origin = 'https://sada.example', host = 'sada.example', ip, headers = {} } = {}) {
  return new Promise((resolve) => {
    const out = { status: 0, headers: {}, body: null };
    const res = {
      setHeader(k, v) { out.headers[k.toLowerCase()] = v; },
      status(c) { out.status = c; return this; },
      json(d) { out.body = d; resolve(out); },
    };
    const h = { host, 'content-type': 'application/json', 'x-real-ip': ip || `10.0.0.${++ipCounter}`, ...headers };
    if (origin) h.origin = origin;
    handler({ method, headers: h, body }, res);
  });
}
const reset = (b = {}) => { calls.length = 0; behavior = b; };
const noLeak = (out) => assert.ok(!JSON.stringify(out).includes(KEY), 'API key leaked into response');

const EXPLAIN = { word: 'helpful', sentence: 'My neighbor is a very helpful person.', level: 'B1' };
const FULL = { is_vocab: true, word: 'helpful', lemma: 'helpful', pos: 'adjective', ipa: 'ˈhelpfəl', meaning_en: 'Happy to help.', meaning_ar: 'خدوم', in_context_en: 'Karim likes helping people.', in_context_ar: 'كريم يحب مساعدة الناس.', example: 'She is helpful.', answer_en: '', answer_ar: '' };

test('explain: request matches Meta Chat Completions docs', async () => {
  reset({ content: FULL });
  const out = await call(ai, { task: 'explain', payload: { ...EXPLAIN, lang: 'both' } });
  assert.equal(out.status, 200);
  const j = calls[0].json;
  assert.equal(j.model, 'muse-spark-1.3');
  assert.equal(j.messages[0].role, 'developer');
  assert.equal(j.response_format.type, 'json_schema');
  assert.equal(j.response_format.json_schema.name, 'WordExplanation');
  assert.ok(j.max_completion_tokens > 0 && !('max_tokens' in j));
  assert.equal(j.reasoning_effort, 'low');
  assert.ok(!('temperature' in j), 'temperature should stay at Meta default');
  assert.match(j.safety_identifier, /^[0-9a-f]{32}$/);
  assert.ok(!j.safety_identifier.includes('10.0.0'), 'raw IP must not be sent');
  assert.equal(calls[0].headers.authorization, `Bearer ${KEY}`);
  assert.equal(out.body.model, 'muse-spark-1.3');
  assert.equal(out.body.source, 'meta');
  noLeak(out);
});

test('language "both" asks for and returns Arabic AND English', async () => {
  reset({ content: FULL });
  const out = await call(ai, { task: 'explain', payload: { ...EXPLAIN, lang: 'both' } });
  const prompt = calls[0].json.messages[1].content;
  assert.match(prompt, /BOTH languages/);
  assert.equal(out.body.result.in_context_ar, FULL.in_context_ar);
  assert.equal(out.body.result.in_context_en, FULL.in_context_en);
});

test('language "ar" and "en" keep only the chosen explanation language', async () => {
  reset({ content: FULL });
  const ar = await call(ai, { task: 'explain', payload: { ...EXPLAIN, lang: 'ar' } });
  assert.match(calls[0].json.messages[1].content, /Arabic only/);
  assert.equal(ar.body.result.in_context_en, '');
  assert.ok(ar.body.result.in_context_ar);
  reset({ content: FULL });
  const en = await call(ai, { task: 'explain', payload: { ...EXPLAIN, lang: 'en' } });
  assert.equal(en.body.result.in_context_ar, '');
  assert.ok(en.body.result.in_context_en);
  // word meanings are always stored in both languages for the vocabulary list
  assert.ok(en.body.result.meaning_ar && en.body.result.meaning_en);
});

test('grade: both feedback languages, score within 0–1', async () => {
  // A valid GradeRubric answer (the schema Sada requests). The pre-rubric shape {correct, score} is now refused
  // as malformed (see "a malformed answer is retried once, then refused").
  reset({ content: { meaning: 'matches', uses_target_word: true, grammar: 'correct', grammar_note_en: '', grammar_note_ar: '', feedback_en: 'Good.', feedback_ar: 'جيد.', corrected: '' } });
  const out = await call(ai, { task: 'grade', payload: { lang: 'both', answer: 'He is helpful', exercise: { type: 'explain', word: 'helpful', prompt: 'Explain helpful' } } });
  assert.equal(out.status, 200);
  assert.equal(out.body.result.score, 1);
  assert.ok(out.body.result.feedback_ar && out.body.result.feedback_en);
});

test('falls back to META_FALLBACK_MODEL when muse-spark-1.3 is unavailable', async () => {
  reset({
    chat: (j, send) => (j.model === 'muse-spark-1.3'
      ? send(404, { error: { message: 'The model `muse-spark-1.3` does not exist', type: 'invalid_request_error', code: 'model_not_found', param: 'model' } })
      : send(200, { model: j.model, choices: [{ message: { content: '{"ok":true}' }, finish_reason: 'stop' }] })),
  });
  const out = await call(ai, { task: 'ping' });
  assert.equal(out.status, 200);
  assert.deepEqual(calls.map((c) => c.json.model), ['muse-spark-1.3', 'muse-spark-1.1']);
  assert.equal(out.body.model, 'muse-spark-1.1');
  assert.equal(out.body.configuredModel, 'muse-spark-1.3');
});

test('retries without response_format only if Meta rejects that parameter', async () => {
  reset({
    chat: (j, send) => (j.response_format
      ? send(400, { error: { message: 'unsupported response_format', param: 'response_format' } })
      : send(200, { model: j.model, choices: [{ message: { content: '```json\n{"ok":true}\n```' }, finish_reason: 'stop' }] })),
  });
  const out = await call(ai, { task: 'ping' });
  assert.equal(out.status, 200);
  assert.equal(calls.length, 2);
  assert.deepEqual(out.body.result, { ok: true });
});

test('Meta errors become safe messages (401, 429, 504, empty, cut off)', async () => {
  for (const [code, re] of [[401, /rejected the API key/], [429, /busy/], [504, /too long/]]) {
    reset({ chat: (j, send) => send(code, { error: { message: `secret detail ${KEY}`, type: 'x' } }) });
    const out = await call(ai, { task: 'explain', payload: EXPLAIN });
    assert.equal(out.status, 502);
    assert.match(out.body.error, re);
    noLeak(out);
  }
  reset({ chat: (j, send) => send(200, { choices: [{ message: { content: '' }, finish_reason: 'length' }] }) });
  const cut = await call(ai, { task: 'explain', payload: EXPLAIN });
  assert.equal(cut.status, 502);
  reset({ chat: (j, send) => send(200, { choices: [{ message: { content: 'not json at all' }, finish_reason: 'stop' }] }) });
  const bad = await call(ai, { task: 'explain', payload: EXPLAIN });
  assert.equal(bad.status, 502);
});

test('input validation rejects bad requests before calling Meta', async () => {
  reset();
  const cases = [
    [{ task: 'explain', payload: { word: 'x' } }, 400],
    [{ task: 'explain', payload: { sentence: 'Hi.' } }, 400],
    [{ task: 'explain', payload: { word: 'x'.repeat(61), sentence: 'Hi.' } }, 400],
    [{ task: 'explain', payload: { word: 42, sentence: 'Hi.' } }, 400],
    [{ task: 'exercises', payload: { words: [] } }, 400],
    [{ task: 'exercises', payload: { words: Array(9).fill({ word: 'a' }) } }, 400],
    [{ task: 'grade', payload: { exercise: { type: 'mcq', word: 'a', prompt: 'p' }, answer: 'x' } }, 400],
    [{ task: 'grade', payload: { exercise: { type: 'explain', word: 'a', prompt: 'p' }, answer: 'x'.repeat(601) } }, 400],
    [{ task: 'hack' }, 400],
  ];
  for (const [body, code] of cases) assert.equal((await call(ai, body)).status, code, JSON.stringify(body).slice(0, 80));
  assert.equal(calls.length, 0, 'Meta must not be called for invalid input');
});

test('abuse protection: method, origin, content type, size', async () => {
  reset();
  assert.equal((await call(ai, {}, { method: 'GET' })).status, 405);
  assert.equal((await call(ai, { task: 'ping' }, { origin: 'https://evil.example' })).status, 403);
  assert.equal((await call(ai, { task: 'ping' }, { origin: null })).status, 403);
  assert.equal((await call(ai, { task: 'ping' }, { headers: { 'content-type': 'text/plain' } })).status, 415);
  assert.equal((await call(ai, { task: 'explain', payload: { sentence: 'x'.repeat(20000) } })).status, 413);
  assert.equal(calls.length, 0);
});

test('rate limit: per-IP per-minute, with Retry-After', async () => {
  reset({ content: FULL });
  let last;
  for (let i = 0; i < 21; i++) last = await call(ai, { task: 'explain', payload: EXPLAIN }, { ip: '192.168.9.9' });
  assert.equal(last.status, 429);
  assert.equal(last.headers['retry-after'], '60');
  assert.equal(calls.length, 20);
  const other = await call(ai, { task: 'explain', payload: EXPLAIN }, { ip: '192.168.9.10' });
  assert.equal(other.status, 200, 'another learner is not affected');
});

test('rate limit: ping is limited separately (3/min)', async () => {
  reset();
  const codes = [];
  for (let i = 0; i < 4; i++) codes.push((await call(ai, { task: 'ping' }, { ip: '172.16.0.1' })).status);
  assert.deepEqual(codes, [200, 200, 200, 429]);
});

test('a hanging Meta request ends with a timeout message, not a hang', async () => {
  reset({ chat: () => {} }); // never responds
  const t0 = Date.now();
  const out = await call(ai, { task: 'explain', payload: EXPLAIN }, { ip: '10.9.9.9' });
  assert.equal(out.status, 502);
  assert.match(out.body.error, /too long/);
  assert.ok(Date.now() - t0 < 56000);
});

test('missing MODEL_API_KEY gives setup instructions, not a crash', async () => {
  delete process.env.MODEL_API_KEY;
  const out = await call(ai, { task: 'ping' });
  process.env.MODEL_API_KEY = KEY;
  assert.equal(out.status, 500);
  assert.match(out.body.error, /MODEL_API_KEY/);
});

// ---- voice ----
function wav({ seconds = 1, rate = 16000, channels = 1, bits = 16 } = {}) {
  const n = Math.round(seconds * rate) * channels * (bits / 8);
  const b = Buffer.alloc(44 + n);
  b.write('RIFF', 0); b.writeUInt32LE(36 + n, 4); b.write('WAVE', 8); b.write('fmt ', 12);
  b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(channels, 22); b.writeUInt32LE(rate, 24);
  b.writeUInt32LE(rate * channels * bits / 8, 28); b.writeUInt16LE(channels * bits / 8, 32); b.writeUInt16LE(bits, 34);
  b.write('data', 36); b.writeUInt32LE(n, 40);
  return b.toString('base64');
}

test('transcribe: multipart and headers match Meta reference', async () => {
  reset();
  const out = await call(transcribe, { audio: wav(), keywords: ['helpful', 'neighbor'], languages: ['English', 'Arabic', 'Klingon'] });
  assert.equal(out.status, 200);
  assert.equal(out.body.text, 'What does helpful mean?');
  assert.equal(out.body.source, 'meta');
  const c = calls[0];
  assert.equal(c.headers.accept, 'application/json');
  assert.equal(c.headers.authorization, `Bearer ${KEY}`);
  assert.match(c.headers['content-type'], /^multipart\/form-data; boundary=/);
  const text = c.raw.toString('latin1');
  assert.match(text, /Content-Disposition: form-data; name="request"\r\nContent-Type: application\/json\r\n\r\n/);
  assert.doesNotMatch(text, /name="request"; filename/);
  assert.match(text, /Content-Disposition: form-data; name="audio"; filename="question.wav"\r\nContent-Type: audio\/wav/);
  const req = JSON.parse(text.match(/\r\n\r\n(\{.*?\})\r\n--/)[1]);
  assert.deepEqual(req, { model: 'muse-voice-transcribe-1.0', mode: 'PUSH_TO_TALK', audioEncoding: 'WAV', languageBias: ['English', 'Arabic'], keywords: ['helpful', 'neighbor'] });
  assert.ok(text.includes('RIFF'));
});

test('transcribe: rejects audio Meta would refuse, without calling Meta', async () => {
  reset();
  const bad = [
    { audio: Buffer.from('hello world, not audio at all').toString('base64') },
    { audio: wav({ channels: 2 }) },
    { audio: wav({ rate: 44100 }) },
    { audio: wav({ seconds: 0.1 }) },
    { audio: '***' },
    {},
  ];
  for (const b of bad) assert.equal((await call(transcribe, b)).status, 400);
  assert.equal((await call(transcribe, { audio: wav({ seconds: 31 }) })).status, 400, 'over 30 seconds');
  assert.equal((await call(transcribe, { audio: wav({ seconds: 50 }) })).status, 413, 'body over size cap');
  assert.equal(calls.length, 0);
});

test('transcribe: 24 kHz accepted; Meta errors handled', async () => {
  reset();
  assert.equal((await call(transcribe, { audio: wav({ rate: 24000 }) })).status, 200);
  for (const [code, body, re] of [
    [400, { error: { message: 'unsupported WAV' } }, /could not read/],
    [401, { error: { message: 'bad key' } }, /rejected the API key/],
    [429, { error: { message: 'slow down' } }, /busy/],
    [500, { error: { message: 'budget' } }, /Meta API error/],
  ]) {
    reset({ asr: (c, send) => send(code, body) });
    const out = await call(transcribe, { audio: wav() });
    assert.equal(out.status, 502);
    assert.match(out.body.error, re);
    noLeak(out);
  }
  reset({ asr: (c, send) => send(200, 'plain text', 'text/plain') });
  assert.equal((await call(transcribe, { audio: wav() })).status, 502);
  reset({ asr: (c, send) => send(200, { sessionId: 'x' }) });
  assert.equal((await call(transcribe, { audio: wav() })).status, 502, 'missing transcript field');
});

test('transcribe: per-IP limit (12/min)', async () => {
  reset();
  const codes = [];
  for (let i = 0; i < 13; i++) codes.push((await call(transcribe, { audio: wav({ seconds: 0.5 }) }, { ip: '172.20.0.5' })).status);
  assert.equal(codes.filter((c) => c === 200).length, 12);
  assert.equal(codes.at(-1), 429);
});

test.after(() => mock.close());

// ---- Android app: hands-free question ("ask") and app access without a browser Origin ----

const ASK_FULL = { is_vocab: true, term: 'reliable', sentence: 'The bus is very reliable.', answer_en: 'Reliable means you can trust it to work well.', answer_ar: 'موثوق: يمكنك الاعتماد عليه.', example: 'My phone is reliable.' };

test('android: no Origin + X-Sada-Client header is accepted in production; neither is rejected', async () => {
  reset({ content: ASK_FULL });
  const ok = await call(ai, { task: 'ask', payload: { question: 'What does reliable mean?', lang: 'both' } }, { origin: null, headers: { 'x-sada-client': 'android' } });
  assert.equal(ok.status, 200);
  const bad = await call(ai, { task: 'ask', payload: { question: 'What does reliable mean?' } }, { origin: null });
  assert.equal(bad.status, 403);
  const evil = await call(ai, { task: 'ask', payload: { question: 'x' } }, { origin: 'https://evil.example', headers: { 'x-sada-client': 'android' } });
  assert.equal(evil.status, 403, 'a foreign browser Origin is still refused even with the app header');
});

test('ask: request to Muse Spark carries the question, real context only, and the spoken-answer schema', async () => {
  reset({ content: ASK_FULL });
  const out = await call(ai, { task: 'ask', payload: { question: 'What does reliable mean?', lang: 'both', level: 'B1', episode: 'Sada test episode 2', positionSec: 75.4, recent: 'The bus is very reliable.' } },
    { origin: null, headers: { 'x-sada-client': 'android' } });
  assert.equal(out.status, 200);
  assert.equal(out.body.source, 'meta');
  assert.deepEqual(out.body.result, ASK_FULL);
  const j = calls.at(-1).json;
  assert.equal(j.response_format.json_schema.name, 'SpokenAnswer');
  const prompt = JSON.stringify(j.messages);
  assert.ok(prompt.includes('What does reliable mean?'));
  assert.ok(prompt.includes('Sada test episode 2') && prompt.includes('1:15'), 'title and position');
  assert.ok(prompt.includes('APPROXIMATE'), 'context is labelled approximate');
  assert.ok(prompt.includes('BOTH languages'));
  noLeak(out);
});

test('ask: without a transcript the model is told not to invent one', async () => {
  reset({ content: ASK_FULL });
  await call(ai, { task: 'ask', payload: { question: 'Explain that sentence.' } }, { origin: null, headers: { 'x-sada-client': 'android' } });
  assert.ok(JSON.stringify(calls.at(-1).json.messages).includes('do not invent'));
});

test('ask: language "ar" / "en" keep only the chosen answer', async () => {
  reset({ content: ASK_FULL });
  const ar = await call(ai, { task: 'ask', payload: { question: 'What does reliable mean?', lang: 'ar' } }, { origin: null, headers: { 'x-sada-client': 'android' } });
  assert.equal(ar.body.result.answer_en, '');
  assert.ok(ar.body.result.answer_ar.length > 0);
  const en = await call(ai, { task: 'ask', payload: { question: 'What does reliable mean?', lang: 'en' } }, { origin: null, headers: { 'x-sada-client': 'android' } });
  assert.equal(en.body.result.answer_ar, '');
});

test('ask: empty or oversized questions are rejected before calling Meta', async () => {
  reset({ content: ASK_FULL });
  const empty = await call(ai, { task: 'ask', payload: { question: '   ' } }, { origin: null, headers: { 'x-sada-client': 'android' } });
  assert.equal(empty.status, 400);
  const big = await call(ai, { task: 'ask', payload: { question: 'x'.repeat(600) } }, { origin: null, headers: { 'x-sada-client': 'android' } });
  assert.equal(big.status, 400);
  assert.equal(calls.length, 0, 'Meta was not called');
});

test('transcribe: accepted from the Android app without a browser Origin', async () => {
  reset();
  const wav = Buffer.alloc(44 + 32000);
  wav.write('RIFF', 0); wav.writeUInt32LE(36 + 32000, 4); wav.write('WAVE', 8); wav.write('fmt ', 12); wav.writeUInt32LE(16, 16);
  wav.writeUInt16LE(1, 20); wav.writeUInt16LE(1, 22); wav.writeUInt32LE(16000, 24); wav.writeUInt32LE(32000, 28); wav.writeUInt16LE(2, 32); wav.writeUInt16LE(16, 34);
  wav.write('data', 36); wav.writeUInt32LE(32000, 40);
  const out = await call(transcribe, { audio: wav.toString('base64') }, { origin: null, headers: { 'x-sada-client': 'android' } });
  assert.equal(out.status, 200);
  assert.equal(out.body.text, 'What does helpful mean?');
});

// ---- transcribe: WAV parsing by chunks, size limit for the longest allowed recording ----

function wavWith({ rate = 16000, seconds = 1, chunksBefore = [], format = 1, channels = 1, bits = 16 } = {}) {
  const pcm = Buffer.alloc(Math.round(rate * seconds) * 2, 1);
  const fmt = Buffer.alloc(24);
  fmt.write('fmt ', 0); fmt.writeUInt32LE(16, 4); fmt.writeUInt16LE(format, 8); fmt.writeUInt16LE(channels, 10);
  fmt.writeUInt32LE(rate, 12); fmt.writeUInt32LE(rate * channels * bits / 8, 16); fmt.writeUInt16LE(channels * bits / 8, 20); fmt.writeUInt16LE(bits, 22);
  const extra = chunksBefore.map(([id, body]) => {
    const h = Buffer.alloc(8); h.write(id, 0); h.writeUInt32LE(body.length, 4);
    return Buffer.concat([h, body, body.length % 2 ? Buffer.alloc(1) : Buffer.alloc(0)]);
  });
  const dataH = Buffer.alloc(8); dataH.write('data', 0); dataH.writeUInt32LE(pcm.length, 4);
  const body = Buffer.concat([Buffer.from('WAVE'), ...extra, fmt, dataH, pcm]);
  const riff = Buffer.alloc(8); riff.write('RIFF', 0); riff.writeUInt32LE(body.length, 4);
  return Buffer.concat([riff, body]);
}

test('transcribe: WAV with extra chunks (LIST, odd size) is read by chunks and sent to Meta as a canonical WAV', async () => {
  reset();
  const wav = wavWith({ chunksBefore: [['LIST', Buffer.from('INFOISFT\x05\x00\x00\x00Lavf\x00')], ['junk', Buffer.from('abc')]] });
  const out = await call(transcribe, { audio: wav.toString('base64') });
  assert.equal(out.status, 200);
  const raw = calls.at(-1).raw;
  const at = raw.indexOf(Buffer.from('RIFF'));
  assert.equal(raw.toString('ascii', at + 12, at + 16), 'fmt ', 'fmt chunk directly after the header: canonical layout');
  assert.equal(raw.toString('ascii', at + 36, at + 40), 'data');
  assert.equal(raw.indexOf(Buffer.from('LIST')), -1, 'extra chunks are not forwarded');
});

test('transcribe: 30 s at 24 kHz fits the request limit; longer is refused clearly', async () => {
  reset();
  const ok = await call(transcribe, { audio: wavWith({ rate: 24000, seconds: 29.9 }).toString('base64') });
  assert.equal(ok.status, 200, 'the longest allowed recording at 24 kHz is accepted');
  const long = await call(transcribe, { audio: wavWith({ rate: 16000, seconds: 31 }).toString('base64') });
  assert.equal(long.status, 400);
  assert.match(long.body.error, /under 30 seconds/);
});

test('transcribe: non-PCM, stereo, 8-bit and wrong rates are refused before calling Meta', async () => {
  reset();
  for (const opts of [{ format: 3 }, { channels: 2 }, { bits: 8 }, { rate: 44100 }]) {
    const out = await call(transcribe, { audio: wavWith(opts).toString('base64') });
    assert.equal(out.status, 400, JSON.stringify(opts));
  }
  assert.equal(calls.length, 0);
});

// ---- the answer must match the requested schema (model / format fallbacks cannot guarantee it) ----

test('a malformed answer is retried once, then refused, never passed to the app', async () => {
  reset({ content: { answer_en: 'Reliable means trustworthy.' } }); // missing required fields, as an unconstrained fallback might return
  const out = await call(ai, { task: 'ask', payload: { question: 'What does reliable mean?' } }, { origin: null, headers: { 'x-sada-client': 'android' } });
  assert.equal(out.status, 502);
  assert.match(out.body.error, /incomplete/);
  assert.equal(calls.length, 2, 'retried exactly once');
});

test('wrong field types are refused too (e.g. is_vocab as text)', async () => {
  reset({ content: { ...FULL, is_vocab: 'yes' } });
  const out = await call(ai, { task: 'explain', payload: { ...EXPLAIN, lang: 'both' } });
  assert.equal(out.status, 502);
});

test('a malformed first answer followed by a valid one succeeds', async () => {
  let n = 0;
  reset({ chat: (j, send) => {
    n++;
    const content = n === 1 ? { term: 'reliable' } : ASK_FULL;
    send(200, { model: j.model, choices: [{ message: { role: 'assistant', content: JSON.stringify(content) }, finish_reason: 'stop' }] });
  } });
  const out = await call(ai, { task: 'ask', payload: { question: 'What does reliable mean?' } }, { origin: null, headers: { 'x-sada-client': 'android' } });
  assert.equal(out.status, 200);
  assert.deepEqual(out.body.result, ASK_FULL);
});

test('schema-fallback path: Meta rejects response_format, the plain answer is still validated', async () => {
  let n = 0;
  reset({ chat: (j, send) => {
    n++;
    if (j.response_format) return send(400, { error: { message: 'response_format not supported', param: 'response_format' } });
    send(200, { model: j.model, choices: [{ message: { role: 'assistant', content: 'Sure! Reliable means trustworthy.' } , finish_reason: 'stop' }] });
  } });
  const out = await call(ai, { task: 'ask', payload: { question: 'What does reliable mean?' } }, { origin: null, headers: { 'x-sada-client': 'android' } });
  assert.equal(out.status, 502, 'plain prose is never presented as a structured answer');
});
