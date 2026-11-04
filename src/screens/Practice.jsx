import React, { useMemo, useRef, useState } from 'react';
import { useStore, gradeWord, logActivity } from '../lib/store.js';
import { pickTargets, isStruggling } from '../lib/srs.js';
import { norm, shuffle, levenshtein } from '../lib/text.js';
import { askAI, transcribe } from '../lib/ai.js';
import { speakOnce } from '../lib/speech.js';
import { recordingSupported, startRecording, micMessage } from '../lib/recorder.js';
import { Icon, Ar, Bi, Spinner, toast, DemoTag } from '../ui.jsx';
import { ProgressBar } from './Today.jsx';

const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

export default function Practice({ go }) {
  const s = useStore();
  const [phase, setPhase] = useState('start');
  const [exercises, setExercises] = useState([]);
  const [i, setI] = useState(0);
  const [error, setError] = useState('');
  const [aiMode, setAiMode] = useState(true);
  const [exSource, setExSource] = useState(null);
  const results = useRef({});
  const lang = 'en'; // Practice is for learning English: always English, whatever the explanation language
  const targets = useMemo(() => pickTargets(s.words, 6), [phase === 'start' ? s.words : null]); // eslint-disable-line

  if (s.words.length === 0) {
    return (
      <div className="screen">
        <header className="top"><h1 className="h1">Practice</h1></header>
        <div className="empty-state">
          <p className="h3">Practice is built from your words.</p>
          <p className="muted">There are no fixed lessons here. Catch a few words while listening, and Sada writes exercises around exactly those words.</p>
          <button className="btn primary" onClick={() => go('listen')}>Catch your first words</button>
        </div>
      </div>
    );
  }

  const start = async (useAI) => {
    setError('');
    setAiMode(useAI);
    results.current = {};
    setI(0);
    if (!useAI) {
      const ex = withSayIt(localDrill(targets, s.words, lang), targets);
      if (!ex.length) return setError('Save a few more words (with meanings) for an offline drill.');
      setExercises(ex);
      return setPhase('run');
    }
    setPhase('loading');
    try {
      const payload = targets.map((w) => ({ word: w.word, meaning_en: clip(w.meaning_en, 160), context: clip(w.contexts?.[0]?.sentence || '', 400), struggling: isStruggling(w) }));
      const r = await askAI('exercises', { words: payload, level: s.profile.level, lang: 'en', count: Math.min(8, Math.max(4, Math.round(targets.length * 1.4))) });
      const ex = clean(r?.exercises, s.words);
      if (ex.length < 2) throw new Error('The exercises came back incomplete. Try again, or use the offline drill.');
      setExSource(r._source);
      setExercises(withSayIt(ex, targets));
      setPhase('run');
    } catch (e) {
      setError(e.message);
      setPhase('start');
    }
  };

  const record = (ex, correct) => {
    if (correct === null) return;
    (results.current[ex.wordId] ||= { word: ex.word, marks: [] }).marks.push(correct);
  };

  const finish = () => {
    const entries = Object.entries(results.current);
    entries.forEach(([id, r]) => gradeWord(id, r.marks.every(Boolean) ? 4 : 1, aiMode ? 'session' : 'drill'));
    if (entries.length) logActivity('practiced', entries.length);
    setPhase('summary');
  };

  if (phase === 'loading') {
    return (
      <div className="screen center-col">
        <Spinner />
        <p className="h3">Writing exercises around your words…</p>
        <div className="chips center">{targets.map((w) => <span key={w.id} className="chip caught">{w.word}</span>)}</div>
        <p className="muted small">Muse Spark is putting them in new sentences you haven’t seen yet.</p>
      </div>
    );
  }

  if (phase === 'run') {
    const ex = exercises[i];
    return (
      <div className="screen session">
        <div className="row gap session-top">
          <button className="icon-btn" aria-label="End session" onClick={finish}><Icon name="close" /></button>
          <ProgressBar value={i / exercises.length} />
          <span className="small muted">{i + 1}/{exercises.length}</span>
        </div>
        {aiMode && <DemoTag source={exSource}>Demo exercises · built from samples, not by Meta</DemoTag>}
        <Exercise key={i} ex={ex} lang={lang} level={s.profile.level}
          onNext={(correct) => { record(ex, correct); if (i + 1 >= exercises.length) finish(); else setI(i + 1); }} />
      </div>
    );
  }

  if (phase === 'summary') {
    const rows = Object.values(results.current);
    return (
      <div className="screen">
        <header className="top"><h1 className="h1">Session done</h1></header>
        {rows.length === 0 ? <p className="muted">No answers recorded this time.</p> : (
          <ul className="summary">
            {rows.map((r) => (
              <li key={r.word}>
                <span className="wr-word">{r.word}</span>
                {r.marks.every(Boolean)
                  ? <span className="t-mint small">Solid. Back in a few days.</span>
                  : <span className="t-sun small">Coming back in 10 minutes.</span>}
              </li>
            ))}
          </ul>
        )}
        <div className="row gap">
          <button className="btn secondary grow" onClick={() => setPhase('start')}>Practice again</button>
          <button className="btn primary grow" onClick={() => go('listen')}>Keep listening</button>
        </div>
      </div>
    );
  }

  return (
    <div className="screen">
      <header className="top"><h1 className="h1">Practice</h1></header>
      <section className="card">
        <p className="h3">Your next session</p>
        <p className="muted small">Chosen from what’s due, what keeps tripping you up, and what you caught recently.</p>
        <div className="chips">
          {targets.map((w) => <span key={w.id} className={`chip ${isStruggling(w) ? 'caught' : ''}`}>{w.word}</span>)}
        </div>
        {error && <p className="error" role="alert">{error}</p>}
        <button className="btn primary big" onClick={() => start(true)}>Start personal session</button>
        <button className="btn ghost wide" onClick={() => start(false)}>Quick drill (offline)</button>
      </section>
      <p className="fine">Personal sessions mix meaning, context, gap-fill, sentence building, translation, explaining in your own words, and saying the word aloud. Open answers are judged by meaning, so there’s more than one right way to say it.</p>
    </div>
  );
}

/* ---------- building and validating exercises ---------- */

// Saved meanings can be long (a spoken answer saved as a meaning): the server limits their length, so send short ones.
const clip = (t, n) => { const x = String(t || '').replace(/\s+/g, ' ').trim(); return x.length <= n ? x : x.slice(0, n - 1).trimEnd() + '…'; };

function clean(list, vocab) {
  const out = [];
  for (const e of Array.isArray(list) ? list : []) {
    if (!e || typeof e !== 'object') continue;
    const w = vocab.find((v) => norm(v.word) === norm(e.word) || norm(v.lemma) === norm(e.word));
    if (!w) continue;
    const base = { ...e, wordId: w.id, word: w.word };
    if ((e.type === 'mcq' || e.type === 'context') && Array.isArray(e.options) && e.options.length >= 2 && e.prompt) {
      const right = e.options[e.answer_index];
      if (right == null) continue;
      const options = shuffle(e.options.map(String));
      out.push({ ...base, options, answer_index: options.indexOf(String(right)) });
    } else if (e.type === 'fill' && typeof e.sentence === 'string' && e.sentence.includes('___') && e.answer) {
      out.push(base);
    } else if (e.type === 'order' && typeof e.answer === 'string') {
      const toks = e.answer.trim().split(/\s+/);
      if (toks.length >= 3 && toks.length <= 12) out.push({ ...base, tokens: shuffle(toks) });
    } else if (e.type === 'translate' && e.source_ar && e.reference_en) {
      out.push(base);
    } else if (e.type === 'explain') {
      out.push({ ...base, prompt: e.prompt || `Explain what “${w.word}” means and use it in a sentence.` });
    }
  }
  return out;
}

function localDrill(targets, all, lang) {
  const ex = [];
  for (const w of targets) {
    const useAr = lang !== 'en' && !!w.meaning_ar;
    const label = (x) => (useAr ? x.meaning_ar || x.meaning_en : x.meaning_en || x.meaning_ar);
    const others = shuffle(all.filter((o) => o.id !== w.id && label(o))).slice(0, 3);
    if (label(w) && others.length >= 2) {
      const opts = shuffle([w, ...others]);
      ex.push({ type: 'mcq', word: w.word, wordId: w.id, prompt: `What does “${w.word}” mean?`, options: opts.map(label), answer_index: opts.indexOf(w), rtl: useAr });
    }
    const sent = w.example || w.contexts?.[0]?.sentence;
    const re = new RegExp(`\\b${esc(w.word)}\\b`, 'i');
    if (sent && re.test(sent)) ex.push({ type: 'fill', word: w.word, wordId: w.id, sentence: sent.replace(re, '___'), answer: w.word, hint: w.meaning_en || '' });
  }
  return shuffle(ex).slice(0, 8);
}

function withSayIt(list, targets) {
  if (!recordingSupported || !targets.length) return list;
  const pick = targets.filter((w) => isStruggling(w)).concat(targets).slice(0, 1);
  const say = pick.map((w) => ({ type: 'say', word: w.word, wordId: w.id, ipa: w.ipa }));
  const out = [...list];
  out.splice(Math.min(out.length, 2), 0, ...say);
  return out;
}

/* ---------- one exercise ---------- */

function Exercise({ ex, lang, level, onNext }) {
  const [verdict, setVerdict] = useState(null); // {correct, feedback, better}
  const [busy, setBusy] = useState(false);
  const [text, setText] = useState('');
  const [picked, setPicked] = useState(null);
  const [built, setBuilt] = useState([]);
  const [rec, setRec] = useState(null);
  const recRef = useRef(null);

  const judge = (correct, extra = {}) => setVerdict({ correct, ...extra });

  const aiGrade = async (answer) => {
    if (!answer.trim()) return toast('Write or say an answer first.');
    setBusy(true);
    try {
      const r = await askAI('grade', { exercise: ex, answer, level, lang });
      judge(r.correct === true, { feedbackAr: r.feedback_ar, feedbackEn: r.feedback_en, better: r.better, source: r._source });
    } catch (e) {
      setVerdict({ selfCheck: true, better: ex.reference_en, feedback: `${e.message} Compare with the model answer and mark yourself.` });
    } finally {
      setBusy(false);
    }
  };

  // Returns { text, source } after the second tap, or null (first tap, errors, too short).
  const voiceToText = async () => {
    if (recRef.current) {
      const r = recRef.current; recRef.current = null;
      let clip;
      try { clip = r.stop(); } catch (e) { setRec(null); toast(e.message || micMessage()); return null; }
      if (clip.seconds < 0.4) { setRec(null); toast('That was too short. Tap, speak, then tap again.'); return null; }
      setRec('sending');
      try {
        const out = await transcribe(clip.base64, { languages: ['English'] });
        setRec(null);
        if (out.source !== 'meta') { toast('Demo preview: recordings aren’t transcribed here, so this can’t be checked.'); return { ...out, demo: true }; }
        return out;
      } catch (e) { setRec(null); toast(e.message); return null; }
    }
    try {
      recRef.current = await startRecording({ maxMs: 15000 });
      setRec('rec');
    } catch (e) {
      recRef.current = null;
      setRec(null);
      toast(e?.message || micMessage());
    }
    return null;
  };

  const sayCheck = async () => {
    const out = await voiceToText();
    if (!out) return;
    if (out.demo) return setVerdict({ selfCheck: true, feedback: 'This preview can’t transcribe speech, so your pronunciation wasn’t checked. Mark it yourself.' });
    const heard = out.text;
    const target = norm(ex.word);
    const toks = norm(heard).split(' ');
    const ok = norm(heard).includes(target) || toks.some((t) => levenshtein(t, target) <= (target.length > 6 ? 1 : 0));
    judge(ok, { feedback: heard ? `Sada heard: “${heard}”` : 'Nothing was heard.', better: ok ? null : ex.word });
  };

  let body = null;
  if (ex.type === 'mcq' || ex.type === 'context') {
    body = (
      <>
        {ex.sentence && <p className="ex-sentence">“{ex.sentence}”</p>}
        <p className="ex-prompt">{ex.prompt}</p>
        <div className="options">
          {ex.options.map((o, k) => {
            const state = verdict ? (k === ex.answer_index ? 'right' : k === picked ? 'wrong' : '') : '';
            return (
              <button key={k} className={`option ${state}`} disabled={!!verdict} dir={ex.rtl ? 'rtl' : undefined}
                onClick={() => { setPicked(k); judge(k === ex.answer_index, { feedback: ex.explanation }); }}>
                {ex.rtl ? <Ar>{o}</Ar> : o}
              </button>
            );
          })}
        </div>
      </>
    );
  } else if (ex.type === 'fill') {
    const check = () => {
      const a = norm(text);
      const goods = [ex.answer, ...(ex.accept || [])].map(norm);
      if (goods.includes(a)) return judge(true);
      const close = goods.some((g) => g.length > 4 && levenshtein(a, g) <= 1);
      judge(close, { feedback: close ? `Almost exact. Watch the spelling: ${ex.answer}` : null, better: close ? null : ex.answer });
    };
    body = (
      <>
        <p className="ex-prompt">Complete the sentence</p>
        <p className="ex-sentence">{ex.sentence.split('___').map((part, k, arr) => (
          <React.Fragment key={k}>{part}{k < arr.length - 1 && <span className="blank">{verdict ? ex.answer : text || '\u2007\u2007\u2007\u2007'}</span>}</React.Fragment>
        ))}</p>
        {ex.hint && <p className="small muted">Hint: {/[\u0600-\u06FF]/.test(ex.hint) ? <Ar>{ex.hint}</Ar> : ex.hint}</p>}
        <input className="input" value={text} onChange={(e) => setText(e.target.value)} disabled={!!verdict} autoCapitalize="off" autoCorrect="off" spellCheck={false}
          onKeyDown={(e) => e.key === 'Enter' && text.trim() && !verdict && check()} aria-label="Your answer" />
        {!verdict && <button className="btn primary big" onClick={check} disabled={!text.trim()}>Check</button>}
      </>
    );
  } else if (ex.type === 'order') {
    const used = new Set(built);
    const check = () => judge(norm(built.map((k) => ex.tokens[k]).join(' ')) === norm(ex.answer), { better: ex.answer });
    body = (
      <>
        <p className="ex-prompt">Build the sentence with “{ex.word}”</p>
        <div className="built" aria-live="polite">
          {built.length === 0 ? <span className="muted small">Tap the words in order</span> :
            built.map((k, n) => <button key={n} className="tok on" disabled={!!verdict} onClick={() => setBuilt(built.filter((_, m) => m !== n))}>{ex.tokens[k]}</button>)}
        </div>
        <div className="tokens">
          {ex.tokens.map((t, k) => <button key={k} className="tok" disabled={used.has(k) || !!verdict} onClick={() => setBuilt([...built, k])}>{t}</button>)}
        </div>
        {!verdict && <button className="btn primary big" disabled={built.length !== ex.tokens.length} onClick={check}>Check</button>}
      </>
    );
  } else if (ex.type === 'translate' || ex.type === 'explain') {
    body = (
      <>
        <p className="ex-prompt">{ex.type === 'translate' ? `Say it in English, using “${ex.word}”` : ex.prompt}</p>
        {ex.type === 'translate' && <p className="ex-sentence"><Ar className="big-ar">{ex.source_ar}</Ar></p>}
        <div className="answer-box">
          <textarea className="input area sm" value={text} onChange={(e) => setText(e.target.value)} disabled={!!verdict || busy} placeholder="Type, or tap the mic and speak" aria-label="Your answer" />
          {recordingSupported && !verdict && (
            <button className={`mic small ${rec === 'rec' ? 'rec' : ''}`} aria-label={rec === 'rec' ? 'Stop and transcribe' : 'Answer by voice'}
              onClick={async () => { const t = await voiceToText(); if (t && !t.demo && t.text) setText((p) => (p ? `${p} ${t.text}` : t.text)); }}>
              {rec === 'sending' ? <span className="spinner light" /> : <Icon name={rec === 'rec' ? 'send' : 'mic'} size={20} />}
            </button>
          )}
        </div>
        {!verdict && <button className="btn primary big" disabled={busy || !text.trim()} onClick={() => aiGrade(text)}>{busy ? 'Checking…' : 'Check my answer'}</button>}
      </>
    );
  } else if (ex.type === 'say') {
    body = (
      <>
        <p className="ex-prompt">Say this word out loud</p>
        <button className="say-word" onClick={() => speakOnce(ex.word)} aria-label={`Hear ${ex.word}`}>{ex.word} <Icon name="speaker" size={22} /></button>
        {ex.ipa && <p className="muted">/{ex.ipa.replace(/^\/|\/$/g, '')}/</p>}
        {!verdict && (
          <button className={`btn big ${rec === 'rec' ? 'danger' : 'primary'}`} onClick={sayCheck} disabled={rec === 'sending'}>
            {rec === 'rec' ? 'Done, check it' : rec === 'sending' ? 'Listening back…' : 'Tap and say it'}
          </button>
        )}
        {!verdict && <button className="link" onClick={() => onNext(null)}>Can’t speak right now</button>}
      </>
    );
  }

  return (
    <section className="exercise">
      {body}
      {verdict && (
        <div className={`verdict ${verdict.selfCheck ? '' : verdict.correct ? 'good' : 'bad'}`} role="status">
          {!verdict.selfCheck && <p className="v-head">{verdict.correct ? 'Correct' : 'Not quite'}</p>}
          {verdict.feedback && <p>{/[\u0600-\u06FF]/.test(verdict.feedback) ? <Ar>{verdict.feedback}</Ar> : verdict.feedback}</p>}
          {(verdict.feedbackAr || verdict.feedbackEn) && <DemoTag source={verdict.source}>Demo grading · simple word check, not Meta</DemoTag>}
          {(verdict.feedbackAr || verdict.feedbackEn) && <p className="bi-wrap"><Bi ar={verdict.feedbackAr} en={verdict.feedbackEn} lang={lang} /></p>}
          {verdict.better && <p className="better">“{verdict.better}”</p>}
          {verdict.selfCheck ? (
            <div className="row gap"><button className="btn ghost grow" onClick={() => onNext(false)}>I missed it</button><button className="btn primary grow" onClick={() => onNext(true)}>I got it</button></div>
          ) : (
            <button className="btn primary big" onClick={() => onNext(verdict.correct)}>Next</button>
          )}
        </div>
      )}
    </section>
  );
}
