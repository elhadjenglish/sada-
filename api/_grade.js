// Open-answer grading (translations, explanations). The model fills a rubric; the verdict is computed here
// from that rubric, so tense, person, pronouns or word order can't cause a rejection on their own.

const T = { type: 'string' };
export const GRADE_SCHEMA = {
  name: 'GradeRubric',
  schema: {
    type: 'object',
    properties: {
      meaning: { type: 'string', enum: ['matches', 'partly', 'wrong'] },
      uses_target_word: { type: 'boolean' },
      grammar: { type: 'string', enum: ['correct', 'minor_issue', 'error'] },
      grammar_note_en: T,
      grammar_note_ar: T,
      feedback_en: T,
      feedback_ar: T,
      corrected: T,
    },
    required: ['meaning', 'uses_target_word', 'grammar', 'grammar_note_en', 'grammar_note_ar', 'feedback_en', 'feedback_ar', 'corrected'],
    additionalProperties: false,
  },
};

export function gradePrompt({ type, word, level, task, sourceAr, reference, answer, langRule, sourceLang = 'Arabic' }) {
  const what = type === 'translate'
    ? `The learner was asked to translate this ${sourceLang} sentence into English using the word "${word}":
"${sourceAr}"
The ${sourceLang} sentence is the ONLY definition of what is required.${reference ? `
An example translation (just one of many acceptable ones; it may even differ from the original in tense or person, so never require its wording, tense, subject or structure): "${reference}"` : ''}`
    : `The learner was asked: ${task}
They should show they understand "${word}". An explanation in their own words, a correct example sentence, or both are all acceptable.`;
  return `Learner level: ${level}.
${what}
Learner's answer: "${answer}"

Fill the rubric honestly:
- meaning: "matches" if the answer expresses the required meaning; "partly" if part of it is missing or changed; "wrong" if it means something else.
  Different but valid wording is still "matches". Imperatives, statements, any pronoun (I/you/he/she/we/they), any tense, contractions, and reordered clauses are all acceptable
  unless the original sentence itself clearly requires a specific one. Never invent a requirement the exercise did not state.
- uses_target_word: true if "${word}" appears in any correct form (plural, past tense, -ing, etc.).
- grammar: "correct" if the English is grammatical; "minor_issue" if it is understandable with a small mistake (article, agreement, preposition, spelling, word order slip);
  "error" only if the grammar breaks the sentence so the meaning is unclear. A different tense or subject from the example is NOT a grammar issue.
- grammar_note_en / grammar_note_ar: if grammar is not "correct", name the specific issue in one short sentence; otherwise "".
- corrected: if grammar is not "correct", the learner's OWN sentence with the smallest fix (keep their tense, subject and structure); otherwise "".
- feedback_en / feedback_ar: one or two kind, specific sentences about this answer.
${langRule}`;
}

const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
// Detects the target word in common inflected forms, so a correct use is never missed.
export function containsWord(answer, word) {
  const a = ` ${String(answer).toLowerCase().replace(/[^a-z' -]+/g, ' ').replace(/\s+/g, ' ')} `;
  const w = String(word).toLowerCase().trim();
  if (!w) return false;
  if (w.includes(' ')) return a.includes(` ${w} `) || a.includes(` ${w}`);
  const stem = w.replace(/e$/, '');
  const forms = new Set([w, `${w}s`, `${w}es`, `${w}ed`, `${w}d`, `${w}ing`, `${stem}ing`, `${stem}ed`, `${w}${w.at(-1)}ed`, `${w}${w.at(-1)}ing`,
    w.replace(/y$/, 'ies'), w.replace(/y$/, 'ied'), `${w}'s`, `${w}er`, `${w}est`, `${w}ly`]);
  return [...forms].some((f) => new RegExp(`\\s${esc(f)}\\s`).test(a));
}

const pick = (v, list, d) => (list.includes(v) ? v : d);
const clean = (s, n = 400) => (typeof s === 'string' ? s.trim().slice(0, n) : '');

/**
 * Turns the model's rubric into the verdict the app shows.
 * Correct = meaning matches AND (word used, for translations) AND grammar is not a meaning-breaking error.
 * A minor grammar issue is still correct, with the issue explained and a minimal correction.
 * Also accepts the older {correct, feedback} shape if Meta ever ignores the schema.
 */
export function decide(r, { type, word, answer, lang }) {
  const legacy = r && typeof r.correct === 'boolean' && r.meaning == null;
  const meaning = legacy ? (r.correct ? 'matches' : 'wrong') : pick(r?.meaning, ['matches', 'partly', 'wrong'], 'wrong');
  const grammar = pick(r?.grammar, ['correct', 'minor_issue', 'error'], 'correct');
  const usesWord = containsWord(answer, word) || r?.uses_target_word === true;
  const needsWord = type === 'translate';
  const correct = meaning === 'matches' && grammar !== 'error' && (!needsWord || usesWord);

  let feedback_en, feedback_ar, better = '';
  if (correct && grammar === 'correct') {
    feedback_en = type === 'translate' ? `Correct! Your sentence expresses the meaning clearly and uses “${word}” well.` : `Correct! You clearly understand “${word}”.`;
    feedback_ar = type === 'translate' ? `إجابة صحيحة! جملتك تعبّر عن المعنى بوضوح وتستخدم كلمة «${word}» استخدامًا سليمًا.` : `إجابة صحيحة! أنت تفهم معنى «${word}» جيدًا.`;
  } else if (correct) {
    const noteEn = clean(r?.grammar_note_en), noteAr = clean(r?.grammar_note_ar);
    feedback_en = `Correct meaning! One small fix: ${noteEn || 'check the grammar.'}`;
    feedback_ar = `المعنى صحيح! تصحيح بسيط: ${noteAr || 'راجع القواعد.'}`;
    better = clean(r?.corrected, 300);
  } else {
    feedback_en = clean(r?.feedback_en) || (needsWord && !usesWord ? `Use the word “${word}” in your sentence.` : 'Not quite. Check the meaning of the sentence.');
    feedback_ar = clean(r?.feedback_ar) || (needsWord && !usesWord ? `استخدم كلمة «${word}» في جملتك.` : 'ليس تمامًا. راجع معنى الجملة.');
    if (needsWord && !usesWord && meaning === 'matches') {
      feedback_en = `The meaning is right, but the exercise asks you to use “${word}”.`;
      feedback_ar = `المعنى صحيح، لكن التمرين يطلب استخدام كلمة «${word}».`;
    }
    better = clean(r?.corrected, 300);
  }
  if (lang !== 'en' && lang !== 'both') feedback_en = '';
  if (lang === 'en') feedback_ar = '';
  const score = correct ? (grammar === 'correct' ? 1 : 0.8) : meaning === 'partly' ? 0.4 : 0;
  return { correct, score, meaning, grammar, uses_target_word: usesWord, feedback_en, feedback_ar, better };
}
