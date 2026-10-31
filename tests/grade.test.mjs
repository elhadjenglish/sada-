// MOCKED tests for open-answer grading: the real /api/ai handler and verdict logic against a local fake of Meta.
// They prove what Sada asks the model and how it turns the model's rubric into a verdict.
// They cannot prove how the real Muse Spark fills the rubric; that needs the live check in TESTING.md.
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';

let replies = [];
const calls = [];
const mock = http.createServer(async (req, res) => {
  let b = ''; for await (const c of req) b += c;
  calls.push(JSON.parse(b));
  const content = replies.shift();
  res.writeHead(200, { 'content-type': 'application/json' });
  res.end(JSON.stringify({ id: 'x', object: 'chat.completion', model: 'muse-spark-1.3',
    choices: [{ index: 0, message: { role: 'assistant', content: JSON.stringify(content) }, finish_reason: 'stop' }] }));
});
await new Promise((r) => mock.listen(0, r));
process.env.META_BASE_URL = `http://127.0.0.1:${mock.address().port}/v1`;
process.env.MODEL_API_KEY = 'sk-test-grade';
const ai = (await import('../api/ai.js')).default;
const { containsWord, decide } = await import('../api/_grade.js');
console.info = () => {};

let ip = 0;
const grade = (exercise, answer, lang = 'both') => new Promise((resolve) => {
  const out = {};
  const res = { setHeader() {}, status(c) { out.status = c; return this; }, json(d) { out.body = d; resolve(out); } };
  ai({ method: 'POST', headers: { host: 's.example', origin: 'https://s.example', 'content-type': 'application/json', 'x-real-ip': `10.2.0.${++ip}` },
    body: { task: 'grade', payload: { exercise, answer, level: 'B1', lang } } }, res);
});
const rubric = (o = {}) => ({ meaning: 'matches', uses_target_word: true, grammar: 'correct', grammar_note_en: '', grammar_note_ar: '', feedback_en: 'Good.', feedback_ar: 'جيد.', corrected: '', ...o });

// The exact exercise from the bug report, including the unfaithful generated reference.
const BREATH = { type: 'translate', word: 'breath', source_ar: 'خذ نفسًا عميقًا قبل أن تبدأ الكلام.', reference_en: 'He took a deep breath before speaking.' };
const ANSWERS = ['Take a deep breath before speaking.', 'I take a deep breath before I speak.', 'He took a deep breath before speaking.'];

test('bug report: all three valid answers are accepted, with positive feedback and no "better" sentence', async () => {
  for (const answer of ANSWERS) {
    replies = [rubric()];
    const out = await grade(BREATH, answer);
    assert.equal(out.status, 200, answer);
    assert.equal(out.body.result.correct, true, answer);
    assert.equal(out.body.result.score, 1);
    assert.match(out.body.result.feedback_en, /^Correct!/);
    assert.match(out.body.result.feedback_ar, /صحيحة/);
    assert.equal(out.body.result.better, '', 'no rival sentence shown for a correct answer');
  }
});

test('bug report: the grader is told the Arabic defines the task and the reference is only an example', async () => {
  replies = [rubric()];
  calls.length = 0;
  await grade(BREATH, ANSWERS[0]);
  const prompt = calls[0].messages[1].content;
  assert.ok(prompt.includes(BREATH.source_ar), 'Arabic source is in the prompt');
  assert.match(prompt, /Arabic sentence is the ONLY definition of what is required/);
  assert.match(prompt, /just one of many acceptable ones/);
  assert.match(prompt, /never require its wording, tense, subject or structure/);
  assert.match(prompt, /any pronoun \(I\/you\/he\/she\/we\/they\), any tense/);
  assert.match(prompt, /Never invent a requirement/);
  assert.match(prompt, /A different tense or subject from the example is NOT a grammar issue/);
  assert.equal(calls[0].response_format.json_schema.name, 'GradeRubric');
});

test('bug report: word detected by Sada even if the model wrongly says it is missing', async () => {
  replies = [rubric({ uses_target_word: false })];
  const out = await grade(BREATH, 'Take a deep breath before speaking.');
  assert.equal(out.body.result.correct, true);
});

test('minor grammar issue: still correct, issue explained, minimal correction of the learner’s own sentence', async () => {
  replies = [rubric({ grammar: 'minor_issue', grammar_note_en: '"I" takes "take", not "takes".', grammar_note_ar: 'مع I نستخدم take وليس takes.', corrected: 'I take a deep breath before I speak.' })];
  const out = await grade(BREATH, 'I takes a deep breath before I speak.');
  const r = out.body.result;
  assert.equal(r.correct, true);
  assert.equal(r.score, 0.8);
  assert.match(r.feedback_en, /Correct meaning! One small fix: "I" takes "take"/);
  assert.match(r.feedback_ar, /المعنى صحيح/);
  assert.equal(r.better, 'I take a deep breath before I speak.');
});

test('meaning-breaking grammar error is not accepted', async () => {
  replies = [rubric({ grammar: 'error', feedback_en: 'The sentence is hard to understand.', corrected: 'Take a deep breath before speaking.' })];
  const out = await grade(BREATH, 'Breath deep take speaking before.');
  assert.equal(out.body.result.correct, false);
});

test('wrong meaning is not accepted', async () => {
  replies = [rubric({ meaning: 'wrong', feedback_en: 'This says something different from the Arabic.' })];
  const out = await grade(BREATH, 'I hold my breath under water.');
  assert.equal(out.body.result.correct, false);
  assert.equal(out.body.result.score, 0);
  assert.match(out.body.result.feedback_en, /different/);
});

test('partly correct meaning is not accepted, partial score', async () => {
  replies = [rubric({ meaning: 'partly' })];
  const out = await grade(BREATH, 'Take a deep breath.');
  assert.equal(out.body.result.correct, false);
  assert.equal(out.body.result.score, 0.4);
});

test('translation without the target word: meaning right, but asked to use the word', async () => {
  replies = [rubric({ uses_target_word: false })];
  const out = await grade(BREATH, 'Inhale deeply before you talk.');
  assert.equal(out.body.result.correct, false);
  assert.match(out.body.result.feedback_en, /meaning is right, but the exercise asks you to use “breath”/);
});

test('other translations: different tense, pronoun and word order accepted', async () => {
  const ex = { type: 'translate', word: 'reliable', source_ar: 'صديقي شخص يُعتمد عليه.', reference_en: 'My friend is a reliable person.' };
  for (const answer of ['My friend is reliable.', 'My friend has always been a reliable person.', 'He is a reliable friend of mine.']) {
    replies = [rubric()];
    const out = await grade(ex, answer);
    assert.equal(out.body.result.correct, true, answer);
  }
  replies = [rubric()];
  const plural = await grade({ type: 'translate', word: 'opportunity', source_ar: 'هذه فرص جيدة.', reference_en: 'These are good opportunities.' }, 'These are great opportunities.');
  assert.equal(plural.body.result.correct, true, 'plural form counts as using the word');
});

test('explain answers: own-words explanation, example-only, and both are accepted; the word is not required', async () => {
  const ex = { type: 'explain', word: 'breath', prompt: 'Explain what “breath” means and give your own example.' };
  for (const answer of ['It is the air that goes in and out of your lungs.', 'Before the race I took a deep breath.', 'Breath means the air you breathe. Example: hold your breath.']) {
    replies = [rubric({ uses_target_word: false })];
    const out = await grade(ex, answer);
    assert.equal(out.body.result.correct, true, answer);
    assert.match(out.body.result.feedback_en, /You clearly understand “breath”/);
  }
  calls.length = 0; replies = [rubric({ meaning: 'wrong' })];
  const wrong = await grade(ex, 'It means to run fast.');
  assert.equal(wrong.body.result.correct, false);
  assert.match(calls[0].messages[1].content, /explanation in their own words, a correct example sentence, or both are all acceptable/);
});

test('feedback language follows the setting', async () => {
  replies = [rubric()];
  const ar = await grade(BREATH, ANSWERS[0], 'ar');
  assert.equal(ar.body.result.feedback_en, '');
  assert.ok(ar.body.result.feedback_ar);
  replies = [rubric()];
  const en = await grade(BREATH, ANSWERS[0], 'en');
  assert.equal(en.body.result.feedback_ar, '');
  assert.ok(en.body.result.feedback_en);
});

test('older response shape still handled if Meta ignores the schema', () => {
  const r = decide({ correct: true, score: 0.9, feedback_en: 'ok' }, { type: 'translate', word: 'breath', answer: ANSWERS[1], lang: 'both' });
  assert.equal(r.correct, true);
  const w = decide({ correct: false, feedback_en: 'no' }, { type: 'translate', word: 'breath', answer: 'x', lang: 'both' });
  assert.equal(w.correct, false);
});

test('word-form detection', () => {
  assert.ok(containsWord('Take a deep breath before speaking.', 'breath'));
  assert.ok(containsWord('She practiced daily', 'practice'));
  assert.ok(containsWord('Many opportunities', 'opportunity'));
  assert.ok(containsWord('I was hurrying', 'hurry'));
  assert.ok(!containsWord('Breathe slowly', 'breath'), '"breathe" is a different word');
  assert.ok(!containsWord('Inhale deeply', 'breath'));
});

test.after(() => mock.close());
