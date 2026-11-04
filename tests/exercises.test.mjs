// MOCKED tests for personal-exercise generation: the real /api/ai handler and validator against a local
// fake of Meta's Chat Completions API, using realistic response bodies. They do not prove what the real
// Muse Spark returns; Vercel's [ai] op=exercises log lines do that.
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';

const KEY = 'sk-test-SECRET-exercises';
const WORDS = ['reliable', 'practiced', 'opportunity'];
const SECRET_CONTENT = ['reliable', 'practiced', 'opportunity', 'Nadia', 'غريب'];
let replies = [];
const calls = [];

const completion = (content, finish = 'stop') => ({
  id: 'chatcmpl-abc123', object: 'chat.completion', created: 1761800000, model: 'muse-spark-1.3',
  choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: finish }],
  usage: { prompt_tokens: 812, completion_tokens: 640, total_tokens: 1452 },
});

const mock = http.createServer(async (req, res) => {
  let body = '';
  for await (const c of req) body += c;
  calls.push(JSON.parse(body));
  const next = replies.shift() || { status: 500, body: { error: { message: 'no reply queued' } } };
  res.writeHead(next.status || 200, { 'content-type': 'application/json' });
  res.end(JSON.stringify(next.body));
});
await new Promise((r) => mock.listen(0, r));
process.env.META_BASE_URL = `http://127.0.0.1:${mock.address().port}/v1`;
process.env.MODEL_API_KEY = KEY;
const ai = (await import('../api/ai.js')).default;
const { normalizeExercises } = await import('../api/_exercises.js');

// Capture server logs to check diagnostics and that no private content is logged.
const logs = [];
for (const m of ['info', 'error', 'log', 'warn']) { const orig = console[m]; console[m] = (...a) => { logs.push(a.join(' ')); if (process.env.SHOW_LOGS) orig(...a); }; }

let ip = 0;
function request(payload) {
  return new Promise((resolve) => {
    const out = {};
    const res = { setHeader() {}, status(c) { out.status = c; return this; }, json(d) { out.body = d; resolve(out); } };
    ai({ method: 'POST', headers: { host: 'sada.example', origin: 'https://sada.example', 'content-type': 'application/json', 'x-real-ip': `10.1.0.${++ip}` },
      body: { task: 'exercises', payload } }, res);
  });
}
const PAYLOAD = {
  level: 'B1', count: 6,
  words: [
    { word: 'reliable', meaning_en: 'you can trust them', meaning_ar: 'موثوق', context: 'She explained that she was reliable.', struggling: true },
    { word: 'practiced', meaning_en: 'did again to improve', meaning_ar: 'تدرّب', context: 'She practiced every day for six months.' },
    { word: 'opportunity', meaning_en: 'a good chance', meaning_ar: 'فرصة', context: 'The company offered her a new opportunity.' },
  ],
};
const start = (...r) => { replies = r; calls.length = 0; logs.length = 0; };
const ok = (content, finish) => ({ status: 200, body: completion(typeof content === 'string' ? content : JSON.stringify(content), finish) });

const VALID = { exercises: [
  { type: 'mcq', word: 'reliable', prompt: 'Which sentence uses “reliable” correctly?', options: ['My reliable car always starts.', 'The soup was reliable.', 'He reliable the door.', 'Reliable is a city.'], answer_index: 0, explanation: 'Reliable describes something you can trust.' },
  { type: 'context', word: 'opportunity', sentence: 'The trip was a great opportunity to practice English.', prompt: 'What does “opportunity” mean here?', options: ['a good chance', 'a problem', 'a ticket', 'a teacher'], answer_index: 0, explanation: 'A chance to do something.' },
  { type: 'fill', word: 'practiced', sentence: 'She ___ the piano every evening.', answer: 'practiced', accept: ['practised'], hint: 'تدرّبت' },
  { type: 'order', word: 'reliable', answer: 'My old phone is still very reliable' },
  { type: 'translate', word: 'opportunity', source_ar: 'هذه فرصة جيدة لك.', reference_en: 'This is a good opportunity for you.' },
  { type: 'explain', word: 'reliable', prompt: 'Explain what “reliable” means and give your own example.' },
] };

// Client validator, loaded from the real Practice.jsx, to prove server output is usable by the app.
const src = fs.readFileSync(new URL('../src/screens/Practice.jsx', import.meta.url), 'utf8');
const norm = (s) => String(s || '').toLowerCase().replace(/[’']/g, "'").replace(/[^a-z0-9' ]+/g, ' ').replace(/\s+/g, ' ').trim();
const clientClean = new Function('norm', 'shuffle', src.slice(src.indexOf('function clean('), src.indexOf('function localDrill(')) + '; return clean;')(norm, (a) => [...a]);
const vocab = WORDS.map((w, i) => ({ id: String(i), word: w, lemma: w }));

test('request: strict per-type schema, every field required, word limited to the learner’s words', async () => {
  start(ok(VALID));
  await request(PAYLOAD);
  const rf = calls[0].response_format;
  assert.equal(rf.type, 'json_schema');
  const items = rf.json_schema.schema.properties.exercises.items;
  assert.equal(items.anyOf.length, 5, 'Practice is English-only: no Arabic translation exercises');
  assert.ok(!JSON.stringify(items).includes('source_ar'));
  for (const v of items.anyOf) {
    assert.deepEqual([...v.required].sort(), Object.keys(v.properties).sort(), 'every property required');
    assert.equal(v.additionalProperties, false);
    assert.deepEqual(v.properties.word.enum, WORDS);
  }
});

test('valid response → 200 with all exercises, and every one passes the app’s own validator', async () => {
  start(ok(VALID));
  const out = await request(PAYLOAD);
  assert.equal(out.status, 200);
  assert.equal(out.body.source, 'meta');
  assert.equal(out.body.result.exercises.length, 6);
  assert.equal(clientClean(out.body.result.exercises, vocab).length, 6);
  assert.equal(calls.length, 1);
});

test('schema-valid but incomplete items (the production failure) → retried, not returned as a broken 200', async () => {
  const sparse = { exercises: [{ type: 'mcq', word: 'reliable', prompt: 'What does reliable mean?' }, { type: 'fill', word: 'practiced', sentence: 'She practiced.' }, { type: 'context', word: 'opportunity' }] };
  start(ok(sparse), ok(VALID));
  const out = await request(PAYLOAD);
  assert.equal(out.status, 200);
  assert.equal(calls.length, 2, 'retried once');
  assert.equal(out.body.result.exercises.length, 6);
  const line = logs.find((l) => l.includes('attempt=1'));
  assert.match(line, /valid=0/);
  assert.match(line, /missing=.*mcq\.options/);
  assert.match(line, /fill\.sentence/);
});

test('incomplete twice → 502 with a clear message (no silent empty session)', async () => {
  const sparse = { exercises: [{ type: 'mcq', word: 'reliable' }] };
  start(ok(sparse), ok(sparse));
  const out = await request(PAYLOAD);
  assert.equal(out.status, 502);
  assert.match(out.body.error, /incomplete twice/);
});

test('Markdown-wrapped JSON with extra text → parsed', async () => {
  start(ok('Here are your exercises:\n```json\n' + JSON.stringify(VALID, null, 2) + '\n```\nGood luck!'));
  const out = await request(PAYLOAD);
  assert.equal(out.status, 200);
  assert.equal(out.body.result.exercises.length, 6);
});

test('malformed JSON twice → 502, logged as parse=failed', async () => {
  start(ok('{"exercises": [{"type": "mcq", "word": "reliable", "options": ['), ok('not json'));
  const out = await request(PAYLOAD);
  assert.equal(out.status, 502);
  assert.equal(logs.filter((l) => l.includes('parse=failed')).length, 2);
});

test('missing exercises list → shape logged, then 502', async () => {
  start(ok({ result: 'ok', note: 'done' }), ok({ questions: 'none' }));
  const out = await request(PAYLOAD);
  assert.equal(out.status, 502);
  assert.ok(logs.some((l) => l.includes('shape=no_exercise_list')));
});

test('unexpected field names and type spellings → normalized', async () => {
  const variant = { questions: [
    { kind: 'multiple_choice', target_word: 'Reliable', question: 'Pick the meaning of reliable', choices: [{ text: 'trustworthy' }, { text: 'heavy' }, { text: 'late' }, { text: 'noisy' }], correct_answer: 'trustworthy' },
    { type: 'fill_in_the_blank', word: 'practice', text: 'I _____ yoga yesterday.', correct_answer: 'practiced' },
    { type: 'Fill-in-the-blank', word: 'opportunity', sentence: 'It is a great [blank].', solution: 'opportunity' },
    { type: 'translation', word: 'opportunity', arabic: 'لا تضيّع الفرصة.', translation: "Don't miss the opportunity." },
    { type: 'multiple_choice', word: 'reliable', question: 'Which is reliable?', options: ['A clock that is always right', 'A broken clock', 'A lost key', 'A cold day'], answer: 'A' },
    { type: 'sentence_building', word: 'reliable', answer: ['She', 'is', 'very', 'reliable'] },
  ] };
  start(ok(variant));
  const out = await request(PAYLOAD);
  assert.equal(out.status, 200);
  const ex = out.body.result.exercises;
  assert.equal(ex.length, 6);
  assert.deepEqual(ex.map((e) => e.type), ['mcq', 'fill', 'fill', 'translate', 'mcq', 'order']);
  assert.equal(ex[0].word, 'reliable');
  assert.equal(ex[0].answer_index, 0);
  assert.equal(ex[1].word, 'practiced', 'dictionary form matched to the saved word');
  assert.equal(ex[1].sentence, 'I ___ yoga yesterday.');
  assert.equal(ex[2].sentence, 'It is a great ___.');
  assert.equal(ex[4].answer_index, 0, 'letter answer');
  assert.equal(clientClean(ex, vocab).length, 6, 'all usable by the app');
});

test('top-level array and words not in the list', async () => {
  const arr = [...VALID.exercises.slice(0, 2), { type: 'explain', word: 'banana', prompt: 'x' }, { type: 'riddle', word: 'reliable' }];
  start(ok(arr));
  const out = await request(PAYLOAD);
  assert.equal(out.status, 200);
  assert.equal(out.body.result.exercises.length, 2);
  const line = logs.find((l) => l.includes('op=exercises'));
  assert.match(line, /word_not_in_list:1/);
  assert.match(line, /unknown_type:1/);
});

test('if Meta rejects the schema, plain JSON is requested and still validated', async () => {
  start({ status: 400, body: { error: { message: 'Invalid schema', type: 'invalid_request_error', param: 'response_format' } } }, ok('```json\n' + JSON.stringify(VALID) + '\n```'));
  const out = await request(PAYLOAD);
  assert.equal(out.status, 200);
  assert.equal(calls.length, 2);
  assert.ok(!('response_format' in calls[1]));
  assert.ok(logs.some((l) => l.includes('format=plain')));
});

test('answer cut off by the token limit → logged with finish reason, retried', async () => {
  start(ok(JSON.stringify(VALID).slice(0, 300), 'length'), ok(VALID));
  const out = await request(PAYLOAD);
  assert.equal(out.status, 200);
  assert.ok(logs.some((l) => l.includes('finish=length') && l.includes('parse=failed')));
});

test('diagnostic logs contain model, format, length, counts — and no key or private content', async () => {
  start(ok(VALID));
  await request(PAYLOAD);
  const line = logs.find((l) => l.includes('op=exercises'));
  assert.match(line, /model=muse-spark-1\.3 format=json_schema finish=stop len=\d+ attempt=1 parse=ok shape=ok items=6 valid=6/);
  start(ok({ exercises: [{ type: 'mcq', word: 'reliable', prompt: 'Nadia غريب' }] }), { status: 401, body: { error: { message: `bad key ${KEY} reliable Nadia` } } });
  await request(PAYLOAD);
  const all = logs.join('\n');
  assert.ok(!all.includes(KEY), 'no API key');
  for (const s of SECRET_CONTENT) assert.ok(!all.includes(s), `no user content: ${s}`);
});

test('normalizeExercises reports reasons with fixed names only', () => {
  const { stats } = normalizeExercises({ exercises: [{ type: 'mcq', word: 'reliable', options: ['a'] }, 'junk', { type: '???', word: 'x' }] }, WORDS);
  assert.deepEqual(stats.dropped, { incomplete: 1, not_object: 1, unknown_type: 1 });
  assert.deepEqual(stats.missing, { 'mcq.prompt': 1, 'mcq.options': 1, 'mcq.answer_index': 1 });
});

test.after(() => mock.close());
