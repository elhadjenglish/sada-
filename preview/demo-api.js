// PREVIEW ONLY. Replaces the app's /api calls with clearly labelled sample answers so the interface
// can be tried without a Meta key. Not part of the production build (Vite builds src/main.jsx only).
window.__SADA_DEMO__ = true;

// Hand-written sample entries for words in the six episodes. [lemma, pos, ipa, English, Arabic, example]
const ENTRIES = [
  ['helpful', 'adjective', 'ˈhelpfəl', 'happy to help others; useful', 'خدوم، مُفيد', 'The nurse was very helpful to my mother.'],
  ['retired', 'adjective', 'rɪˈtaɪərd', 'no longer working because of age', 'متقاعد', 'My grandfather is retired and loves gardening.'],
  ['bored', 'adjective', 'bɔːrd', 'tired because nothing is interesting', 'يشعر بالملل', 'The children were bored on the long drive.'],
  ['homework', 'noun', 'ˈhoʊmwɜːrk', 'school work you do at home', 'واجب منزلي', 'I finish my homework before dinner.'],
  ['nervous', 'adjective', 'ˈnɜːrvəs', 'a little afraid or worried', 'متوتّر، قَلِق', 'She was nervous before her exam.'],
  ['comfortable', 'adjective', 'ˈkʌmftəbl', 'pleasant and relaxing to sit in or use', 'مُريح', 'This sofa is very comfortable.'],
  ['offer', 'verb', 'ˈɔːfər', 'to give or say you will give something', 'عَرَضَ، قدّم', 'He offered me a cup of tea.'],
  ['journey', 'noun', 'ˈdʒɜːrni', 'a trip from one place to another', 'رحلة', 'The journey to the coast took three hours.'],
  ['village', 'noun', 'ˈvɪlɪdʒ', 'a very small town in the countryside', 'قرية', 'My parents grew up in a small village.'],
  ['cousin', 'noun', 'ˈkʌzn', 'the child of your aunt or uncle', 'ابن أو بنت العمّ أو الخال', 'My cousin lives in Oran.'],
  ['bright', 'adjective', 'braɪt', 'full of light', 'مُشرِق', 'The room is bright in the morning.'],
  ['prepare', 'verb', 'prɪˈper', 'to get ready for something', 'استعدّ، حضّر', 'I prepared for the test all week.'],
  ['shake', 'verb', 'ʃeɪk', 'to move quickly up and down or side to side', 'يرتجف، يهتزّ', 'His hands were shaking from the cold.'],
  ['interview', 'noun', 'ˈɪntərvjuː', 'a meeting to decide if you are right for a job', 'مقابلة', 'I have a job interview tomorrow.'],
  ['breath', 'noun', 'breθ', 'the air you take into your body', 'نَفَس', 'Take a deep breath and relax.'],
  ['reliable', 'adjective', 'rɪˈlaɪəbl', 'you can trust them to do what they promise', 'يُعتمَد عليه، موثوق', 'My old car is still very reliable.'],
  ['promise', 'verb', 'ˈprɑːmɪs', 'to say you will surely do something', 'وَعَدَ', 'She promised to call me tonight.'],
  ['organize', 'verb', 'ˈɔːrɡənaɪz', 'to plan and arrange an event', 'نظّم', 'We organized a party for our teacher.'],
  ['charity', 'noun', 'ˈtʃærəti', 'help or money given to people in need', 'عمل خيري', 'They give money to charity every month.'],
  ['budget', 'noun', 'ˈbʌdʒɪt', 'the money you have to spend on something', 'ميزانية', 'Our budget for the trip is small.'],
  ['carefully', 'adverb', 'ˈkerfəli', 'with attention, to avoid mistakes', 'بعناية', 'Read the question carefully.'],
  ['disappointed', 'adjective', 'ˌdɪsəˈpɔɪntɪd', 'sad because something did not happen as you hoped', 'خائب الأمل', 'He was disappointed with his score.'],
  ['feedback', 'noun', 'ˈfiːdbæk', 'advice about how well you did something', 'ملاحظات، تقييم', 'My teacher gave me useful feedback.'],
  ['practice', 'verb', 'ˈpræktɪs', 'to do something again and again to improve', 'تمرّن، تدرّب', 'I practice English every day.'],
  ['eventually', 'adverb', 'ɪˈventʃuəli', 'in the end, after a long time', 'في النهاية', 'We eventually found the right street.'],
  ['opportunity', 'noun', 'ˌɑːpərˈtuːnəti', 'a good chance to do something', 'فرصة', 'This job is a great opportunity.'],
  ['rhythm', 'noun', 'ˈrɪðəm', 'a regular repeated pattern', 'إيقاع', 'The city has a slower rhythm in summer.'],
  ['calm', 'adjective', 'kɑːm', 'quiet and peaceful', 'هادئ', 'The sea is calm today.'],
  ['energy', 'noun', 'ˈenərdʒi', 'the strength to be active', 'طاقة', 'I have no energy after work.'],
  ['hurry', 'verb', 'ˈhɜːri', 'to move or do things quickly', 'يُسرِع، يستعجل', 'We hurried to catch the bus.'],
  ['sunset', 'noun', 'ˈsʌnset', 'the time when the sun goes down', 'غروب الشمس', 'We walked on the beach at sunset.'],
  ['fast', 'noun', 'fæst', 'a time when you do not eat or drink', 'الصيام', 'They broke their fast with dates.'],
  ['relative', 'noun', 'ˈrelətɪv', 'a person in your family', 'قريب (من العائلة)', 'Many relatives came to the wedding.'],
  ['memorable', 'adjective', 'ˈmemərəbl', 'special and easy to remember', 'لا يُنسى', 'It was a memorable holiday.'],
  ['patience', 'noun', 'ˈpeɪʃns', 'the ability to wait calmly', 'الصبر', 'Learning a language takes patience.'],
  ['generous', 'adjective', 'ˈdʒenərəs', 'happy to give things to others', 'كريم', 'Our neighbors are very generous.'],
  ['stranger', 'noun', 'ˈstreɪndʒər', 'a person you do not know', 'غريب', 'A stranger asked me for directions.'],
  ['confident', 'adjective', 'ˈkɑːnfɪdənt', 'sure about yourself or your answer', 'واثق', 'She felt confident before the match.'],
  ['forget', 'verb', 'fərˈɡet', 'to stop remembering something', 'نَسِيَ', "Don't forget your keys."],
  ['review', 'verb', 'rɪˈvjuː', 'to study something again', 'راجَعَ', 'I review my notes before class.'],
  ['repetition', 'noun', 'ˌrepəˈtɪʃn', 'doing something again and again', 'التكرار', 'Repetition helps you remember.'],
  ['context', 'noun', 'ˈkɑːntekst', 'the words and situation around something', 'السياق', 'Guess the meaning from the context.'],
  ['emotion', 'noun', 'ɪˈmoʊʃn', 'a feeling such as joy or fear', 'شعور، عاطفة', 'Music can bring strong emotions.'],
  ['useful', 'adjective', 'ˈjuːsfl', 'helps you do something', 'مُفيد', 'This app is useful for learning words.'],
  ['logbook', 'noun', 'ˈlɔːɡbʊk', 'a book where you record daily events', 'سِجلّ يومي', 'The captain wrote in the logbook.'],
  ['automatic', 'adjective', 'ˌɔːtəˈmætɪk', 'works by itself without a person', 'آليّ', 'The doors are automatic.'],
  ['complain', 'verb', 'kəmˈpleɪn', 'to say you are not happy about something', 'اشتكى', 'He never complains about work.'],
  ['patient', 'adjective', 'ˈpeɪʃnt', 'able to wait calmly', 'صبور', 'Be patient, the bus is coming.'],
  ['attention', 'noun', 'əˈtenʃn', 'careful watching or listening', 'الانتباه', 'Please pay attention to the teacher.'],
  ['accept', 'verb', 'əkˈsept', 'to agree that something is true or OK', 'تقبّل، قَبِلَ', 'I accepted his apology.'],
  ['mainland', 'noun', 'ˈmeɪnlænd', 'the main area of land, not an island', 'البرّ الرئيسي', 'The boat goes to the mainland every day.'],
];
const DICT = new Map(ENTRIES.map((e) => [e[0], e]));

function lookup(token) {
  const t = String(token || '').toLowerCase().replace(/[^a-z'-]/g, '');
  const tries = [t, t.replace(/ies$/, 'y'), t.replace(/ing$/, ''), t.replace(/ing$/, 'e'), t.replace(/ed$/, ''), t.replace(/ed$/, 'e'),
    t.replace(/d$/, ''), t.replace(/ied$/, 'y'), t.replace(/es$/, ''), t.replace(/s$/, ''), t.replace(/'s$/, '')];
  for (const k of tries) if (DICT.has(k)) return DICT.get(k);
  return null;
}

const SAMPLE_WORDS = 'helpful, reliable, nervous, eventually, opportunity, generous, patience';

function explain(p) {
  const asked = p.word || (String(p.question || '').match(/what does (?:the word )?(.+?) mean/i) || [])[1] || '';
  const e = lookup(asked) || lookup(asked.split(' ').pop());
  if (!e) {
    // Explicit "not available" result: the UI shows a clear notice and saves nothing.
    return { demo_unavailable: true, is_vocab: false, word: asked || 'this', sample_words: SAMPLE_WORDS };
  }
  const [lemma, pos, ipa, en, ar, example] = e;
  const shown = p.word || lemma;
  return {
    is_vocab: true, word: shown, lemma, pos, ipa, meaning_en: en, meaning_ar: ar, example,
    in_context_en: `In this sentence, “${shown}” means ${en}.`,
    in_context_ar: `في هذه الجملة، كلمة «${shown}» تعني: ${ar}.`,
    answer_en: '', answer_ar: '',
  };
}

const shuffle = (a) => [...a].sort(() => Math.random() - 0.5);

function exercises(p) {
  const out = [];
  const others = ENTRIES.map((e) => e[3]);
  for (const w of p.words || []) {
    const e = lookup(w.word);
    const en = e ? e[3] : w.meaning_en;
    const example = e ? e[5] : '';
    if (en) {
      out.push({ type: 'context', word: w.word, sentence: example || `They talked about the word “${w.word}”.`, prompt: `What does “${w.word}” mean here?`,
        options: [en, ...shuffle(others.filter((o) => o !== en)).slice(0, 3)], answer_index: 0, explanation: `“${w.word}” means ${en}.` });
    }
    const re = new RegExp(`\\b${(e ? e[0] : w.word).slice(0, -1)}\\w*`, 'i');
    if (example && re.test(example)) {
      const form = example.match(re)[0];
      out.push({ type: 'fill', word: w.word, sentence: example.replace(re, '___'), answer: form, accept: [], hint: e[4] });
    }
    if (w.struggling && example) out.push({ type: 'order', word: w.word, answer: example.replace(/[.!?]$/, '') });
    if (w.struggling) out.push({ type: 'explain', word: w.word, prompt: `Explain what “${w.word}” means and give your own example.` });
  }
  return { exercises: shuffle(out).slice(0, Math.max(3, Math.min(10, p.count || 6))) };
}

function grade(p) {
  const words = String(p.answer || '').toLowerCase().split(/\s+/).filter(Boolean);
  const target = String(p.exercise?.word || '').toLowerCase();
  const stem = target.slice(0, Math.max(3, target.length - 2));
  const correct = words.length >= 3 && words.some((w) => w.startsWith(stem));
  return {
    correct, score: correct ? 1 : 0,
    feedback_en: correct ? 'Demo check: your answer uses the word in a full sentence.' : `Demo check: use “${p.exercise?.word}” in a full sentence of at least three words.`,
    feedback_ar: correct ? 'فحص تجريبي: إجابتك تستخدم الكلمة في جملة كاملة.' : `فحص تجريبي: استخدم كلمة «${p.exercise?.word}» في جملة كاملة من ثلاث كلمات على الأقل.`,
    better: p.exercise?.reference_en || '',
  };
}

const json = (status, body) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const realFetch = window.fetch.bind(window);

async function handle(url, body) {
  if (url === '/api/transcribe') {
    // No speech recognition in the preview: the recording is NOT transcribed. The app says so.
    const kw = (body.keywords || []).find((k) => lookup(k));
    return json(200, { text: kw ? `(demo) What does ${kw} mean?` : 'continue', source: 'demo' });
  }
  if (url === '/api/ai') {
    const { task, payload = {} } = body;
    if (task === 'ping') return json(503, { error: 'This is the demo preview. It is not connected to Meta, so no real AI requests are made.' });
    if (task === 'explain') return json(200, { result: explain(payload), source: 'demo' });
    if (task === 'exercises') return json(200, { result: exercises(payload), source: 'demo' });
    if (task === 'grade') return json(200, { result: grade(payload), source: 'demo' });
  }
  return json(404, { error: 'Not available in the demo preview.' });
}

window.fetch = async (input, init = {}) => {
  const url = typeof input === 'string' ? input : input?.url || '';
  if (!url.startsWith('/api/')) return realFetch(input, init);
  let body = {};
  try { body = JSON.parse(init.body || '{}'); } catch {}
  await wait(400 + Math.random() * 300);
  if (init.signal?.aborted) throw new DOMException('Aborted', 'AbortError');
  try {
    return await handle(url, body);
  } catch (e) {
    console.error('[demo]', e);
    return json(500, { error: 'The demo answer failed. Try another word.' });
  }
};
