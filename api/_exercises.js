// Exercise generation: the schema sent to Meta and the server-side validator for what comes back.
// The validator mirrors the client's rules (src/screens/Practice.jsx → clean) so anything accepted
// here is usable in the app; it also tolerates common variations if Meta ever returns plain JSON.

const str = { type: 'string' };
const obj = (properties) => ({ type: 'object', properties, required: Object.keys(properties), additionalProperties: false });

// One variant per exercise type with EVERY field required, and "word" limited to the learner's words.
// Meta constrains decoding to this schema, so each item is complete by construction.
export function exerciseSchema(words, count) {
  const word = { type: 'string', enum: words };
  const four = { type: 'array', items: str, minItems: 4, maxItems: 4 };
  const idx = { type: 'integer', minimum: 0, maximum: 3 };
  return {
    name: 'ExerciseSet',
    schema: obj({
      exercises: {
        type: 'array',
        minItems: Math.min(3, count),
        maxItems: 10,
        items: {
          anyOf: [
            obj({ type: { type: 'string', enum: ['mcq'] }, word, prompt: str, options: four, answer_index: idx, explanation: str }),
            obj({ type: { type: 'string', enum: ['context'] }, word, sentence: str, prompt: str, options: four, answer_index: idx, explanation: str }),
            obj({ type: { type: 'string', enum: ['fill'] }, word, sentence: str, answer: str, accept: { type: 'array', items: str }, hint: str }),
            obj({ type: { type: 'string', enum: ['order'] }, word, answer: str }),
            obj({ type: { type: 'string', enum: ['translate'] }, word, source_ar: str, reference_en: str }),
            obj({ type: { type: 'string', enum: ['explain'] }, word, prompt: str }),
          ],
        },
      },
    }),
  };
}

const TYPE_ALIASES = {
  mcq: 'mcq', multiple_choice: 'mcq', multiplechoice: 'mcq', choice: 'mcq', meaning: 'mcq',
  context: 'context', context_meaning: 'context', meaning_in_context: 'context', in_context: 'context',
  fill: 'fill', fill_blank: 'fill', fill_in_the_blank: 'fill', fill_in_blank: 'fill', gap_fill: 'fill', gap: 'fill', cloze: 'fill',
  order: 'order', word_order: 'order', sentence_order: 'order', build_sentence: 'order', sentence_building: 'order', reorder: 'order',
  translate: 'translate', translation: 'translate',
  explain: 'explain', explanation: 'explain', define: 'explain', definition: 'explain',
};
const normType = (t) => TYPE_ALIASES[String(t ?? '').toLowerCase().trim().replace(/[\s-]+/g, '_')] || null;
const norm = (s) => String(s ?? '').toLowerCase().replace(/[’']/g, "'").replace(/[^a-z0-9' ]+/g, ' ').replace(/\s+/g, ' ').trim();
const text = (v, max = 400) => (typeof v === 'string' || typeof v === 'number' ? String(v).replace(/\s+/g, ' ').trim().slice(0, max) : '');
const first = (o, keys) => { for (const k of keys) if (o[k] != null && o[k] !== '') return o[k]; return undefined; };

function findList(parsed) {
  if (Array.isArray(parsed)) return parsed;
  if (!parsed || typeof parsed !== 'object') return null;
  for (const k of ['exercises', 'questions', 'items', 'tasks']) if (Array.isArray(parsed[k])) return parsed[k];
  for (const k of ['data', 'result', 'output']) { const inner = findList(parsed[k]); if (inner) return inner; }
  return null;
}

// Exact match first; otherwise a single unambiguous match on a shared stem ("practice" ↔ "practiced").
function matchWord(raw, words) {
  const r = norm(raw);
  if (!r) return null;
  const exact = words.find((w) => norm(w) === r);
  if (exact) return exact;
  const stem = (s) => s.replace(/(ing|ed|es|s|d)$/, '');
  const hits = words.filter((w) => { const a = stem(norm(w)), b = stem(r); return a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a)); });
  return hits.length === 1 ? hits[0] : null;
}

function answerIndex(o, options) {
  const raw = first(o, ['answer_index', 'correct_index', 'answerIndex', 'correctIndex', 'correct_option_index']);
  if (Number.isInteger(raw)) return raw;
  if (typeof raw === 'string' && /^\d+$/.test(raw.trim())) return Number(raw);
  const ans = first(o, ['answer', 'correct_answer', 'correct', 'correctAnswer']);
  if (typeof ans === 'string') {
    const t = ans.trim();
    if (/^[A-D]$/i.test(t)) return t.toUpperCase().charCodeAt(0) - 65;
    const i = options.findIndex((x) => norm(x) === norm(t));
    if (i >= 0) return i;
  }
  return -1;
}

function toOptions(v) {
  if (!Array.isArray(v)) return [];
  return v.map((x) => (x && typeof x === 'object' ? text(first(x, ['text', 'option', 'value', 'label'])) : text(x, 200))).filter(Boolean);
}

/**
 * Validates and normalizes a parsed model response.
 * Returns { exercises, stats } where stats contains ONLY counts and fixed field/reason names, never content.
 */
export function normalizeExercises(parsed, words) {
  const stats = { shape: 'ok', items: 0, valid: 0, dropped: {}, missing: {}, types: {} };
  const bump = (bag, k) => { bag[k] = (bag[k] || 0) + 1; };
  const list = findList(parsed);
  if (!list) { stats.shape = parsed && typeof parsed === 'object' ? 'no_exercise_list' : 'not_object'; return { exercises: [], stats }; }
  stats.items = list.length;
  const out = [];
  for (const o of list) {
    if (!o || typeof o !== 'object') { bump(stats.dropped, 'not_object'); continue; }
    const type = normType(first(o, ['type', 'kind', 'exercise_type', 'format']));
    bump(stats.types, type || 'unknown');
    if (!type) { bump(stats.dropped, 'unknown_type'); continue; }
    const word = matchWord(first(o, ['word', 'target', 'target_word', 'vocab', 'term']), words);
    if (!word) { bump(stats.dropped, 'word_not_in_list'); continue; }
    const missing = [];
    let ex = null;
    if (type === 'mcq' || type === 'context') {
      const prompt = text(first(o, ['prompt', 'question', 'instruction', 'stem']));
      const options = toOptions(first(o, ['options', 'choices', 'answers']));
      const sentence = text(first(o, ['sentence', 'context', 'example']));
      const ai = answerIndex(o, options);
      if (!prompt) missing.push('prompt');
      if (options.length < 2) missing.push('options');
      if (ai < 0 || ai >= options.length) missing.push('answer_index');
      if (type === 'context' && !sentence) missing.push('sentence');
      ex = { type, word, prompt, options, answer_index: ai, explanation: text(first(o, ['explanation', 'feedback', 'rationale'])) };
      if (sentence) ex.sentence = sentence;
    } else if (type === 'fill') {
      let sentence = text(first(o, ['sentence', 'text', 'question', 'prompt']));
      if (sentence && !sentence.includes('___')) sentence = sentence.replace(/_{2,}|\[\s*blank\s*\]|\(\s*blank\s*\)|\{\s*blank\s*\}|…|\.\.\./i, '___');
      if (sentence) sentence = sentence.replace(/_{3,}/g, '___');
      const answer = text(first(o, ['answer', 'correct_answer', 'solution', 'correct']), 60);
      if (!sentence || !sentence.includes('___')) missing.push('sentence');
      if (!answer) missing.push('answer');
      ex = { type, word, sentence, answer, accept: toOptions(first(o, ['accept', 'alternatives', 'accepted'])), hint: text(o.hint, 120) };
    } else if (type === 'order') {
      let answer = first(o, ['answer', 'sentence', 'correct_sentence', 'solution']);
      if (Array.isArray(answer)) answer = answer.join(' ');
      answer = text(answer, 200);
      const n = answer ? answer.split(/\s+/).length : 0;
      if (n < 3 || n > 12) missing.push('answer');
      ex = { type, word, answer };
    } else if (type === 'translate') {
      const source_ar = text(first(o, ['source_ar', 'arabic', 'source', 'sentence_ar', 'prompt_ar']));
      const reference_en = text(first(o, ['reference_en', 'reference', 'translation', 'answer', 'english']));
      if (!source_ar) missing.push('source_ar');
      if (!reference_en) missing.push('reference_en');
      ex = { type, word, source_ar, reference_en };
    } else if (type === 'explain') {
      ex = { type, word, prompt: text(first(o, ['prompt', 'question', 'instruction'])) || `Explain what “${word}” means and give your own example.` };
    }
    if (missing.length) { missing.forEach((f) => bump(stats.missing, `${type}.${f}`)); bump(stats.dropped, 'incomplete'); continue; }
    out.push(ex);
  }
  stats.valid = out.length;
  return { exercises: out, stats };
}

// One-line, content-free summary for logs: counts and fixed names only.
export function describeStats(s) {
  const kv = (o) => Object.entries(o).map(([k, v]) => `${k}:${v}`).join(',') || '-';
  return `shape=${s.shape} items=${s.items} valid=${s.valid} types=${kv(s.types)} dropped=${kv(s.dropped)} missing=${kv(s.missing)}`;
}
